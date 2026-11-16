package org.voice.changer.engine

/**
 * Incremental (stateful) Beatrice 2.0.0-rc.0 graphs.
 *
 * Direct port of beatrice-web `src/lib/rc0/graphs.ts` + the shared helpers in
 * `src/lib/beta/graphs.ts`. Every frame passes through every layer exactly
 * once; causal convolutions carry explicit history and the phone self-attention
 * carries bounded K/V caches, so a chunk's result never depends on how the
 * stream was split.
 */

private const val MASK = -1e9f

class StateSpec(val input: String, val output: String, val dims: IntArray, val init: Float)

class StreamingGraph(
    val bytes: ByteArray,
    val states: List<StateSpec>,
    val inputs: List<GraphBuilder.IoSpec>,
    val outputs: List<GraphBuilder.IoSpec>,
)

private fun finish(g: GraphBuilder, states: List<StateSpec>): StreamingGraph =
    StreamingGraph(g.build(), states, g.inputSpecs, g.outputSpecs)

private fun remember(
    g: GraphBuilder,
    states: MutableList<StateSpec>,
    input: String,
    value: String,
    dims: IntArray,
    init: Float = 0f,
) {
    val output = "${input}_next"
    g.named("Identity", listOf(value), output)
    g.output(output, *dims)
    states += StateSpec(input, output, dims, init)
}

/** Causal conv with (k-1-delay) frames of explicit history. Only consumed frames stay cached. */
private fun conv(
    g: GraphBuilder,
    states: MutableList<StateSpec>,
    x: String,
    w: Lin,
    c: Int,
    k: Int,
    chunk: Int,
    name: String,
    delay: Int = 0,
    depthwise: Boolean = false,
): String {
    val left = k - 1 - delay
    val history = g.input(name, 1, c, left)
    val joined = g.concat(listOf(history, x), 2)
    remember(g, states, name, g.slice(joined, intArrayOf(chunk), intArrayOf(chunk + left), intArrayOf(2)), intArrayOf(1, c, left))
    return g.conv1d(joined, w.w, w.b, c, if (depthwise) 1 else c, k, groups = if (depthwise) c else 1)
}

private class StackW(
    val embed: Lin,
    val norm: Lin?,
    val blocks: List<ConvBlock>,
    val final: Lin?,
    val channels: Int,
    val inter: Int,
    val kernel: Int,
    val delay: Int,
    val dwKernel: Int,
)

private fun stack(g: GraphBuilder, states: MutableList<StateSpec>, input: String, w: StackW, chunk: Int, prefix: String): String {
    val c = w.channels
    var x = conv(g, states, input, w.embed, c, w.kernel, chunk, "${prefix}_embed", w.delay)
    if (w.norm != null) {
        x = g.transpose(g.layerNorm(g.transpose(x, intArrayOf(0, 2, 1)), w.norm.w, w.norm.b, c), intArrayOf(0, 2, 1))
    }
    w.blocks.forEachIndexed { i, b ->
        val identity = x
        var y = conv(g, states, x, b.dw, c, w.dwKernel, chunk, "${prefix}_dw$i", 0, depthwise = true)
        y = g.transpose(y, intArrayOf(0, 2, 1))
        if (w.norm != null) y = g.layerNorm(y, null, null, c)
        y = g.geluTanh(g.linear(y, b.pw1.w, b.pw1.b, w.inter, c))
        y = g.linear(y, b.pw2.w, b.pw2.b, c, w.inter)
        x = g.add(identity, g.transpose(y, intArrayOf(0, 2, 1)))
    }
    if (w.final != null) {
        x = g.transpose(g.layerNorm(g.transpose(x, intArrayOf(0, 2, 1)), w.final.w, w.final.b, c), intArrayOf(0, 2, 1))
    }
    return x
}

// ================================================================ PitchEstimator

fun buildRc0Pitch(w: PitchWeights, chunk: Int): StreamingGraph {
    val g = GraphBuilder("beatrice_rc0_pitch")
    val states = ArrayList<StateSpec>()
    val c = PITCH.ch
    val inst = g.input("instfreq", 1, PITCH.ifIn, chunk + 1)
    val corr = g.input("corr_diff", 1, PITCH.crIn, chunk + 1)
    val valid = g.input("valid", 1, 1, chunk + 1)

    fun branch(x: String, a: Lin, b: Lin, cin: Int): String =
        g.conv1d(g.geluTanh(g.conv1d(x, a.w, a.b, c, cin, 1)), b.w, b.b, c, c, 1)

    // rc.0 fixed the missing activation: GELU after the sum.
    val x = g.mul(
        g.geluTanh(g.add(branch(inst, w.if0, w.if1, PITCH.ifIn), branch(corr, w.cr0, w.cr1, PITCH.crIn))),
        valid,
    )
    val y = stack(
        g, states, x,
        StackW(w.embed, w.norm, w.blocks, w.finalNorm, c, PITCH.inter, PITCH.embedK, 1, PITCH.dwK),
        chunk, "pitch",
    )
    val logits = g.conv1d(y, w.head.w, w.head.b, PITCH.bins, c, 1)
    g.named("Identity", listOf(logits), "logits")
    g.output("logits", 1, PITCH.bins, chunk)
    return finish(g, states)
}

