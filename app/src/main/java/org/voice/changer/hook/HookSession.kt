package org.voice.changer.hook

import android.content.Context
import android.util.Log
import org.voice.changer.engine.EngineHub
import org.voice.changer.engine.EngineParams
import org.voice.changer.engine.LiveProcessor
import org.voice.changer.engine.PcmSource
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
    /** 16 kHz samples of capture dropped when the engine took over. */
    val baseSamples: Long = 0L,
    /** Measured capture-to-hand-over delay of the last served buffer. */
    val measuredMs: Long = 0L,
) {
    val totalMs: Int get() = if (measuredMs > 0) measuredMs.toInt() else lookaheadMs + queueMs
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

        /**
         * Cushion the last session settled on. Process-wide so switching
         * between apps (each gets its own session) does not relearn it, and
         * deliberately not persisted: a reboot starts optimistic again.
         */
        @Volatile private var learnedPrimeMs = 0
    }

    private val inResampler = StreamResampler(rate, 16_000)
    private var outResampler: StreamResampler? = null

    private val inLock = Object()
    private val ring = FloatArray(16_000 * RING_SECONDS)
    /** Wall-clock capture time of each ring slot; used to measure real delay. */
    private val ringAt = LongArray(16_000 * RING_SECONDS)
    private var received = 0L

    /** Converted samples thrown away by a queue trim (shifts the mapping). */
    private var outDropped = 0L

    /** End-to-end delay of the last served buffer: capture time -> read time. */
    @Volatile var lastLatencyMs = 0L
        private set

    private val outLock = Object()
    private val fifo = FloatFifo((rate * OUTPUT_SECONDS).toInt().coerceAtLeast(rate / 2))

    /**
     * Converted audio is only served once this much has queued up.
     *
     * The queue exists to absorb the gap between backend blocks: RVC emits
     * 250 ms of audio per 200-300 ms of input, so a level of one block keeps
     * the stream fed without adding a whole extra block of delay. If the
     * target app jitters more than that, [onUnderrun] raises it — and it
     * decays again after a long clean stretch.
     */
    @Volatile private var blockMs = 0
    @Volatile private var primeMs = 300
    private val primeSamples: Int get() = rate * primeMs / 1000
    private var primed = false
    private var cleanBlocks = 0
    private var lastTrimAt = 0L

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

    /** Capture samples dropped when the engine took over (16 kHz). */
    @Volatile private var inputBase = 0L

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
            baseSamples = inputBase,
            measuredMs = lastLatencyMs,
        )
    }

    fun setParams(params: EngineParams) {
        pendingParams = params
    }

    /** Capture samples this stream starts from; the debug harness times with it. */
    fun baseSamples(): Long = inputBase

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
            val capturedAt = System.currentTimeMillis()
            synchronized(inLock) {
                for (v in mono16) {
                    val slot = (received % ring.size).toInt()
                    ring[slot] = v
                    ringAt[slot] = capturedAt
                    received++
                }
                inLock.notifyAll()
            }
        }
        val out = synchronized(outLock) {
            if (!primed) {
                if (fifo.size >= primeSamples) {
                    primed = true
                    // The engine emits its first blocks in a burst while it
                    // catches up, so the queue can already be several blocks
                    // deep. That excess never drains on its own (input and
                    // output run at the same rate), so drop the oldest audio
                    // once, here, and start the stream at the target depth.
                    if (fifo.size > primeSamples) {
                        Log.d(TAG, "prime trim ${fifo.size} -> $primeSamples")
                        dropConverted(fifo.size - primeSamples)
                    }
                    Log.d(TAG, "primed with ${fifo.size} frames (prime=$primeMs ms)")
                } else {
                    // Keep the backlog bounded even if the app stops reading.
                    val high = primeSamples + rate / 20
                    if (fifo.size > high) {
                        Log.d(TAG, "trim queue ${fifo.size} -> $primeSamples")
                        dropConverted(fifo.size - primeSamples)
                    }
                    return FloatArray(0)
                }
            }
            // High-water trim. Catch-up after a slow first block pushes whole
            // blocks of already-stale audio into the queue, and the level never
            // comes back down on its own (input and output run at the same
            // rate), so it has to be cut once. Only a full block of excess is
            // dropped, at most every couple of seconds, so a steady stream is
            // never touched.
            val high = primeSamples + (rate * blockMs / 1000).coerceAtLeast(rate / 10)
            val now = System.currentTimeMillis()
            if (fifo.size > high && now - lastTrimAt > 2_000) {
                Log.d(TAG, "queue trim ${fifo.size} -> $primeSamples")
                dropConverted(fifo.size - primeSamples)
                lastTrimAt = now
            }
            val ready = fifo.read(pcm.size)
            if (ready == null) {
                // Underrun: the target app is reading faster than the backend
                // emits. Ask for more cushion next time instead of splicing
                // raw audio into a converted stream every few buffers.
                primed = false
                onUnderrun()
                Log.d(TAG, "underrun, fifo=${fifo.size} want=${pcm.size} prime=$primeMs")
                FloatArray(0)
            } else {
                // Real delay: the first sample of this buffer was captured at
                // ringAt[delivered + dropped] and is only now handed over.
                // delivered counts output samples, the ring counts 16 kHz
                // capture samples, so convert before indexing.
                val slot = (((delivered + outDropped) * 16_000 / rate) % ring.size).toInt()
                val at = ringAt[slot]
                if (at > 0) lastLatencyMs = now - at
                delivered += ready.size
                ready
            }
        }
        return out
    }

    /** Drops the oldest converted audio; caller holds [outLock]. */
    private fun dropConverted(n: Int) {
        fifo.dropOldest(n)
        outDropped += n
    }

    /** Grows the cushion by 100 ms, up to 800 ms; shared by every session. */
    private fun onUnderrun() {
        val next = (primeMs + 100).coerceAtMost(800)
        primeMs = next
        learnedPrimeMs = next
    }

    private fun setPrime(ms: Int) {
        primeMs = ms
        learnedPrimeMs = ms
    }

    private fun minPrime(): Int = maxOf(120, blockMs)

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
                    synchronized(outLock) { fifo.clear(); primed = false; delivered = 0; outDropped = 0 }
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
                    // Start at one block: the native pipeline emits 250 ms per
                    // 200-300 ms of input, so a single block absorbs the gap.
                    // A session that underruns grows this (onUnderrun) and the
                    // value is shared with later sessions in this process.
                    if (learnedPrimeMs > 0) primeMs = learnedPrimeMs else setPrime(ms)
                }
                cleanBlocks++
                if (cleanBlocks >= 50 && primeMs > minPrime()) {
                    // Ten seconds without a dropout: creep back towards the
                    // minimum instead of keeping the learned cushion forever.
                    cleanBlocks = 0
                    setPrime(maxOf(minPrime(), primeMs - 50))
                }
                Log.d(TAG, "block in=${part.audio.size} out=${out.size} fed=${processor.outputSamples} recv=$received fifo=${fifo.size} prime=${primeSamples} lat=${lastLatencyMs}ms infer=${"%.1f".format(lastInferMs)}ms")
                if (out.isNotEmpty()) synchronized(outLock) { fifo.write(out) }
            } catch (t: Throwable) {
                Log.w(TAG, "conversion failed, restarting: ${t.message}")
                status = "error: ${t.message}"
                runCatching { processor.reset() }
                synchronized(inLock) { received = 0 }
                synchronized(outLock) { fifo.clear(); primed = false; delivered = 0; outDropped = 0 }
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
            // ring holds a second or two of stale capture. Keeping it does not
            // just add that much delay once: the engine consumes input at the
            // same rate it emits output, so a backlog never drains — it stays
            // in the stream for the whole call. Start the new processor on an
            // empty ring and let it fill from live audio instead.
            inputBase = received
            received = 0
        }
        synchronized(outLock) { fifo.clear(); primed = false; delivered = 0; outDropped = 0 }
        engine = built
        Log.i(TAG, "engine for $key: ${built.javaClass.simpleName} @${built.outputRate} Hz -> $rate Hz")
        return built
    }
}
