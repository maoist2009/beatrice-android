package com.beatrice.lite.engine

/**
 * Byte layouts of the Beatrice 2.0.0-rc.0 paraphernalia `.bin` files.
 * Ported from beatrice-web `src/lib/layouts.ts`, which itself was read from
 * the official trainer exporter: headerless little-endian float16, no dims,
 * in `dump()` order.
 */
object C {
    const val IN_HOP = 160
    const val OUT_HOP = 240
    const val IN_SR = 16_000
    const val OUT_SR = 24_000
    const val PHONE_CHANNELS = 128
    const val PITCH_BINS = 448
    const val CODEBOOK_SIZE = 512
    const val KV_LENGTH = 384
    const val KV_SPK_CH = 128
    const val N_BLOCKS = 4
    const val N_FORMANT = 9
    const val WG_HIDDEN = 256
    const val PITCH_BINS_PER_OCTAVE = 96
}

/** IEEE 754 binary16 → float32, exact for every finite/binary value. */
fun f16ToF32(bytes: ByteArray): FloatArray {
    require(bytes.size % 2 == 0) { "float16 buffer has an odd byte count: ${bytes.size}" }
    val n = bytes.size / 2
    val out = FloatArray(n)
    for (i in 0 until n) {
        val lo = bytes[i * 2].toInt() and 0xff
        val hi = bytes[i * 2 + 1].toInt() and 0xff
        val h = lo or (hi shl 8)
        val sign = if (h and 0x8000 != 0) -1f else 1f
        val e = (h ushr 10) and 0x1f
        val f = h and 0x3ff
        out[i] = when (e) {
            0 -> sign * f * pow2i(-24)
            31 -> if (f != 0) Float.NaN else sign * Float.POSITIVE_INFINITY
            else -> sign * (1f + f / 1024f) * pow2i(e - 15)
        }
    }
    return out
}

/** Exact power of two: every value here is representable as float32. */
private fun pow2i(exponent: Int): Float {
    var r = 1f
    var b = 2f
    var e = exponent
    if (e < 0) { b = 1f / b; e = -e }
    while (e > 0) {
        if (e and 1 == 1) r *= b
        b *= b
        e = e shr 1
    }
    return r
}

class Lin(val w: FloatArray, val b: FloatArray?)

private class Reader(private val a: FloatArray, private val what: String) {
    var o = 0
    fun take(n: Int): FloatArray {
        require(o + n <= a.size) { "$what: layout overrun at $o+$n > ${a.size}" }
        val s = a.copyOfRange(o, o + n)
        o += n
        return s
    }
    fun lin(cout: Int, cin: Int, bias: Boolean = true): Lin =
        Lin(take(cout * cin), if (bias) take(cout) else null)
    fun end() = require(o == a.size) { "$what: ${a.size - o} float16 values left over (read $o)" }
}

// ------------------------------------------------------------ PitchEstimator

object PITCH {
    const val ch = 192
    const val inter = 384
    const val bins = 448
    const val nBlocks = 9
    const val dwK = 33
    const val embedK = 3
    const val ifIn = 192
    const val crIn = 256
}

interface ConvBlock { val dw: Lin; val pw1: Lin; val pw2: Lin }

class BlockLin(
    override val dw: Lin,
    override val pw1: Lin,
    override val pw2: Lin,
) : ConvBlock

class PitchWeights(
    val if0: Lin, val if1: Lin, val cr0: Lin, val cr1: Lin,
    val embed: Lin, val norm: Lin, val blocks: List<BlockLin>,
    val finalNorm: Lin, val head: Lin,
)

fun pitchExpectedHalves(): Int {
    val c = PITCH.ch
    val inter = PITCH.inter
    val bins = PITCH.bins
    val n = PITCH.nBlocks
    val dwK = PITCH.dwK
    val embedK = PITCH.embedK
    return (PITCH.ifIn * c + c) + (c * c + c) + (PITCH.crIn * c + c) + (c * c + c) +
        (c * c * embedK + c) + 2 * c +
        n * ((c * dwK + c) + (inter * c + inter) + (c * inter + c)) +
        2 * c + (bins * c + bins)
}

