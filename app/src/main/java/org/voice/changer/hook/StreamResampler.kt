package org.voice.changer.hook

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Streaming band-limited resampler.
 *
 * [org.voice.changer.audio.AudioIO.resample] resamples a finished clip; this
 * variant carries the filter history and the fractional read position across
 * calls, so a stream is resampled without clicks at every chunk boundary.
 * The kernel is the same windowed sinc, evaluated for each output sample.
 */
class StreamResampler(private val from: Int, private val to: Int) {
    private val identity = from == to
    private val step = from.toDouble() / to
    private val cutoff = min(1.0, to.toDouble() / from)
    private val half = 12

    /** Unread input: [pos] is the fractional read position inside it. */
    private var buffer = FloatArray(0)
    private var pos = 0.0

    fun reset() {
        buffer = FloatArray(0)
        pos = 0.0
    }

    fun process(input: FloatArray): FloatArray {
        if (identity) return input
        if (input.isEmpty() && buffer.isEmpty()) return EMPTY

        val joined = FloatArray(buffer.size + input.size)
        System.arraycopy(buffer, 0, joined, 0, buffer.size)
        System.arraycopy(input, 0, joined, buffer.size, input.size)

        var out = FloatArray(((joined.size - pos) * to / from).toInt().coerceAtLeast(1) + 1)
        var n = 0
        val last = joined.size - 1
        while (pos + half - 1 < last) {
            if (n == out.size) out = out.copyOf(out.size * 2 + 8)
            val base = floor(pos).toInt()
            val t = pos - base
            var acc = 0.0
            var norm = 0.0
            for (k in -half + 1..half) {
                val idx = base + k
                if (idx < 0) continue
                if (idx > last) break
                val x = t - k
                val c = if (abs(x) < 1e-9) {
                    1.0
                } else {
                    val w = sin(PI * x * cutoff) / (PI * x * cutoff)
                    val a = (x / half + 1.0) * 0.5
                    val win = if (a <= 0.0 || a >= 1.0) 0.0 else 0.5 - 0.5 * cos(2 * PI * a)
                    w * win
                }
                acc += joined[idx] * c
                norm += c
            }
            out[n++] = if (norm != 0.0) (acc / norm).toFloat() else 0f
            pos += step
        }
        if (n == 0) {
            buffer = joined
            return EMPTY
        }

        // Keep just enough history for the next call's kernel.
        val keepFrom = (pos.toInt() - half).coerceIn(0, joined.size)
        buffer = joined.copyOfRange(keepFrom, joined.size)
        pos -= keepFrom
        return out.copyOf(n)
    }

    private companion object {
        val EMPTY = FloatArray(0)
    }
}
