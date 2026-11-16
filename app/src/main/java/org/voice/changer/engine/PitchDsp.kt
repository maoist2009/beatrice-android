package org.voice.changer.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Host-side DSP of Beatrice, ported from beatrice-web `src/lib/dsp.ts`. */
const val HOP = 160
const val WIN = 560
const val CUTOFF = 64
const val MAX_CORR = 256
const val CORR_WIN = 304
const val BINS = 448
const val BAND = 4
const val BPO = 96
const val BINS_PER_SEMITONE = BPO / 12

private object Tables {
    val cosT = FloatArray(CUTOFF * WIN)
    val sinT = FloatArray(CUTOFF * WIN)
    val wcos = FloatArray(WIN)

    init {
        for (k in 0 until CUTOFF) for (n in 0 until WIN) {
            val a = (-2.0 * PI * k * n) / WIN
            cosT[k * WIN + n] = cos(a).toFloat()
            sinT[k * WIN + n] = sin(a).toFloat()
        }
        for (n in 0 until WIN) wcos[n] = sin(PI * (n + 0.5) / WIN).toFloat()
    }
}

class FrameFeatures(val instfreq: FloatArray, val corr: FloatArray, val energy: Float)

/**
 * Sequential feature extractor; keeps the previous frame's spectrum for the
 * instantaneous-frequency channels, so frames must be fed in order.
 */
class PitchFeatureExtractor {
    private val prevRe = DoubleArray(CUTOFF)
    private val prevIm = DoubleArray(CUTOFF)
    private var first = true

    fun reset() {
        prevRe.fill(0.0)
        prevIm.fill(0.0)
        first = true
    }

    /** [frame] = 560 samples: original[f*160-200 .. f*160+360), zero outside the signal. */
    fun process(frame: FloatArray): FrameFeatures {
        val inst = FloatArray(3 * CUTOFF)
        for (k in 0 until CUTOFF) {
            var re = 0.0
            var im = 0.0
            val co = k * WIN
            for (n in 0 until WIN) {
                val v = frame[n].toDouble()
                re += v * Tables.cosT[co + n]
                im += v * Tables.sinT[co + n]
            }
            inst[k] = log10(hypot(re, im) + 1e-5).toFloat()
            if (first) {
                inst[CUTOFF + k] = 0f
                inst[2 * CUTOFF + k] = 0f
            } else {
                val dre = re * prevRe[k] + im * prevIm[k]
                val dim = im * prevRe[k] - re * prevIm[k]
                val den = hypot(dre, dim) + 1e-5
                inst[CUTOFF + k] = (dre / den).toFloat()
                inst[2 * CUTOFF + k] = (dim / den).toFloat()
            }
            prevRe[k] = re
            prevIm[k] = im
        }
        first = false

        val corr = FloatArray(MAX_CORR)
        for (t in 0 until MAX_CORR) {
            val lag = t + 1
            var acc = 0.0
            val a = MAX_CORR
            val b = MAX_CORR - lag
            for (j in 0 until CORR_WIN) {
                val d = (frame[a + j] - frame[b + j]).toDouble()
                acc += d * d
            }
            acc *= 2.0 / CORR_WIN
            corr[t] = sqrt(if (acc < 0) 0.0 else acc).toFloat()
        }

        var e = 0.0
        for (n in 0 until WIN) {
            val v = (frame[n] * Tables.wcos[n]).toDouble()
            e += v * v
        }
        if (e < 1e-3) e = 1e-3
        return FrameFeatures(inst, corr, (log10(e) * 0.5).toFloat())
    }
}

class PitchSample(val bin: Int, val unvoiced: Float, val half: Float, val dbl: Float)