// ================================================================ PhoneExtractor

private fun streamingMha(
    g: GraphBuilder,
    states: MutableList<StateSpec>,
    xt: String,
    b: PhoneBlock,
    i: Int,
    chunk: Int,
    p: Int,
): String {
    val h = PHONE.heads
    val ch = PHONE.hidden
    val hd = ch / h
    val cs = chunk / 4

    // frame 4r+s -> subsequence s, position r: [1,chunk,Ch] -> [Cs,4,Ch] -> [4,Cs,Ch]
    val sub = g.transpose(g.reshape(xt, intArrayOf(cs, 4, ch)), intArrayOf(1, 0, 2))
    val n = g.layerNorm(sub, null, null, ch) // attn_norm affine is folded into in_proj

    fun proj(wt: FloatArray, bs: FloatArray): String =
        g.transpose(g.reshape(g.linear(n, wt, bs, ch, ch), intArrayOf(4, cs, h, hd)), intArrayOf(0, 2, 1, 3))

    val q = proj(b.wq, b.bq)
    val k = proj(b.wk, b.bk)
    val v = proj(b.wv, b.bv)

    val kc = g.input("p${i}_k", 4, h, p, hd)
    val vc = g.input("p${i}_v", 4, h, p, hd)
    val mc = g.input("p${i}_m", 4, 1, 1, p)
    val kf = g.concat(listOf(kc, k), 2) // [4,H,P+Cs,hd]
    val vf = g.concat(listOf(vc, v), 2)
    val mf = g.concat(listOf(mc, g.f32(g.uniq("mzero"), FloatArray(4 * cs), 4, 1, 1, cs)), 3)
    remember(g, states, "p${i}_k", g.slice(kf, intArrayOf(cs), intArrayOf(p + cs), intArrayOf(2)), intArrayOf(4, h, p, hd))
    remember(g, states, "p${i}_v", g.slice(vf, intArrayOf(cs), intArrayOf(p + cs), intArrayOf(2)), intArrayOf(4, h, p, hd))
    remember(g, states, "p${i}_m", g.slice(mf, intArrayOf(cs), intArrayOf(p + cs), intArrayOf(3)), intArrayOf(4, 1, 1, p), MASK)

    // causal part over the new keys: query r may see new key r' iff r' <= r
    val causal = FloatArray(cs * (p + cs))
    for (r in 0 until cs) for (j in p until p + cs) if (j - p > r) causal[r * (p + cs) + j] = MASK
    val causalC = g.f32(g.uniq("causal"), causal, 1, 1, cs, p + cs)

    var s = g.add(g.add(g.matmul(q, g.transpose(kf, intArrayOf(0, 1, 3, 2))), mf), causalC)
    s = g.softmax(s, -1)
    var o = g.matmul(s, vf)
    o = g.reshape(g.transpose(o, intArrayOf(0, 2, 1, 3)), intArrayOf(4, cs, ch))
    o = g.linear(o, b.out.w, b.out.b, ch, ch)
    val back = g.reshape(g.transpose(o, intArrayOf(1, 0, 2)), intArrayOf(1, chunk, ch))
    return g.add(xt, back)
}

fun buildRc0Phone(w: PhoneWeights, chunk: Int, attnPositions: Int): StreamingGraph {
    require(chunk % 4 == 0) { "rc.0 phone chunk must be a multiple of 4" }
    require(attnPositions >= chunk / 4) { "attention memory must cover at least one chunk" }
    val g = GraphBuilder("beatrice_rc0_phone")
    val states = ArrayList<StateSpec>()
    val c = PHONE.hidden
    var x = g.input("wav", 1, 1, chunk * 160 + 80) // [e*160-40, e*160+chunk*160+40)
    var cin = 1
    for (i in 0 until 6) {
        x = g.geluTanh(g.conv1d(x, w.fe[i], null, PHONE.feCh[i], cin, PHONE.feK[i], stride = PHONE.feS[i]))
        cin = PHONE.feCh[i]
    }
    // FeatureProjection norm: affine folded into backbone.embed -> normalise only
    x = g.transpose(g.layerNorm(g.transpose(x, intArrayOf(0, 2, 1)), null, null, c), intArrayOf(0, 2, 1))
    x = conv(g, states, x, w.embed, c, PHONE.embedK, chunk, "phone_embed", 0)
    var xt = g.layerNorm(g.transpose(x, intArrayOf(0, 2, 1)), w.norm.w, w.norm.b, c)
    w.blocks.forEachIndexed { i, b ->
        xt = streamingMha(g, states, xt, b, i, chunk, attnPositions)
        xt = convBranch(g, states, xt, b.dw, b.pw1, b.pw2, c, PHONE.inter, PHONE.dwK, chunk, "phone_dw$i", true)
    }
    xt = g.geluTanh(g.layerNorm(xt, w.finalNorm.w, w.finalNorm.b, c)) // head after GELU
    val units = g.conv1d(g.transpose(xt, intArrayOf(0, 2, 1)), w.head.w, w.head.b, PHONE.phone, c, 1)
    g.named("Identity", listOf(units), "units")
    g.output("units", 1, PHONE.phone, chunk)
    return finish(g, states)
}