fun extractPitch(f32: FloatArray): PitchWeights {
    val c = PITCH.ch
    val inter = PITCH.inter
    val bins = PITCH.bins
    val r = Reader(f32, "pitch_estimator.bin")
    val if0 = r.lin(c, PITCH.ifIn)
    val if1 = r.lin(c, c)
    val cr0 = r.lin(c, PITCH.crIn)
    val cr1 = r.lin(c, c)
    val embed = r.lin(c, c * PITCH.embedK)
    val norm = r.lin(c, 1)
    val blocks = ArrayList<BlockLin>(PITCH.nBlocks)
    repeat(PITCH.nBlocks) {
        blocks += BlockLin(r.lin(c, PITCH.dwK), r.lin(inter, c), r.lin(c, inter))
    }
    val finalNorm = r.lin(c, 1)
    val head = r.lin(bins, c)
    r.end()
    return PitchWeights(if0, if1, cr0, cr1, embed, norm, blocks, finalNorm, head)
}

// ------------------------------------------------------------ PhoneExtractor

object PHONE {
    const val hidden = 128
    const val phone = 128
    const val inter = 384
    const val nBlocks = 20
    const val heads = 4
    const val embedK = 9
    const val dwK = 17
    val feCh = intArrayOf(16, 32, 64, 128, 128, 128)
    val feK = intArrayOf(10, 3, 3, 3, 3, 2)
    val feS = intArrayOf(5, 2, 2, 2, 2, 2)
}

class PhoneBlock(
    val wq: FloatArray, val wk: FloatArray, val wv: FloatArray,
    val bq: FloatArray, val bk: FloatArray, val bv: FloatArray,
    val out: Lin, val dw: Lin, val pw1: Lin, val pw2: Lin,
)

class PhoneWeights(
    val fe: List<FloatArray>, val embed: Lin, val norm: Lin,
    val blocks: List<PhoneBlock>, val finalNorm: Lin, val head: Lin,
)

fun phoneExpectedHalves(): Int {
    val h = PHONE.hidden
    val inter = PHONE.inter
    var n = 0
    var cin = 1
    for (i in 0 until 6) {
        n += PHONE.feCh[i] * cin * PHONE.feK[i]
        cin = PHONE.feCh[i]
    }
    n += h * h * PHONE.embedK + h + 2 * h
    n += PHONE.nBlocks * (
        3 * h * h + 3 * h + h * h + h +
            (h * PHONE.dwK + h) + (inter * h + inter) + (h * inter + h)
        )
    return n + 2 * h + (PHONE.phone * h + PHONE.phone)
}

fun extractPhone(f32: FloatArray): PhoneWeights {
    val h = PHONE.hidden
    val inter = PHONE.inter
    val heads = PHONE.heads
    val hd = h / heads
    val r = Reader(f32, "phone_extractor.bin")
    val fe = ArrayList<FloatArray>()
    var cin = 1
    for (i in 0 until 6) {
        fe += r.take(PHONE.feCh[i] * cin * PHONE.feK[i])
        cin = PHONE.feCh[i]
    }
    val embed = r.lin(h, h * PHONE.embedK)
    val norm = r.lin(h, 1)
    val blocks = ArrayList<PhoneBlock>(PHONE.nBlocks)
    repeat(PHONE.nBlocks) {
        val inW = r.take(3 * h * h)   // [heads, 3, hd, C]
        val inB = r.take(3 * h)       // [heads, 3, hd]
        val wq = FloatArray(h * h); val wk = FloatArray(h * h); val wv = FloatArray(h * h)
        val bq = FloatArray(h); val bk = FloatArray(h); val bv = FloatArray(h)
        val ws = arrayOf(wq, wk, wv)
        val bs = arrayOf(bq, bk, bv)
        for (hh in 0 until heads) for (p in 0 until 3) for (d in 0 until hd) {
            val row = hh * hd + d
            val src = ((hh * 3 + p) * hd + d) * h
            System.arraycopy(inW, src, ws[p], row * h, h)
            bs[p][row] = inB[(hh * 3 + p) * hd + d]
        }
        blocks += PhoneBlock(
            wq, wk, wv, bq, bk, bv,
            out = r.lin(h, h), dw = r.lin(h, PHONE.dwK),
            pw1 = r.lin(inter, h), pw2 = r.lin(h, inter),
        )
    }
    val finalNorm = r.lin(h, 1)
    val head = r.lin(PHONE.phone, h)
    r.end()
    return PhoneWeights(fe, embed, norm, blocks, finalNorm, head)
}

