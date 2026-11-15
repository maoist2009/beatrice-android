package com.beatrice.lite.hook

import android.content.Context
import android.util.Log
import com.beatrice.lite.engine.EngineHub
import com.beatrice.lite.engine.EngineParams
import com.beatrice.lite.engine.LiveProcessor
import com.beatrice.lite.engine.PcmSource
import kotlin.math.max

/**
 * What the UI shows for a live hooked stream.
 *
 * [lookaheadMs] is how much input the backend still needs before it can emit
 * the next block; [queueMs] is converted audio already waiting to be handed
 * back to the target app. Their sum is the delay this session adds on top of
 * the target's own capture/playback buffering.
 */
data class HookStats(
    val lookaheadMs: Int,
    val queueMs: Int,
    val inferMs: Double,
    val primeMs: Int,
) {
    val totalMs: Int get() = lookaheadMs + queueMs
}

/**
 * One hooked audio stream: a target app's microphone at a fixed format,
 * converted by the resident engine.
 *
 * The target app calls [exchange] on its audio thread, so that call has to
 * stay cheap: it only resamples into the 16 kHz ring and pops whatever the
 * worker already produced. Everything expensive (engine init, inference,
 * output resampling) runs on this session's own thread.
 */
class HookSession(
    val key: String,
    val pkg: String,
    val rate: Int,
    val channels: Int,
    private val context: Context,
) {
    companion object {
        private const val TAG = "hook_session"
        private const val RING_SECONDS = 4
        private const val OUTPUT_SECONDS = 1.0
    }

    private val inResampler = StreamResampler(rate, 16_000)
    private var outResampler: StreamResampler? = null

    private val inLock = Object()
    private val ring = FloatArray(16_000 * RING_SECONDS)
    private var received = 0L

    private val outLock = Object()
    private val fifo = FloatFifo((rate * OUTPUT_SECONDS).toInt().coerceAtLeast(rate / 2))

    /**
     * Converted audio is only served once this much has queued up.
     *
     * Backends emit in blocks (RVC: 250 ms) while the app consumes
     * continuously, so serving the first buffer immediately would drain the
     * queue to empty before every block and mix raw audio into the stream at
     * the block rate. Priming past one block keeps the queue non-empty.
     */
    @Volatile private var blockMs = 0
    @Volatile private var primeMs = 300
    private val primeSamples: Int get() = rate * primeMs / 1000
    private var primed = false

    @Volatile private var running = true
    @Volatile private var engine: LiveProcessor? = null
    @Volatile private var pendingParams: EngineParams? = null

    @Volatile var lastUse = System.currentTimeMillis()
        private set

    @Volatile var delivered = 0L
        private set

    @Volatile var status: String = "starting"
        private set

    @Volatile var lastInferMs: Double = 0.0
        private set

    init {
        Log.d(TAG, "session ${System.identityHashCode(this)} for $key")
    }

    private val worker = Thread(::loop, "hook-${pkg.takeLast(12)}-${rate}").also {
        it.isDaemon = true
        it.priority = Thread.NORM_PRIORITY + 2
        it.start()
    }

    fun isActive(): Boolean = System.currentTimeMillis() - lastUse < 3_000

    /** Cheap snapshot for the UI: no locks on the audio path, one read per second. */
    fun stats(): HookStats {
        val queued = synchronized(outLock) { fifo.size }
        val pending = engine?.let { it.requiredSamples - it.outputSamples } ?: 0L
        return HookStats(
            lookaheadMs = (pending.coerceAtLeast(0L) * 1000 / 16_000).toInt(),
            queueMs = queued * 1000 / rate.coerceAtLeast(1),
            inferMs = lastInferMs,
            primeMs = primeMs,
        )
    }

    fun setParams(params: EngineParams) {
        pendingParams = params
    }

    /**
     * Feeds one capture buffer and returns its converted audio, or an empty
     * array while the stream is priming. Never a partial buffer: the caller
     * writes either the whole conversion or nothing, so a buffer never mixes
     * raw and converted audio.
     */
    fun exchange(pcm: FloatArray): FloatArray {
        lastUse = System.currentTimeMillis()
        HookBridge.touch(key)
        val mono16 = inResampler.process(pcm)
        if (mono16.isNotEmpty()) {
            synchronized(inLock) {
                for (v in mono16) {
                    ring[(received % ring.size).toInt()] = v
                    received++
                }
                inLock.notifyAll()
            }
        }
        val out = synchronized(outLock) {
            if (!primed) {
                if (fifo.size >= primeSamples) {
                    primed = true
                    Log.d(TAG, "primed with ${fifo.size} frames")
                } else {
                    // Keep the backlog bounded even if the app stops reading.
                    // The high-water mark is one prime plus one block: letting
                    // it creep to 2x prime (measured: 1 s) adds delay that the
                    // stream never gives back, because the queue is sticky.
                    val high = primeSamples + (rate * blockMs / 1000).coerceAtLeast(rate / 20)
                    if (fifo.size > high) {
                        Log.d(TAG, "trim queue ${fifo.size} -> $primeSamples")
                        fifo.dropOldest(fifo.size - primeSamples)
                    }
                    return FloatArray(0)
                }
            }
            // Running above the high-water mark (backlog left over from engine
            // start): shave a few ms per call instead of dropping a whole
            // block, so the delay converges back to prime without a jump.
            val high = primeSamples + (rate * blockMs / 1000).coerceAtLeast(rate / 20)
            if (fifo.size > high) {
                fifo.dropOldest(minOf(fifo.size - primeSamples, rate / 200))
            }
            val ready = fifo.read(pcm.size)
            if (ready == null) {
                // Underrun: go back to priming rather than splicing raw audio
                // into the middle of a converted stream every few buffers.
                primed = false
                Log.d(TAG, "underrun, fifo=${fifo.size} want=${pcm.size}")
                FloatArray(0)
            } else {
                delivered += ready.size
                ready
            }
        }
        return out
    }

    fun close() {
        running = false
        synchronized(inLock) { inLock.notifyAll() }
        runCatching { worker.join(700) }
    }

    // ------------------------------------------------------------- worker

    private fun loop() {
        Log.d(TAG, "worker ${Thread.currentThread().id} up for $key")
        while (running) {
            if (!HookBridge.isOwner(key) && !HookBridge.claim(key)) {
                // Another hooked app owns the stateful engine; wait until it
                // goes quiet and HookBridge hands it over.
                engine = null
                Thread.sleep(50)
                continue
            }
            val processor = ensureEngine()
            if (processor == null) {
                status = "engine unavailable"
                Thread.sleep(250)
                continue
            }
            pendingParams?.let {
                pendingParams = null
                runCatching { processor.setParams(it) }
            }

            val start: Int
            val end: Int
            val snapshot: FloatArray
            synchronized(inLock) {
                while (running && received < processor.requiredSamples) inLock.wait(20)
                if (!running) return
                if (received < processor.requiredSamples) continue
                if (received - processor.outputSamples > ring.size * 3L / 4) {
                    // More than a ring behind: the queued input is already
                    // stale, so restart instead of playing it back late.
                    processor.reset()
                    received = 0
                    synchronized(outLock) { fifo.clear(); primed = false }
                    continue
                }
                start = max(0, (processor.outputSamples - 560).toInt())
                end = processor.requiredSamples
                snapshot = FloatArray(end - start)
                for (i in start until end) {
                    snapshot[i - start] = ring[(((i % ring.size) + ring.size) % ring.size)]
                }
            }
            status = "running"
            val source = PcmSource { i -> if (i < start || i >= end) 0f else snapshot[i - start] }
            try {
                val t0 = System.nanoTime()
                val part = processor.next(source)
                lastInferMs = (System.nanoTime() - t0) / 1_000_000.0
                if (part.audio.isEmpty()) continue
                val resampler = outResampler ?: continue
                val out = resampler.process(part.audio)
                // Size the prefill from the backend's real block length: two
                // blocks absorb the gap between bursts without adding more
                // delay than the backend already has.
                val ms = out.size * 1000 / rate
                if (ms > blockMs) {
                    blockMs = ms
                    // 1.6 blocks: the native pipeline emits 250 ms per 250-300 ms
                    // of input, so the queue only has to survive one gap. Two
                    // blocks (the old value) measured ~1 s of added delay.
                    // rc.0 emits a regular 120 ms block, so it only needs a
                    // quarter second of cushion.
                    primeMs = (ms * 8 / 5).coerceIn(250, 800)
                }
                Log.d(TAG, "block in=${part.audio.size} out=${out.size} fed=${processor.outputSamples} fifo=${fifo.size} prime=${primeSamples} infer=${"%.1f".format(lastInferMs)}ms")
                if (out.isNotEmpty()) synchronized(outLock) { fifo.write(out) }
            } catch (t: Throwable) {
                Log.w(TAG, "conversion failed, restarting: ${t.message}")
                status = "error: ${t.message}"
                runCatching { processor.reset() }
                synchronized(inLock) { received = 0 }
                synchronized(outLock) { fifo.clear(); primed = false }
                Thread.sleep(100)
            }
        }
    }

    /** Loads the resident engine and takes ownership; null while unavailable. */
    private fun ensureEngine(): LiveProcessor? {
        val current = EngineHub.processor
        if (current != null && current === engine) return current
        if (!HookBridge.claim(key)) return null
        // Re-check after the hand-over: the processor may have been rebuilt.
        val now = EngineHub.processor
        if (now != null && now === engine) return now
        val built = try {
            val params = HookSettings.engineParams(context)
            EngineHub.ensureDefault(context, params).let { EngineHub.processor }
        } catch (t: Throwable) {
            Log.w(TAG, "no engine: ${t.message}")
            return null
        } ?: return null
        runCatching { built.setParams(HookSettings.engineParams(context)) }
        EngineHub.warmup(built)
        outResampler = StreamResampler(built.outputRate, rate)
        synchronized(inLock) {
            // The warm-up ran while the app was already being heard, so the
            // ring holds real audio whose absolute indices no longer match the
            // fresh processor. Slide the newest samples down to index 0
            // instead of throwing them away — but cap it at one second: a
            // longer backlog would be converted as fast as the engine can,
            // and every extra sample ends up as permanent queue delay.
            val keep = minOf(received, 8_000L).toInt()
            if (keep > 0) {
                val tail = FloatArray(keep)
                for (k in 0 until keep) {
                    tail[k] = ring[((received - keep + k) % ring.size).toInt()]
                }
                System.arraycopy(tail, 0, ring, 0, keep)
            }
            received = keep.toLong()
        }
        synchronized(outLock) { fifo.clear(); primed = false }
        engine = built
        Log.i(TAG, "engine for $key: ${built.javaClass.simpleName} @${built.outputRate} Hz -> $rate Hz")
        return built
    }
}
