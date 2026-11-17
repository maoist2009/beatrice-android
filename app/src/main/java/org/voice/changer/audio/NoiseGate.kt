package org.voice.changer.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Amplitude gate for the input path.
 *
 * A conversion model cannot tell silence from audio: fed a quiet room it still
 * synthesises breath and room tone in the target voice, which is what makes a
 * hooked call sound noisy between phrases. Gating the input keeps those frames
 * out of the model instead of trying to remove them afterwards.
 *
 * The threshold is dBFS on a 10 ms peak envelope. Opening ramps over 5 ms and
 * closing over 30 ms, so a gate that opens mid-word does not click and a word
 * tail is not chopped. [setThresholdDb] with a value >= 0 disables the gate.
 *
 * Not thread safe: each capture path owns one instance and processes its own
 * buffers.
 */
class NoiseGate(private val rate: Int) {

    private var thresholdDb = Float.NaN
    private var threshold = 0f
    private var env = 0f
    private var gain = 1f

    private val envDecay = exp(-1.0 / (0.010 * rate)).toFloat()
    private val openStep = 1f / (0.005f * rate)
    private val closeStep = 1f / (0.030f * rate)

    val enabled: Boolean get() = threshold > 0f

    fun setThresholdDb(db: Float) {
        if (db == thresholdDb) return
        thresholdDb = db
        threshold = if (db >= 0f) 0f else 10.0.pow(db / 20.0).toFloat()
    }

    /** Applies the gate in place to the first [n] samples of [buf]. */
    fun process(buf: FloatArray, n: Int = buf.size) {
        if (!enabled) {
            gain = 1f
            return
        }
        for (i in 0 until n) {
            val x = buf[i]
            val a = abs(x)
            env = if (a > env) a else env * envDecay
            val want = if (env >= threshold) 1f else 0f
            gain = when {
                gain < want -> minOf(want, gain + openStep)
                gain > want -> maxOf(want, gain - closeStep)
                else -> gain
            }
            buf[i] = x * gain
        }
    }

    fun reset() {
        env = 0f
        gain = 1f
    }
}