// ---------------------------------------------------------- WaveformGenerator

object WG {
    const val hidden = 256
    const val phone = 128
    const val bins = 448
    const val hop = 240
    const val irLen = 512
    const val kvLen = 384
    const val kvCh = 128
    const val attnCh = 128
    const val heads = 4
    const val preBlocks = 4
    const val irBlocks = 2
    const val apBlocks = 1
    const val pfBlocks = 1
    const val preEmbedK = 7
    const val preDelay = 2
    const val genEmbedK = 3
    const val dwK = 33
}

class WSBlock(
    override val dw: Lin,
    override val pw1: Lin,
    override val pw2: Lin,
) : ConvBlock
class PreBlock(
    val qW: FloatArray, val qB: FloatArray,
    val outW: FloatArray, val outB: FloatArray,
    val dw: Lin, val pw1: Lin, val pw2: Lin,
)
class WSStack(val embed: Lin, val blocks: List<WSBlock>)

class WGWeights(
    val embedPhone: Lin, val embedQPitch: FloatArray, val embedPitchFeat: Lin,
    val pre: PrenetWeights,
    val irGen: WSStack, val irPost: Lin, val irWindow: FloatArray,
    val apGen: WSStack, val apPost: Lin,
    val pfGen: WSStack, val pfPost: Lin,
)

class PrenetWeights(val embed: Lin, val norm: Lin, val blocks: List<PreBlock>, val finalNorm: Lin)

private fun wsStackHalves(nBlocks: Int, cin: Int): Int {
    val h = WG.hidden
    val inter = h * 2
    return (h * cin * WG.genEmbedK + h) + nBlocks * (
        (h * WG.dwK + h) + (inter * h + inter) + (h * inter + h)
        )
}

fun wgExpectedHalves(): Int {
    val h = WG.hidden
    val inter = h * 2
    var n = (h * WG.phone + h) + (WG.bins * h) + (h * 4 + h)
    n += (h * h * WG.preEmbedK + h) + 2 * h
    n += WG.preBlocks * (
        (WG.attnCh * h + WG.attnCh) + (h * WG.attnCh + h) +
            (h * WG.dwK + h) + (inter * h + inter) + (h * inter + h)
        )
    n += 2 * h
    n += wsStackHalves(WG.irBlocks, h) + (WG.irLen * h + WG.irLen) + WG.irLen
    n += wsStackHalves(WG.apBlocks, h) + WG.hop * h
    n += wsStackHalves(WG.pfBlocks, h) + WG.irLen * h
    return n
}

fun extractWaveformGenerator(f32: FloatArray): WGWeights {
    val h = WG.hidden
    val inter = h * 2
    val r = Reader(f32, "waveform_generator.bin")
    val embedPhone = r.lin(h, WG.phone)
    val embedQPitch = r.take(WG.bins * h)
    val embedPitchFeat = r.lin(h, 4)
    val preEmbed = r.lin(h, h * WG.preEmbedK)
    val preNorm = r.lin(h, 1)
    val preB = ArrayList<PreBlock>(WG.preBlocks)
    repeat(WG.preBlocks) {
        val qW = r.take(WG.attnCh * h); val qB = r.take(WG.attnCh)
        val outW = r.take(h * WG.attnCh); val outB = r.take(h)
        preB += PreBlock(qW, qB, outW, outB, r.lin(h, WG.dwK), r.lin(inter, h), r.lin(h, inter))
    }
    val preFinal = r.lin(h, 1)

    fun ws(nBlocks: Int, cin: Int): WSStack {
        val embed = r.lin(h, cin * WG.genEmbedK)
        val blocks = ArrayList<WSBlock>(nBlocks)
        repeat(nBlocks) { blocks += WSBlock(r.lin(h, WG.dwK), r.lin(inter, h), r.lin(h, inter)) }
        return WSStack(embed, blocks)
    }

    val irGen = ws(WG.irBlocks, h)
    val irPost = r.lin(WG.irLen, h)
    val irWindow = r.take(WG.irLen)
    val apGen = ws(WG.apBlocks, h)
    val apPost = r.lin(WG.hop, h, bias = false)
    val pfGen = ws(WG.pfBlocks, h)
    val pfPost = r.lin(WG.irLen, h, bias = false)
    r.end()
    return WGWeights(
        embedPhone, embedQPitch, embedPitchFeat,
        PrenetWeights(preEmbed, preNorm, preB, preFinal),
        irGen, irPost, irWindow, apGen, apPost, pfGen, pfPost,
    )
}

