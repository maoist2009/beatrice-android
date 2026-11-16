package org.voice.changer.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Complex FFT: iterative radix-2 for powers of two, Bluestein otherwise.
 *
 * The vocoder needs sizes 480, 512 and 768, so the Bluestein path is not
 * optional. Accumulation is double precision, matching the reference, because
 * the synthesizer mixes overlapping frames and small phase errors are audible.
 */
private class Fft(val n: Int) {
    private val cosTable: DoubleArray
    private val sinTable: DoubleArray
    private val rev: IntArray
    private class Bl(
        val m: Int,
        val inner: Fft,
        val wr: DoubleArray,
        val wi: DoubleArray,
        val ar: DoubleArray,
        val ai: DoubleArray,
        val br: DoubleArray,
        val bi: DoubleArray,
    )

    private val bl: Bl?

    init {
        if (n and (n - 1) == 0) {
            val half = n shr 1
            cosTable = DoubleArray(half) { cos(-2.0 * PI * it / n) }
            sinTable = DoubleArray(half) { sin(-2.0 * PI * it / n) }
            rev = IntArray(n)
            var bits = 0
            while ((1 shl bits) < n) bits++
            for (i in 0 until n) {
                var r = 0
                for (b in 0 until bits) if (i and (1 shl b) != 0) r = r or (1 shl (bits - 1 - b))
                rev[i] = r
            }
            bl = null
        } else {
            var m = 1
            while (m < 2 * n - 1) m = m shl 1
            val wr = DoubleArray(n)
            val wi = DoubleArray(n)
            for (k in 0 until n) {
                val a = -PI * ((k.toLong() * k % (2L * n)).toDouble()) / n
                wr[k] = cos(a)
                wi[k] = sin(a)
            }
            val inner = Fft(m)
            val ar = DoubleArray(m)
            val ai = DoubleArray(m)
            ar[0] = 1.0
            for (k in 1 until n) {
                ar[k] = wr[k]
                ar[m - k] = wr[k]
                ai[k] = -wi[k]
                ai[m - k] = -wi[k]
            }
            inner.transform(ar, ai, false)
            cosTable = DoubleArray(1)
            sinTable = DoubleArray(1)
            rev = IntArray(1)
            bl = Bl(m, inner, wr, wi, ar, ai, DoubleArray(m), DoubleArray(m))
        }
    }

    /** In-place forward DFT, or inverse WITHOUT 1/n scaling. */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean = false) {
        val b = bl
        if (b != null) {
            bluestein(re, im, inverse, b)
            return
        }
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size shr 1
            val step = n / size
            var i = 0
            while (i < n) {
                var j = i
                var k = 0
                while (j < i + half) {
                    val c = cosTable[k]
                    val s = if (inverse) -sinTable[k] else sinTable[k]
                    val l = j + half
                    val tr = re[l] * c - im[l] * s
                    val ti = re[l] * s + im[l] * c
                    re[l] = re[j] - tr; im[l] = im[j] - ti
                    re[j] += tr; im[j] += ti
                    j++
                    k += step
                }
                i += size
            }
            size = size shl 1
        }
    }

    private fun bluestein(re: DoubleArray, im: DoubleArray, inverse: Boolean, b: Bl) {
        val n = this.n
        val m = b.m
        b.br.fill(0.0)
        b.bi.fill(0.0)
        for (k in 0 until n) {
            val c = b.wr[k]
            val s = if (inverse) -b.wi[k] else b.wi[k]
            b.br[k] = re[k] * c - im[k] * s
            b.bi[k] = re[k] * s + im[k] * c
        }
        b.inner.transform(b.br, b.bi, false)
        for (k in 0 until m) {
            val cr = b.ar[k]
            val ci = if (inverse) -b.ai[k] else b.ai[k]
            val tr = b.br[k] * cr - b.bi[k] * ci
            val ti = b.br[k] * ci + b.bi[k] * cr
            b.br[k] = tr
            b.bi[k] = ti
        }
        b.inner.transform(b.br, b.bi, true)
        val sc = 1.0 / m
        for (k in 0 until m) {
            b.br[k] *= sc
            b.bi[k] *= sc
        }
        for (k in 0 until n) {
            val c = b.wr[k]
            val s = if (inverse) -b.wi[k] else b.wi[k]
            re[k] = b.br[k] * c - b.bi[k] * s
            im[k] = b.br[k] * s + b.bi[k] * c
        }
    }
}

private val fftCache = HashMap<Int, Fft>()
private val scratchCache = HashMap<Int, Pair<DoubleArray, DoubleArray>>()

private fun getFft(n: Int): Fft = fftCache.getOrPut(n) { Fft(n) }

private fun scratch(n: Int): Pair<DoubleArray, DoubleArray> =
    scratchCache.getOrPut(n) { Pair(DoubleArray(n), DoubleArray(n)) }.also {
        it.first.fill(0.0)
        it.second.fill(0.0)
    }

/** real -> half spectrum (n/2+1 bins). [x] may be shorter than n (zero padded). */
fun rfft(x: FloatArray, n: Int, outRe: DoubleArray, outIm: DoubleArray) {
    val (re, im) = scratch(n)
    val len = minOf(x.size, n)
    for (i in 0 until len) re[i] = x[i].toDouble()
    getFft(n).transform(re, im, false)
    val h = n shr 1
    for (k in 0..h) {
        outRe[k] = re[k]
        outIm[k] = im[k]
    }
}

/** half spectrum -> real signal of length n, scaled by 1/n (torch.fft.irfft). */
fun irfft(inRe: DoubleArray, inIm: DoubleArray, n: Int, out: FloatArray) {
    val (re, im) = scratch(n)
    val h = n shr 1
    for (k in 0..h) {
        re[k] = inRe[k]
        im[k] = inIm[k]
    }
    for (k in h + 1 until n) {
        re[k] = inRe[n - k]
        im[k] = -inIm[n - k]
    }
    getFft(n).transform(re, im, true)
    val s = 1.0 / n
    for (i in 0 until n) out[i] = (re[i] * s).toFloat()
}
