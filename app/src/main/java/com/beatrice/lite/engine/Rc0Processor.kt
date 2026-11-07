package com.beatrice.lite.engine

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class FrameResult(
    val frame: Int,
    val bin: Int,
    val hz: Float,
    val midi: Float,
    val energy: Float,
    val unvoiced: Float,
    val half: Float,
    val dbl: Float,
    val targetBin: Int,
    val targetHz: Float,
)

data class ChunkAudio(val audio: FloatArray, val frames: List<FrameResult>)

/** Random access to the input timeline; negative indices and indices past the end read as silence. */
fun interface PcmSource {
    fun sample(index: Int): Float
}

/**
 * Incremental rc.0 processor: phone extraction and pitch analysis run two
 * frames ahead of synthesis, energy is read one frame back and quantised pitch
 * two frames back, exactly as the reference schedules it.
 */
class Rc0Processor private constructor(
    private val chunk: Int,
    private val model: BeatriceModel,
    private var params: EngineParams,
    private val pitchSession: StatefulSession,
    private val phoneSession: StatefulSession,
    private val vocoderSession: StatefulSession,
    private val random: kotlin.random.Random,
) : LiveProcessor {
    companion object {
        fun create(
            env: OrtEnvironment,
            model: BeatriceModel,
            chunkFrames: Int = 8,
            attnPositions: Int = 16,
            params: EngineParams = EngineParams(),
            random: kotlin.random.Random = kotlin.random.Random.Default,
        ): Rc0Processor {
            require(chunkFrames in 1..40) { "Invalid rc.0 chunk" }
            val chunk = max(4, ((chunkFrames + 3) / 4) * 4)
            val p = max(chunk / 4, min(256, attnPositions))

            val opts = OrtSession.SessionOptions()
            opts.setIntraOpNumThreads(max(1, Runtime.getRuntime().availableProcessors() / 2))
            opts.setInterOpNumThreads(1)
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)

            fun session(g: StreamingGraph): StatefulSession {
                val s = env.createSession(g.bytes, opts)
                return StatefulSession(env, s, g.states, g.inputs, g.outputs)
            }

            val pitch = session(buildRc0Pitch(model.pitch, chunk))
            val phone = session(buildRc0Phone(model.phone, chunk, p))
            val vocoder = session(buildRc0Vocoder(model.wg, chunk))
            return Rc0Processor(chunk, model, params, pitch, phone, vocoder, random).also { it.reset() }
        }
    }

    private val capacity = chunk * 8 + 64
    private val units = FloatArray(capacity * PHONE.phone)
    private val bins = IntArray(capacity)
    private val feats = FloatArray(capacity * 3)
    private val energy = FloatArray(capacity)
    private val inst = FloatArray(capacity * 192)
    private val corr = FloatArray(capacity * 256)

    private val wavInput = FloatArray(chunk * 160 + 80)
    private val instInput = FloatArray(192 * (chunk + 1))
    private val corrInput = FloatArray(256 * (chunk + 1))
    private val validInput = FloatArray(chunk + 1)
    private val xInput = FloatArray(WG.hidden * (chunk + 2))
    private val pitchLogits = FloatArray(PITCH.bins)
    private val phoneVec = FloatArray(PHONE.phone)
    private val irFrame = FloatArray(WG.irLen)
    private val apFrame = FloatArray(WG.hop)
    private val pfFrame = FloatArray(WG.irLen)

    private val fx = PitchFeatureExtractor()
    private var synth = WaveformSynth(model.wg.irWindow, random)

    private var analysis = 0
    private var synthesis = 0
    private var featureNext = 0

    private var preparedSpeaker = -1
    private var preparedFormant = Double.NaN
    private var kvDirty = true
    private var codebook = FloatArray(0)
    private var spkEmb = FloatArray(0)
    private var fmtEmb = FloatArray(0)
    private val kt = Array(WG.preBlocks) { FloatArray(0) }
    private val vv = Array(WG.preBlocks) { FloatArray(0) }

    val outputFrame: Int get() = synthesis

    override val outputRate: Int get() = 24_000

    override val outputSamples: Long get() = synthesis.toLong() * 160

    override val requiredSamples: Int get() {
        val frames = (synthesis + chunk + 2 + chunk - 1) / chunk * chunk
        return frames * 160 + 360
    }

    override fun setParams(p: EngineParams) {
        params = p
        if (p.speaker != preparedSpeaker || p.formantShift != preparedFormant) prepareSpeaker()
    }

    private fun prepareSpeaker() {
        val s = min(max(0, params.speaker), model.speakers.nSpeakers - 1)
        val h = WG.heads
        val hd = model.setter.attentionChannels / h
        val l = WG.kvLen
        val kc = WG.kvCh
        val kv = model.speakers.keyValue
        val kOff = s * l * kc
        if (kt[0].isEmpty()) {
            for (b in 0 until WG.preBlocks) {
                kt[b] = FloatArray(h * hd * l)
                vv[b] = FloatArray(h * l * hd)
            }
        }
        if (s != preparedSpeaker) {
            for (b in 0 until WG.preBlocks) {
                val blk = model.setter.blocks[b]
                val ktB = kt[b]
                val vvB = vv[b]
                for (hh in 0 until h) for (j in 0 until l) for (d in 0 until hd) {
                    var ak = blk.kB[hh][d].toDouble()
                    var av = blk.vB[hh][d].toDouble()
                    val kw = blk.kW[hh]
                    val vw = blk.vW[hh]
                    for (c in 0 until kc) {
                        val x = kv[kOff + j * kc + c].toDouble()
                        ak += kw[d * kc + c] * x
                        av += vw[d * kc + c] * x
                    }
                    ktB[(hh * hd + d) * l + j] = ak.toFloat()
                    vvB[(hh * l + j) * hd + d] = av.toFloat()
                }
            }
            val cb = C.CODEBOOK_SIZE * C.PHONE_CHANNELS
            codebook = model.speakers.codebook.copyOfRange(s * cb, (s + 1) * cb)
            spkEmb = model.speakers.additive.copyOfRange(s * WG.hidden, (s + 1) * WG.hidden)
            kvDirty = true
        }
        val fi = formantIndex(params.formantShift)
        fmtEmb = model.speakers.formant.copyOfRange(fi * WG.hidden, (fi + 1) * WG.hidden)
        preparedSpeaker = s
        preparedFormant = params.formantShift
    }

    override fun reset() {
        analysis = 0
        synthesis = 0
        featureNext = 0
        phoneSession.reset()
        pitchSession.reset()
        vocoderSession.reset()
        fx.reset()
        units.fill(0f)
        bins.fill(0)
        feats.fill(0f)
        energy.fill(0f)
        inst.fill(0f)
        corr.fill(0f)
        synth = WaveformSynth(model.wg.irWindow, random)
        kvDirty = true
        preparedSpeaker = -1
        prepareSpeaker()
    }

    override fun close() {
        pitchSession.close()
        phoneSession.close()
        vocoderSession.close()
    }

    private fun analyze(pcm: PcmSource, validFrames: Int) {
        val a = analysis
        val n = chunk
        for (i in wavInput.indices) wavInput[i] = pcm.sample(a * 160 - 40 + i)
        val unitsOut = phoneSession.run(mapOf("wav" to wavInput))["units"]!!

        while (featureNext <= a + n) {
            val f = featureNext++
            val slot = f % capacity
            val frame = FloatArray(WIN)
            for (i in 0 until WIN) frame[i] = pcm.sample(f * 160 - 200 + i)
            val ft = fx.process(frame)
            System.arraycopy(ft.instfreq, 0, inst, slot * 192, 192)
            System.arraycopy(ft.corr, 0, corr, slot * 256, 256)
            energy[slot] = ft.energy
        }
        for (i in 0..n) {
            val slot = (a + i) % capacity
            for (c in 0 until 192) instInput[c * (n + 1) + i] = inst[slot * 192 + c]
            for (c in 0 until 256) corrInput[c * (n + 1) + i] = corr[slot * 256 + c]
            validInput[i] = if (a + i < validFrames) 1f else 0f
        }
        val logits = pitchSession.run(
            mapOf(
                "instfreq" to instInput,
                "corr_diff" to corrInput,
                "valid" to validInput,
            ),
        )["logits"]!!

        val minBin = midiToBin(params.minMidi)
        val maxBin = max(minBin + 3, midiToBin(params.maxMidi))
        for (i in 0 until n) {
            val slot = (a + i) % capacity
            for (c in 0 until PHONE.phone) units[slot * PHONE.phone + c] = unitsOut[c * n + i]
            for (c in 0 until PITCH.bins) pitchLogits[c] = logits[c * n + i]
            val p = samplePitch(pitchLogits, minBin, maxBin, 4)
            bins[slot] = p.bin
            feats[slot * 3] = p.unvoiced
            feats[slot * 3 + 1] = p.half
            feats[slot * 3 + 2] = p.dbl
        }
        analysis += n
    }

    /** VectorQuantizer.forward (top-k cosine mean) + ConverterNetwork RMS norm. */
    private fun quantizeAndNorm(phone: FloatArray) {
        val n = PHONE.phone
        val k = min(8, max(0, params.vqNeighbors))
        if (k > 0 && codebook.isNotEmpty()) {
            var nrm = 0.0
            for (c in 0 until n) nrm += phone[c].toDouble() * phone[c].toDouble()
            nrm = 1.0 / max(sqrt(nrm), 1e-6)
            val q = FloatArray(n) { (phone[it] * nrm).toFloat() }
            val bi = IntArray(k) { -1 }
            val bs = DoubleArray(k) { Double.NEGATIVE_INFINITY }
            for (cb in 0 until C.CODEBOOK_SIZE) {
                var s = 0.0
                val off = cb * n
                for (c in 0 until n) s += q[c].toDouble() * codebook[off + c].toDouble()
                if (s > bs[k - 1]) {
                    var p = k - 1
                    while (p > 0 && bs[p - 1] < s) {
                        bs[p] = bs[p - 1]
                        bi[p] = bi[p - 1]
                        p--
                    }
                    bs[p] = s
                    bi[p] = cb
                }
            }
            phone.fill(0f)
            for (j in 0 until k) {
                val off = bi[j] * n
                for (c in 0 until n) phone[c] = phone[c] + codebook[off + c]
            }
            for (c in 0 until n) phone[c] = phone[c] / k
        }
        var ms = 0.0
        for (c in 0 until n) ms += phone[c].toDouble() * phone[c].toDouble()
        val g = 1.0 / sqrt(ms / n + 1.1920929e-7)
        for (c in 0 until n) phone[c] = (phone[c] * g).toFloat()
    }

    override fun next(pcm: PcmSource, validFrames: Int): ChunkAudio {
        val e = synthesis
        val n = chunk
        while (analysis < e + n + 2) analyze(pcm, validFrames)

        val w = model.wg
        val c = WG.hidden
        val t = n + 2
        val np = PHONE.phone
        xInput.fill(0f)
        for (i in 0 until t) {
            val f = e + i
            if (f >= validFrames) continue
            val pitchSlot = abs(f - 2) % capacity
            val energySlot = abs(f - 1) % capacity
            val phoneSlot = f % capacity
            System.arraycopy(units, phoneSlot * np, phoneVec, 0, np)
            quantizeAndNorm(phoneVec)
            val target = officialTargetPitch(bins[pitchSlot], PitchParams(
                params.pitchShift,
                params.averageSourcePitch,
                params.intonationIntensity,
                params.pitchCorrection,
                params.pitchCorrectionType,
            ))
            val en = energy[energySlot].toDouble()
            val f1 = feats[pitchSlot * 3].toDouble()
            val f2 = feats[pitchSlot * 3 + 1].toDouble()
            val f3 = feats[pitchSlot * 3 + 2].toDouble()
            for (cc in 0 until c) {
                var v = w.embedPhone.b!![cc].toDouble() +
                    w.embedQPitch[target * c + cc].toDouble() +
                    w.embedPitchFeat.b!![cc].toDouble() +
                    spkEmb[cc].toDouble() + fmtEmb[cc].toDouble()
                val pw = w.embedPhone.w
                val off = cc * np
                for (kk in 0 until np) v += pw[off + kk].toDouble() * phoneVec[kk].toDouble()
                val fw = w.embedPitchFeat.w
                val fo = cc * 4
                v += fw[fo].toDouble() * en + fw[fo + 1].toDouble() * f1 +
                    fw[fo + 2].toDouble() * f2 + fw[fo + 3].toDouble() * f3
                xInput[cc * t + i] = (v / (1 + exp(-v))).toFloat()
            }
        }

        val feeds = HashMap<String, FloatArray>()
        feeds["x"] = xInput
        for (b in 0 until WG.preBlocks) {
            feeds["kt$b"] = kt[b]
            feeds["v$b"] = vv[b]
        }
        kvDirty = false
        val out = vocoderSession.run(feeds)
        val ir = out["ir"]!!
        val ap = out["aperiodicity"]!!
        val pf = out["post_filter"]!!

        val parts = ArrayList<FloatArray>(n)
        val frames = ArrayList<FrameResult>(n)
        for (i in 0 until n) {
            if (e + i >= validFrames) break
            val f = e + i
            val slot = f % capacity
            val bin = bins[slot]
            val target = officialTargetPitch(bin, PitchParams(
                params.pitchShift,
                params.averageSourcePitch,
                params.intonationIntensity,
                params.pitchCorrection,
                params.pitchCorrectionType,
            ))
            for (cc in 0 until WG.irLen) {
                irFrame[cc] = ir[cc * n + i]
                pfFrame[cc] = pf[cc * n + i]
            }
            for (cc in 0 until WG.hop) apFrame[cc] = ap[cc * n + i]
            val audio = synth.push(irFrame, apFrame, pfFrame, binToHz(target))
            if (audio != null) parts += audio
            frames += FrameResult(
                frame = f,
                bin = bin,
                hz = binToHz(bin),
                midi = binToMidi(bin),
                energy = energy[slot],
                unvoiced = feats[slot * 3],
                half = feats[slot * 3 + 1],
                dbl = feats[slot * 3 + 2],
                targetBin = target,
                targetHz = binToHz(target),
            )
        }
        synthesis += n
        val audio = FloatArray(parts.size * WaveformSynth.HOP)
        parts.forEachIndexed { i, part -> System.arraycopy(part, 0, audio, i * WaveformSynth.HOP, WaveformSynth.HOP) }
        // Output gain is applied per chunk so live slider moves take effect immediately.
        val outGain = params.outputGain.toFloat()
        if (outGain != 1f) for (i in audio.indices) audio[i] = audio[i] * outGain
        return ChunkAudio(audio, frames)
    }

    fun finish(): FloatArray = synth.finish()

    /**
     * Converts a 16 kHz mono clip. Output length is exactly ceil(len/HOP)*HOP
     * samples at 24 kHz (the trailing half-frame is trimmed by the caller).
     */
    override fun convert(pcm: FloatArray, progress: ((Float) -> Unit)?): FloatArray {
        val count = (pcm.size + 159) / 160
        require(count in 3..6000) { "Use clips between 30 ms and 60 seconds" }
        reset()
        val gain = params.inputGain
        val source = PcmSource { i ->
            if (i in pcm.indices) (pcm[i] * gain).toFloat() else 0f
        }
        val result = FloatArray(count * WaveformSynth.HOP)
        var offset = 0
        while (synthesis < count) {
            val part = next(source, count)
            require(offset + part.audio.size <= result.size) { "Synthesis output exceeds preallocated audio" }
            System.arraycopy(part.audio, 0, result, offset, part.audio.size)
            offset += part.audio.size
            progress?.invoke(min(0.99f, synthesis.toFloat() / count))
        }
        val tail = finish()
        require(offset + tail.size == result.size) {
            "Unexpected synthesis length: ${offset + tail.size} vs ${result.size}"
        }
        System.arraycopy(tail, 0, result, offset, tail.size)
        progress?.invoke(1f)
        val keep = min(result.size, Math.round(pcm.size * 1.5f))
        return if (keep == result.size) result else result.copyOf(keep)
    }
}
