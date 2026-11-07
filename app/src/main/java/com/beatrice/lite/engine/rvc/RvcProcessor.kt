package com.beatrice.lite.engine.rvc

import android.util.Log
import com.beatrice.lite.engine.ChunkAudio
import com.beatrice.lite.engine.EngineParams
import com.beatrice.lite.engine.LiveProcessor
import com.beatrice.lite.engine.PcmSource
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * RVC v2 on the Hexagon NPU, driven through the streaming pipeline.
 *
 * The native side owns everything stateful (encoder context cache, SOLA, f0
 * track); this class only decides how much new audio to push per step and
 * applies the user's output gain to the blocks that come back.
 */
class RvcProcessor(
    private val voiceName: String,
    override val outputRate: Int,
    private val stepSamples: Int = 1600,
) : LiveProcessor {

    companion object {
        private const val TAG = "rvc_processor"
        private const val FEED_STRIDE = 4000
    }

    @Volatile private var params = EngineParams()
    private var fed = 0
    private var started = false
    @Volatile private var lastError: String? = null

    override val outputSamples: Long get() = fed.toLong()

    override val requiredSamples: Int get() = fed + stepSamples

    fun lastError(): String? = lastError

    fun name(): String = voiceName

    override fun reset() {
        // Soft reset: the native side clears the stream state and keeps the
        // single NPU worker alive (stopping/restarting it wedges the HTP
        // session, and assigning over a joinable std::thread aborts).
        val err = RvcJni.pipelineStreamStart()
        if (err.isNotEmpty()) {
            lastError = err
            Log.e(TAG, "streamStart: $err")
        }
        fed = 0
        started = true
    }

    override fun setParams(p: EngineParams) {
        params = p
        // Already-loaded pipeline: these are runtime tunables in the native
        // code, so a slider move lands on the next emitted block.
        RvcJni.pipelineSetPitch(p.pitchShift.toFloat())
        RvcJni.pipelineSetRmsMixRate(p.rmsMixRate.toFloat())
        RvcJni.pipelineSetProtect(p.protect.toFloat())
        RvcJni.pipelineSetFilterRadius(p.filterRadius)
    }

    override fun next(pcm: PcmSource, validFrames: Int): ChunkAudio {
        if (!started) reset()
        val from = fed
        val to = if (validFrames == Int.MAX_VALUE) fed + stepSamples else min(fed + stepSamples, validFrames)
        if (to <= from) return ChunkAudio(FloatArray(0), emptyList())
        val block = FloatArray(to - from) { i -> pcm.sample(from + i) }
        fed = to
        val out = RvcJni.pipelineStreamFeed(block)
        if (out.isEmpty()) {
            val err = RvcJni.pipelineLastError()
            if (err.isNotEmpty()) {
                lastError = err
                throw IllegalStateException(err)
            }
            return ChunkAudio(FloatArray(0), emptyList())
        }
        val gain = params.outputGain
        if (gain != 1.0) for (i in out.indices) out[i] = (out[i] * gain).toFloat()
        return ChunkAudio(out, emptyList())
    }

    override fun convert(pcm: FloatArray, progress: ((Float) -> Unit)?): FloatArray {
        reset()
        val parts = ArrayList<FloatArray>()
        var off = 0
        while (off < pcm.size) {
            val n = min(FEED_STRIDE, pcm.size - off)
            val block = pcm.copyOfRange(off, off + n)
            off += n
            fed = off
            val out = RvcJni.pipelineStreamFeed(block)
            if (out.isNotEmpty()) parts += out else {
                val err = RvcJni.pipelineLastError()
                if (err.isNotEmpty()) {
                    lastError = err
                    throw IllegalStateException(err)
                }
            }
            progress?.invoke(min(0.98f, off.toFloat() / pcm.size))
        }
        val tail = RvcJni.pipelineStreamEnd()
        if (tail.isNotEmpty()) parts += tail else {
            val err = RvcJni.pipelineLastError()
            if (err.isNotEmpty()) {
                lastError = err
                throw IllegalStateException(err)
            }
        }
        started = false

        var total = 0
        for (p in parts) total += p.size
        val merged = FloatArray(total)
        var at = 0
        for (p in parts) { System.arraycopy(p, 0, merged, at, p.size); at += p.size }

        val gain = params.outputGain
        if (gain != 1.0) for (i in merged.indices) merged[i] = (merged[i] * gain).toFloat()

        // The padded look-ahead emits up to one block of synthetic tail; keep
        // exactly the input duration so the clip ends where the source does.
        val keep = (pcm.size.toDouble() * outputRate / 16_000.0).roundToInt()
        progress?.invoke(1f)
        return if (keep in 1..merged.size) merged.copyOf(keep) else merged
    }

    override fun close() {
        RvcJni.pipelineDestroy()
    }
}