/** ConvNeXt conv branch on channels-last [1,ch,chunk] with explicit dw-conv history. */
private fun convBranch(
    g: GraphBuilder,
    states: MutableList<StateSpec>,
    xt: String,
    dw: Lin,
    pw1: Lin,
    pw2: Lin,
    ch: Int,
    inter: Int,
    k: Int,
    chunk: Int,
    name: String,
    norm: Boolean,
): String {
    var y = conv(g, states, g.transpose(xt, intArrayOf(0, 2, 1)), dw, ch, k, chunk, name, 0, depthwise = true)
    y = g.transpose(y, intArrayOf(0, 2, 1))
    if (norm) y = g.layerNorm(y, null, null, ch)
    y = g.geluTanh(g.linear(y, pw1.w, pw1.b, inter, ch))
    y = g.linear(y, pw2.w, pw2.b, ch, inter)
    return g.add(xt, y)
}

// ================================================================ Vocoder

/**
 * inputs : x [1,256,chunk+2]  (SiLU'd embedding sum, 2 frames of prenet look-ahead)
 *          kt{i} [1,H,hd,384], v{i} [1,H,384,hd]  fixed speaker key/value per prenet block
 * outputs: ir [1,512,chunk], aperiodicity [1,240,chunk], post_filter [1,512,chunk]
 */
fun buildRc0Vocoder(w: WGWeights, chunk: Int): StreamingGraph {
    val g = GraphBuilder("beatrice_rc0_vocoder")
    val states = ArrayList<StateSpec>()
    val c = WG.hidden
    val inter = c * 2
    val a = WG.attnCh
    val h = WG.heads
    val hd = a / h

    val xIn = g.input("x", 1, c, chunk + 2)
    var x = conv(g, states, xIn, w.pre.embed, c, WG.preEmbedK, chunk, "pre_embed", WG.preDelay)
    var xt = g.layerNorm(g.transpose(x, intArrayOf(0, 2, 1)), w.pre.norm.w, w.pre.norm.b, c)
    w.pre.blocks.forEachIndexed { i, b ->
        val kt = g.input("kt$i", 1, h, hd, WG.kvLen)
        val vv = g.input("v$i", 1, h, WG.kvLen, hd)
        val n = g.layerNorm(xt, null, null, c)
        val q = g.transpose(g.reshape(g.linear(n, b.qW, b.qB, a, c), intArrayOf(1, chunk, h, hd)), intArrayOf(0, 2, 1, 3))
        var o = g.matmul(g.softmax(g.matmul(q, kt), -1), vv) // [1,H,chunk,hd]
        o = g.reshape(g.transpose(o, intArrayOf(0, 2, 1, 3)), intArrayOf(1, chunk, a))
        xt = g.add(xt, g.linear(o, b.outW, b.outB, c, a))
        xt = convBranch(g, states, xt, b.dw, b.pw1, b.pw2, c, inter, WG.dwK, chunk, "pre_dw$i", true)
    }
    xt = g.layerNorm(xt, w.pre.finalNorm.w, w.pre.finalNorm.b, c)
    val pre = g.transpose(xt, intArrayOf(0, 2, 1))

    fun gen(s: WSStack, post: Lin, cout: Int, name: String) {
        val y = stack(
            g, states, pre,
            StackW(s.embed, null, s.blocks, null, c, inter, WG.genEmbedK, 0, WG.dwK),
            chunk, name,
        )
        val value = g.conv1d(g.silu(y), post.w, post.b, cout, c, 1)
        g.named("Identity", listOf(value), name)
        g.output(name, 1, cout, chunk)
    }

    gen(w.irGen, w.irPost, WG.irLen, "ir")
    gen(w.apGen, w.apPost, WG.hop, "aperiodicity")
    gen(w.pfGen, w.pfPost, WG.irLen, "post_filter")
    return finish(g, states)
}
