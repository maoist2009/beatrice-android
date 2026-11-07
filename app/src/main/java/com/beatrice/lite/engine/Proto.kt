package com.beatrice.lite.engine

import java.io.ByteArrayOutputStream

/**
 * Minimal ONNX ModelProto writer, a direct port of beatrice-web's `onnx.ts`.
 *
 * The Beatrice paraphernalia ship headerless float16 weight dumps, not ONNX
 * files, so the graph is assembled at load time from those weights. Keeping
 * the builder shape-for-shape identical to the reference is what makes the
 * numeric behaviour comparable: same ops, same attribute encoding, same
 * accumulation order.
 */
internal class Pb {
    private val out = ByteArrayOutputStream(1 shl 16)

    fun varint(value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7f).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(b)
                return
            }
            out.write(b or 0x80)
        }
    }

    fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    fun int(field: Int, v: Long) {
        tag(field, 0)
        varint(v)
    }

    fun float(field: Int, v: Float) {
        tag(field, 5)
        val bits = v.toRawBits()
        out.write(bits and 0xff)
        out.write((bits ushr 8) and 0xff)
        out.write((bits ushr 16) and 0xff)
        out.write((bits ushr 24) and 0xff)
    }

    fun bytes(field: Int, data: ByteArray) {
        tag(field, 2)
        varint(data.size.toLong())
        out.write(data)
    }

    fun string(field: Int, s: String) = bytes(field, s.toByteArray(Charsets.UTF_8))

    /** Nested message: length is known only after the body is built. */
    fun message(field: Int, body: Pb) {
        val data = body.toByteArray()
        bytes(field, data)
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

sealed class Attr {
    data class I(val v: Long) : Attr()
    data class F(val v: Float) : Attr()
    data class Is(val v: List<Long>) : Attr()
    data class Fs(val v: List<Float>) : Attr()
    data class S(val v: String) : Attr()
}

internal fun attrI(v: Int): Attr = Attr.I(v.toLong())
internal fun attrF(v: Float): Attr = Attr.F(v)
internal fun attrIs(vararg v: Int): Attr = Attr.Is(v.map { it.toLong() })
internal fun attrS(v: String): Attr = Attr.S(v)

private fun tensorProto(name: String, dataType: Int, dims: IntArray, raw: ByteArray): Pb {
    val w = Pb()
    for (d in dims) w.int(1, d.toLong())
    w.int(2, dataType.toLong())
    w.string(8, name)
    w.bytes(9, raw)
    return w
}

private fun valueInfo(name: String, elemType: Int, dims: IntArray): Pb {
    val shape = Pb()
    for (d in dims) {
        val dim = Pb()
        dim.int(1, d.toLong())
        shape.message(1, dim)
    }
    val tt = Pb()
    tt.int(1, elemType.toLong())
    tt.message(2, shape)
    val tp = Pb()
    tp.message(1, tt)
    val vi = Pb()
    vi.string(1, name)
    vi.message(2, tp)
    return vi
}

/** [N, Cin, T] tensor graph with runtime state tensors, as used by Beatrice. */
class GraphBuilder(private val graphName: String) {
    private val nodes = ArrayList<Pb>()
    private val inits = ArrayList<Pb>()
    private val inputs = ArrayList<Pb>()
    private val outputs = ArrayList<Pb>()
    private var counter = 0

    data class IoSpec(val name: String, val dims: IntArray) {
        override fun equals(other: Any?): Boolean =
            other is IoSpec && name == other.name && dims.contentEquals(other.dims)
        override fun hashCode(): Int = name.hashCode() * 31 + dims.contentHashCode()
    }

    val inputSpecs = ArrayList<IoSpec>()
    val outputSpecs = ArrayList<IoSpec>()

    fun uniq(prefix: String): String = "${prefix}_${counter++}"

    fun input(name: String, vararg dims: Int): String {
        inputs += valueInfo(name, 1, dims)
        inputSpecs += IoSpec(name, dims)
        return name
    }

    fun output(name: String, vararg dims: Int): String {
        outputs += valueInfo(name, 1, dims)
        outputSpecs += IoSpec(name, dims)
        return name
    }

    fun f32(name: String, data: FloatArray, vararg dims: Int): String {
        val n = dims.fold(1) { a, b -> a * b }
        require(data.size == n) { "const $name: ${data.size} values for dims ${dims.toList()}" }
        val raw = ByteArray(data.size * 4)
        for (i in data.indices) {
            val bits = data[i].toRawBits()
            raw[i * 4] = (bits and 0xff).toByte()
            raw[i * 4 + 1] = ((bits ushr 8) and 0xff).toByte()
            raw[i * 4 + 2] = ((bits ushr 16) and 0xff).toByte()
            raw[i * 4 + 3] = ((bits ushr 24) and 0xff).toByte()
        }
        inits += tensorProto(name, 1, dims, raw)
        return name
    }

    fun i64(name: String, data: IntArray): String {
        val raw = ByteArray(data.size * 8)
        for (i in data.indices) {
            var v = data[i].toLong()
            for (b in 0 until 8) {
                raw[i * 8 + b] = (v and 0xff).toByte()
                v = v ushr 8
            }
        }
        inits += tensorProto(name, 7, intArrayOf(data.size), raw)
        return name
    }

    fun node(
        op: String,
        inputsIn: List<String>,
        attrs: Map<String, Attr> = emptyMap(),
        nOut: Int = 1,
    ): List<String> {
        val outs = List(nOut) { uniq(op.lowercase()) }
        val w = Pb()
        for (i in inputsIn) w.string(1, i)
        for (o in outs) w.string(2, o)
        w.string(3, uniq("n_$op"))
        w.string(4, op)
        for ((k, a) in attrs) {
            val aw = Pb()
            aw.string(1, k)
            when (a) {
                is Attr.F -> { aw.float(2, a.v); aw.int(20, 1) }
                is Attr.I -> { aw.int(3, a.v); aw.int(20, 2) }
                is Attr.S -> { aw.string(4, a.v); aw.int(20, 3) }
                is Attr.Fs -> { for (v in a.v) aw.float(7, v); aw.int(20, 6) }
                is Attr.Is -> { for (v in a.v) aw.int(8, v); aw.int(20, 7) }
            }
            w.message(5, aw)
        }
        nodes += w
        return outs
    }

    fun named(op: String, inputsIn: List<String>, outName: String, attrs: Map<String, Attr> = emptyMap()): String {
        val w = Pb()
        for (i in inputsIn) w.string(1, i)
        w.string(2, outName)
        w.string(3, uniq("n_$op"))
        w.string(4, op)
        for ((k, a) in attrs) {
            val aw = Pb()
            aw.string(1, k)
            when (a) {
                is Attr.F -> { aw.float(2, a.v); aw.int(20, 1) }
                is Attr.I -> { aw.int(3, a.v); aw.int(20, 2) }
                is Attr.S -> { aw.string(4, a.v); aw.int(20, 3) }
                is Attr.Fs -> { for (v in a.v) aw.float(7, v); aw.int(20, 6) }
                is Attr.Is -> { for (v in a.v) aw.int(8, v); aw.int(20, 7) }
            }
            w.message(5, aw)
        }
        nodes += w
        return outName
    }

    // ---- convenience ops ----
    fun add(a: String, b: String) = node("Add", listOf(a, b))[0]
    fun mul(a: String, b: String) = node("Mul", listOf(a, b))[0]
    fun sub(a: String, b: String) = node("Sub", listOf(a, b))[0]
    fun matmul(a: String, b: String) = node("MatMul", listOf(a, b))[0]
    fun transpose(x: String, perm: IntArray) = node("Transpose", listOf(x), mapOf("perm" to attrIs(*perm)))[0]
    fun reshape(x: String, shape: IntArray) = node("Reshape", listOf(x, i64(uniq("shape"), shape)))[0]
    fun softmax(x: String, axis: Int = -1) = node("Softmax", listOf(x), mapOf("axis" to attrI(axis)))[0]
    fun geluTanh(x: String) = node("Gelu", listOf(x), mapOf("approximate" to attrS("tanh")))[0]
    fun sigmoid(x: String) = node("Sigmoid", listOf(x))[0]
    fun tanh(x: String) = node("Tanh", listOf(x))[0]
    fun silu(x: String) = mul(x, sigmoid(x))
    fun scalar(v: Float) = f32(uniq("c"), floatArrayOf(v), 1)

    fun layerNorm(x: String, scale: FloatArray?, bias: FloatArray?, channels: Int, eps: Float = 1e-5f): String {
        val s = f32(uniq("ln_w"), scale ?: FloatArray(channels) { 1f }, channels)
        val b = f32(uniq("ln_b"), bias ?: FloatArray(channels), channels)
        return node("LayerNormalization", listOf(x, s, b), mapOf("axis" to attrI(-1), "epsilon" to attrF(eps)))[0]
    }

    fun conv1d(
        x: String,
        w: FloatArray,
        b: FloatArray?,
        cout: Int,
        cinPerGroup: Int,
        k: Int,
        stride: Int = 1,
        pads: IntArray = intArrayOf(0, 0),
        groups: Int = 1,
    ): String {
        val wt = f32(uniq("conv_w"), w, cout, cinPerGroup, k)
        val ins = arrayListOf(x, wt)
        if (b != null) ins += f32(uniq("conv_b"), b, cout)
        return node(
            "Conv", ins,
            mapOf(
                "kernel_shape" to attrIs(k),
                "strides" to attrIs(stride),
                "pads" to attrIs(pads[0], pads[1]),
                "group" to attrI(groups),
            ),
        )[0]
    }

    /** x [.., Cin] · Wᵀ + b with torch Linear weight [Cout, Cin]. */
    fun linear(x: String, w: FloatArray, b: FloatArray?, cout: Int, cin: Int): String {
        val wt = FloatArray(cin * cout)
        for (o in 0 until cout) for (i in 0 until cin) wt[i * cout + o] = w[o * cin + i]
        var y = matmul(x, f32(uniq("lin_w"), wt, cin, cout))
        if (b != null) y = add(y, f32(uniq("lin_b"), b, cout))
        return y
    }

    fun slice(x: String, starts: IntArray, ends: IntArray, axes: IntArray): String {
        return node(
            "Slice",
            listOf(x, i64(uniq("st"), starts), i64(uniq("en"), ends), i64(uniq("ax"), axes)),
        )[0]
    }

    fun concat(inputsIn: List<String>, axis: Int): String =
        node("Concat", inputsIn, mapOf("axis" to attrI(axis)))[0]

    fun build(opset: Int = 20): ByteArray {
        val g = Pb()
        for (n in nodes) g.message(1, n)
        g.string(2, graphName)
        for (t in inits) g.message(5, t)
        for (i in inputs) g.message(11, i)
        for (o in outputs) g.message(12, o)

        val m = Pb()
        m.int(1, 9) // ir_version 9 (opset 20)
        m.string(2, "beatrice-lite-android")
        m.message(7, g)
        val op = Pb()
        op.string(1, "")
        op.int(2, opset.toLong())
        m.message(8, op)
        return m.toByteArray()
    }
}