// ------------------------------------------------------------- speaker / KV

class SpeakerEmbeddings(
    val nSpeakers: Int,
    val codebook: FloatArray,   // n * 512 * 128
    val additive: FloatArray,   // n * 256
    val formant: FloatArray,    // 9 * 256
    val keyValue: FloatArray,   // n * 384 * 128
)

class SetterBlock(
    val kW: List<FloatArray>, val vW: List<FloatArray>,
    val kB: List<FloatArray>, val vB: List<FloatArray>,
)

class EmbeddingSetter(val attentionChannels: Int, val blocks: List<SetterBlock>)

/** rc.0 combined layout: codebook, additive, formant, key/value. */
fun parseSpeakerEmbeddings(bytes: ByteArray): SpeakerEmbeddings {
    val halves = bytes.size / 2
    val perSpeaker = C.CODEBOOK_SIZE * C.PHONE_CHANNELS + C.WG_HIDDEN + C.KV_LENGTH * C.KV_SPK_CH
    val fixed = C.N_FORMANT * C.WG_HIDDEN
    val n = (halves - fixed).toDouble() / perSpeaker
    require(n > 0 && n == Math.floor(n)) {
        "speaker_embeddings.bin size ${bytes.size} B is not (9*256 + n*(512*128+256+384*128)) float16"
    }
    val count = n.toInt()
    val all = f16ToF32(bytes)
    var o = 0
    fun take(len: Int): FloatArray {
        val s = all.copyOfRange(o, o + len)
        o += len
        return s
    }
    return SpeakerEmbeddings(
        nSpeakers = count,
        codebook = take(count * C.CODEBOOK_SIZE * C.PHONE_CHANNELS),
        additive = take(count * C.WG_HIDDEN),
        formant = take(C.N_FORMANT * C.WG_HIDDEN),
        keyValue = take(count * C.KV_LENGTH * C.KV_SPK_CH),
    )
}

/** embedding_setter.bin = 4 × CrossAttention.dump_kv. */
fun parseEmbeddingSetter(bytes: ByteArray): EmbeddingSetter {
    val halves = bytes.size / 2
    val a = halves.toDouble() / (C.N_BLOCKS * 258)
    require(a > 0 && a == Math.floor(a)) {
        "embedding_setter.bin size ${bytes.size} B does not match 4 × dump_kv layout"
    }
    val channels = a.toInt()
    val heads = 4
    val hd = channels / heads
    val all = f16ToF32(bytes)
    var o = 0
    fun take(len: Int): FloatArray {
        val s = all.copyOfRange(o, o + len)
        o += len
        return s
    }
    val blocks = ArrayList<SetterBlock>(C.N_BLOCKS)
    repeat(C.N_BLOCKS) {
        val kW = ArrayList<FloatArray>(); val vW = ArrayList<FloatArray>()
        val kB = ArrayList<FloatArray>(); val vB = ArrayList<FloatArray>()
        repeat(heads) { kW += take(hd * C.KV_SPK_CH); vW += take(hd * C.KV_SPK_CH) }
        repeat(heads) { kB += take(hd); vB += take(hd) }
        blocks += SetterBlock(kW, vW, kB, vB)
    }
    return EmbeddingSetter(channels, blocks)
}
