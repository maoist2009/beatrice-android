package org.voice.changer.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/**
 * Source-filter tail of the rc.0 vocoder, ported from beatrice-web
 * `src/lib/beta/synth.ts`.
 *
 * A frame is filtered only after the following frame resolves its last pitch
 * mark, which is why output lags input by two hops and [finish] flushes the
 * remaining frames.
 */
class WaveformSynth(
    private val window: FloatArray,
    private val random: Random = Random.Default,
) {
    companion object {
        const val HOP = 240
        const val IR = 512
        const val NOISE = 480
        const val POST = 768
        const val RING = 240 * 12
    }

    private val periodic = FloatArray(RING)
    private val aperiodic = FloatArray(RING)
    private val filtered = FloatArray(RING)
    private val excitation = FloatArray(NOISE)
    private var phaseSum = 0.0
    private var prevPhase = 0.0
    private var frame = 0
    private var emitted = 0
    private var finished = false

    private val amp = FloatArray(257)
    private val phase = FloatArray(257)
    private val prevAmp = FloatArray(257)
    private val prevIrPhase = FloatArray(257)
    private val re512 = DoubleArray(257)
    private val im512 = DoubleArray(257)
    private val ir = FloatArray(IR)
    private val re480 = DoubleArray(241)
    private val im480 = DoubleArray(241)
    private val noise = FloatArray(NOISE)
    private val re768 = DoubleArray(385)
    private val im768 = DoubleArray(385)
    private val filterRe = DoubleArray(385)
    private val filterIm = DoubleArray(385)
    private val post = FloatArray(POST)
    private val signal = FloatArray(HOP)
    private val previousFilter = FloatArray(IR)
    private val filterBuffer = FloatArray(IR)
    private val hann = FloatArray(NOISE) { (0.5 - 0.5 * cos(2 * PI * it / NOISE)).toFloat() }

    init {
        reset()
    }

    fun reset() {
        periodic.fill(0f)
        aperiodic.fill(0f)
        filtered.fill(0f)
        previousFilter.fill(0f)
        prevAmp.fill(0f)
        prevIrPhase.fill(0f)
        frame = 0
        emitted = 0
        finished = false
        phaseSum = random.nextFloat().toDouble()
        prevPhase = phaseSum
        // The reference starts with two random frames, not a zero-prefixed excitation.
        for (i in 0 until NOISE) excitation[i] = random.nextFloat() - 0.5f
    }

    fun push(irRaw: FloatArray, aper: FloatArray, filter: FloatArray, f0: Float): FloatArray? {
        check(!finished) { "Cannot append audio after finish()" }
        require(irRaw.size == IR && aper.size == HOP && filter.size == IR) { "Invalid Beatrice synthesis inputs" }
        require(f0.isFinite() && f0 > 0f && f0 < 24000f) { "Invalid Beatrice synthesis inputs" }
        for (arr in arrayOf(irRaw, aper, filter)) for (v in arr) require(v.isFinite()) { "Vocoder produced NaN/Infinity" }

        val f = frame
        val base = f * HOP
        for (k in 0..256) {
            amp[k] = exp(irRaw[k].toDouble()).toFloat()
            require(amp[k].isFinite()) { "Vocoder impulse-response amplitude overflow" }
            val raw = if (k == 0 || k == 256) 0.0 else irRaw[256 + k].toDouble()
            phase[k] = (raw + if (k % 2 == 1) PI else 0.0).toFloat()
        }

        val increment = f0 / 24000f
        for (n in 0 until HOP) {
            if (f == 0 && n == 0) continue
            phaseSum += increment
            val current = (phaseSum - floor(phaseSum)).toFloat()
            val previous = prevPhase
            if (previous > current) {
                val numerator = (1.0 - previous).toFloat()
                val fraction = numerator / (numerator + current)
                val ampS = if (n == 0) prevAmp else amp
                val phaseS = if (n == 0) prevIrPhase else phase
                for (k in 0..256) {
                    val a1 = (k * (-2.0 * PI / IR)).toFloat()
                    val a2 = a1 * fraction
                    val angle = (phaseS[k] + a2).toDouble()
                    re512[k] = (ampS[k] * cos(angle)).toFloat().toDouble()
                    im512[k] = (ampS[k] * sin(angle)).toFloat().toDouble()
                }
                irfft(re512, im512, IR, ir)
                for (i in 0 until IR) {
                    val idx = (base + n - 1 + i) % RING
                    periodic[idx] = periodic[idx] + (ir[i] * window[i])
                }
            }
            prevPhase = current.toDouble()
        }
        System.arraycopy(amp, 0, prevAmp, 0, 257)
        System.arraycopy(phase, 0, prevIrPhase, 0, 257)

        if (f > 0) {
            System.arraycopy(excitation, HOP, excitation, 0, NOISE - HOP)
            for (i in HOP until NOISE) excitation[i] = random.nextFloat() - 0.5f
        }
        rfft(excitation, NOISE, re480, im480)
        re480[0] = 0.0
        im480[0] = 0.0
        for (k in 1..HOP) {
            re480[k] = (re480[k].toFloat() * aper[k - 1]).toDouble()
            im480[k] = (im480[k].toFloat() * aper[k - 1]).toDouble()
        }
        irfft(re480, im480, NOISE, noise)
        for (i in 0 until NOISE) {
            val idx = (base + i) % RING
            aperiodic[idx] = aperiodic[idx] + (noise[i] * hann[i])
        }

        if (f > 0) applyFilter((f - 1) * HOP, previousFilter)
        System.arraycopy(filter, 0, previousFilter, 0, IR)
        frame++
        return if (f >= 2) emit() else null
    }

    private fun applyFilter(base: Int, coefficients: FloatArray) {
        System.arraycopy(coefficients, 0, filterBuffer, 0, IR)
        filterBuffer[0] = filterBuffer[0] + 1f
        rfft(filterBuffer, POST, filterRe, filterIm)
        for (source in arrayOf(periodic, aperiodic)) {
            for (i in 0 until HOP) {
                val idx = (base + i) % RING
                signal[i] = source[idx]
                source[idx] = 0f
            }
            rfft(signal, POST, re768, im768)
            for (k in 0..POST / 2) {
                val r = re768[k]
                val im = im768[k]
                re768[k] = r * filterRe[k] - im * filterIm[k]
                im768[k] = r * filterIm[k] + im * filterRe[k]
            }
            irfft(re768, im768, POST, post)
            for (i in 0 until POST) {
                val idx = (base + i) % RING
                filtered[idx] = filtered[idx] + post[i]
            }
        }
        if (base == 0) for (i in 0 until 120) filtered[i] = 0f
    }

    private fun emit(): FloatArray {
        val out = FloatArray(HOP)
        val base = emitted * HOP + 120
        emitted++
        for (i in 0 until HOP) {
            val index = (base + i) % RING
            val value = filtered[index]
            require(value.isFinite()) { "Non-finite synthesized audio" }
            out[i] = value
            filtered[index] = 0f
        }
        return out
    }

    fun finish(): FloatArray {
        if (finished) return FloatArray(0)
        finished = true
        if (frame == 0) return FloatArray(0)
        applyFilter((frame - 1) * HOP, previousFilter)
        val result = FloatArray((frame - emitted) * HOP)
        var off = 0
        while (emitted < frame) {
            System.arraycopy(emit(), 0, result, off, HOP)
            off += HOP
        }
        return result
    }
}