/** One frame's logits -> quantised bin + voicing features. */
fun samplePitch(logits: FloatArray, minBin: Int = 1, maxBin: Int = logits.size - 1, bandWidth: Int = BAND): PitchSample {
    val bins = logits.size
    require(bins == 384 || bins == 448) { "Invalid Beatrice pitch geometry" }
    require(minBin <= maxBin && maxBin - minBin + 1 >= bandWidth) { "Pitch range must cover at least $bandWidth bins" }

    val p = DoubleArray(bins)
    var mx = Double.NEGATIVE_INFINITY
    for (k in 0 until bins) {
        val v = logits[k]
        require(!v.isNaN() && !v.isInfinite()) { "PitchEstimator returned non-finite logits" }
        if (v > mx) mx = v.toDouble()
    }
    var sum = 0.0
    for (k in 0 until bins) {
        p[k] = kotlin.math.exp(logits[k].toDouble() - mx)
        sum += p[k]
    }
    for (k in 0 until bins) p[k] /= sum

    val unvoiced = p[0]
    p[0] = -100.0
    for (k in 1 until bins) if (k < minBin || k > maxBin) p[k] = -100.0

    val nBand = bins - bandWidth + 1
    val band = DoubleArray(nBand)
    var best = 0
    var bestV = Double.NEGATIVE_INFINITY
    for (k in 0 until nBand) {
        var acc = 0.0
        for (j in 0 until bandWidth) acc += p[k + j]
        band[k] = acc
        if (acc > bestV) {
            bestV = acc
            best = k
        }
    }
    var arg = best
    var argV = Double.NEGATIVE_INFINITY
    var k = best
    while (k < best + bandWidth && k < bins) {
        if (p[k] > argV) {
            argV = p[k]
            arg = k
        }
        k++
    }
    val bandP = band[best]
    var half = 0.0
    var dbl = 0.0
    if (best > BPO) {
        var idx = best - BPO
        if (idx < 1) idx = 1
        half = band[idx] / (bandP + 1e-6)
    }
    if (best <= bins - bandWidth - BPO) {
        var idx = best + BPO
        if (idx > bins - bandWidth) idx = bins - bandWidth
        dbl = band[idx] / (bandP + 1e-6)
    }
    return PitchSample(arg, unvoiced.toFloat(), half.toFloat(), dbl.toFloat())
}

fun binToHz(bin: Int): Float = (55.0 * 2.0.pow(bin.toDouble() / BPO)).toFloat()
fun binToMidi(bin: Int): Float = (33.0 + bin.toDouble() / BINS_PER_SEMITONE).toFloat()
fun midiToBin(midi: Double): Int =
    min(BINS - 1, max(1, ((midi - 33) * BINS_PER_SEMITONE).roundToInt()))

fun midiToHz(m: Double): Double = 440.0 * 2.0.pow((m - 69) / 12.0)

class PitchParams(
    val pitchShift: Double = 0.0,          // semitones, [-24, 24]
    val averageSourcePitch: Double = 52.0, // official core unit: bins
    val intonationIntensity: Double = 1.0,
    val pitchCorrection: Double = 0.0,     // [0,1]
    val pitchCorrectionType: Int = 0,
)

/** Quantised pitch fed to the waveform generator (ProcessorCore2::Process1). */
fun officialTargetPitch(quantized: Int, p: PitchParams): Int {
    val k = BINS_PER_SEMITONE.toDouble()
    var tmp = p.averageSourcePitch + (quantized - p.averageSourcePitch) * p.intonationIntensity + k * p.pitchShift
    if (p.pitchCorrection != 0.0) {
        if (p.pitchCorrectionType == 0) {
            val nearest = (floor(tmp / k) + 0.5) * k
            val nd = (tmp - nearest) * (2 / k)
            tmp = if (abs(nd) < 1e-4) nearest
            else nearest + nd * abs(nd).pow(-p.pitchCorrection) * (k / 2)
        } else {
            val nearest = (tmp / k).roundToInt().toDouble() * k
            val nd = (tmp - nearest) * (2 / k)
            tmp = when {
                p.pitchCorrection > 1 - 1e-4 -> nearest
                nd >= 0 -> nearest + nd.pow(1 / (1 - p.pitchCorrection)) * (k / 2)
                else -> nearest - (-nd).pow(1 / (1 - p.pitchCorrection)) * (k / 2)
            }
        }
    }
    return min(BINS - 1, max(1, tmp.roundToInt()))
}

/** formant shift (semitones, [-2,2] in 0.5 steps) -> index into the 9 embeddings. */
fun formantIndex(semis: Double): Int = ((min(2.0, max(-2.0, semis)) * 2 + 4)).roundToInt()
