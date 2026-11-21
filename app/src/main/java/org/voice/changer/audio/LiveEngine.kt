package org.voice.changer.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import org.voice.changer.engine.EngineParams
import org.voice.changer.engine.LiveProcessor
import org.voice.changer.engine.PcmSource
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Microphone -> rc.0 -> speaker loop.
 *
 * Capture and inference run on separate threads: inference for a 120 ms chunk
 * takes most of that time on CPU, so a single thread would let the mic buffer
 * overflow while the model runs. The producer only appends to a ring; the
 * consumer snapshots the window the processor asks for and then works outside
 * the lock.
 */
class LiveEngine(private val processor: LiveProcessor) {
    private val sampleRateIn = 16_000

    /** Output rate of the loaded backend; the AudioTrack must match it. */
    val outputRate: Int get() = processor.outputRate

    private val sampleRateOut = processor.outputRate
    private val ring = FloatArray(sampleRateIn * 4)
    private val lock = Object()

    @Volatile private var running = false
    private var received = 0L
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var producer: Thread? = null
    private var consumer: Thread? = null
    private var failure: Throwable? = null
    @Volatile private var pendingParams: EngineParams? = null

    /** Input gate on the microphone path; the threshold follows [setParams]. */
    private val gate = NoiseGate(sampleRateIn)
    @Volatile private var gateDb = -50f
    private val gateBuf = FloatArray(320)

    /** Optional tap on the synthesized stream (debug capture). */
    @Volatile var onAudio: ((FloatArray) -> Unit)? = null

    /**
     * Numbers the UI shows while the loop runs. [latencyMs] is measured, not
     * estimated: the ring holds exactly the input that has been captured but
     * not converted yet, so the gap between capture and handoff is
     * `(received - outputSamples) / 16 kHz`, and the speaker adds its own
     * buffer on top.
     */
    data class Stats(val rtf: Float, val inferMs: Double, val latencyMs: Int)

    @Volatile var onStats: ((Stats) -> Unit)? = null

    /** Last ~40 ms of converted audio, for the oscilloscope. */
    @Volatile var onScope: ((FloatArray) -> Unit)? = null

    /** False while the UI has the meters hidden: nothing is copied or drawn. */
    @Volatile var wantScope = false
    private val scopeRing = FloatArray(1024)
    private var scopeAt = 0L
    private var statsAt = 0L
    private var outFrames = 0L
    private var rtfEma = 0f
    private var inferEma = 0.0

    /** Applied between chunks on the inference thread, so speaker swaps never race a running graph. */
    fun setParams(params: EngineParams) {
        pendingParams = params
        gateDb = params.noiseGateDb
    }

    fun start(onLevel: (Float) -> Unit, onError: (Throwable) -> Unit) {
        if (running) return
        processor.reset()
        running = true
        received = 0
        outFrames = 0
        rtfEma = 0f
        inferEma = 0.0
        failure = null

        val minRec = AudioRecord.getMinBufferSize(
            sampleRateIn,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRateIn,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minRec, sampleRateIn / 10 * 2),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            running = false
            rec.release()
            onError(IllegalStateException("The microphone could not be opened"))
            return
        }
        record = rec

        val minOut = AudioTrack.getMinBufferSize(
            sampleRateOut,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val out = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRateOut)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(max(minOut, sampleRateOut / 5 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = out
        rec.startRecording()
        out.play()

        producer = Thread { readLoop(rec, onLevel, onError) }.also {
            it.name = "beatrice-mic"
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
        consumer = Thread { processLoop(out, onError) }.also {
            it.name = "beatrice-infer"
            it.priority = Thread.NORM_PRIORITY + 2
            it.start()
        }
    }

    /**
     * Debug entry point: drives the ring from a 16 kHz file at real-time pace
     * instead of the microphone, so the consumer scheduling can be exercised
     * without an acoustic loop.
     */
    fun startFromFile(pcm: FloatArray, onLevel: (Float) -> Unit, onError: (Throwable) -> Unit) {
        if (running) return
        processor.reset()
        running = true
        received = 0
        outFrames = 0
        rtfEma = 0f
        inferEma = 0.0
        val minOut = AudioTrack.getMinBufferSize(
            sampleRateOut,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val out = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRateOut)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(max(minOut, sampleRateOut / 5 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = out
        out.play()
        producer = Thread { feedLoop(pcm, onLevel) }.also {
            it.name = "beatrice-feed"
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
        consumer = Thread { processLoop(out, onError) }.also {
            it.name = "beatrice-infer"
            it.priority = Thread.NORM_PRIORITY + 2
            it.start()
        }
    }

    private fun feedLoop(source: FloatArray, onLevel: (Float) -> Unit) {
        // Gate a copy: the caller keeps the clip and may play it again.
        val pcm = source.copyOf()
        gate.setThresholdDb(gateDb)
        gate.process(pcm)
        val step = 320
        val t0 = SystemClock.elapsedRealtime()
        var i = 0
        var levelCounter = 0
        while (running && i < pcm.size) {
            val n = min(step, pcm.size - i)
            var sum = 0.0
            synchronized(lock) {
                for (k in 0 until n) {
                    val v = pcm[i + k]
                    ring[((received + k) % ring.size).toInt()] = v
                    sum += v.toDouble() * v
                }
                received += n
                lock.notifyAll()
            }
            if (++levelCounter >= 4) {
                levelCounter = 0
                onLevel(sqrt(sum / n).toFloat().coerceIn(0f, 1f))
            }
            i += n
            val target = t0 + i * 1000L / sampleRateIn
            val now = SystemClock.elapsedRealtime()
            if (target > now) Thread.sleep(target - now)
        }
        while (running) Thread.sleep(20)
    }

    private fun readLoop(rec: AudioRecord, onLevel: (Float) -> Unit, onError: (Throwable) -> Unit) {
        val buf = ShortArray(320)
        var levelCounter = 0
        try {
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) {
                    if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) {
                        throw IllegalStateException("Microphone read failed ($n)")
                    }
                    continue
                }
                for (i in 0 until n) gateBuf[i] = buf[i] / 32768f
                gate.setThresholdDb(gateDb)
                gate.process(gateBuf, n)
                var sum = 0.0
                synchronized(lock) {
                    for (i in 0 until n) {
                        val v = gateBuf[i]
                        ring[((received + i) % ring.size).toInt()] = v
                        sum += v.toDouble() * v
                    }
                    received += n
                    lock.notifyAll()
                }
                if (++levelCounter >= 4) {
                    levelCounter = 0
                    onLevel(sqrt(sum / n).toFloat().coerceIn(0f, 1f))
                }
            }
        } catch (t: Throwable) {
            onError(t)
        }
    }

    private fun processLoop(out: AudioTrack, onError: (Throwable) -> Unit) {
        try {
            while (true) {
                pendingParams?.let { p ->
                    pendingParams = null
                    processor.setParams(p)
                }
                val start: Int
                val end: Int
                val snapshot: FloatArray
                synchronized(lock) {
                    while (running && received < processor.requiredSamples) lock.wait(20)
                    if (!running) return
                    val need = processor.requiredSamples
                    if (received < need) continue
                    if (received - processor.outputSamples > ring.size * 3L / 4) {
                        throw IllegalStateException("Inference fell behind the microphone; use a larger chunk")
                    }
                    start = max(0, (processor.outputSamples - 560).toInt())
                    end = need
                    snapshot = FloatArray(end - start)
                    for (i in start until end) {
                        snapshot[i - start] = ring[(i % ring.size + ring.size) % ring.size]
                    }
                }
                val source = PcmSource { i ->
                    if (i < start || i >= end) 0f else snapshot[i - start]
                }
                val t0 = SystemClock.elapsedRealtimeNanos()
                val part = processor.next(source)
                val inferMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0
                if (part.audio.isNotEmpty()) {
                    onAudio?.invoke(part.audio)
                    var off = 0
                    while (off < part.audio.size) {
                        val wrote = out.write(part.audio, off, part.audio.size - off, AudioTrack.WRITE_BLOCKING)
                        if (wrote <= 0) break
                        off += wrote
                    }
                    outFrames += part.audio.size
                }
                publish(part.audio, inferMs)
            }
        } catch (t: Throwable) {
            if (running) onError(t)
        }
    }

    /**
     * Feeds the meters at 5 Hz and the oscilloscope at 20 Hz. Both are optional:
     * with the meters hidden nothing is copied, so the loop is exactly what it
     * was before they existed.
     */
    private fun publish(audio: FloatArray, inferMs: Double) {
        val now = SystemClock.elapsedRealtime()
        if (audio.isNotEmpty() && wantScope && now - scopeAt >= 50) {
            scopeAt = now
            val keep = min(audio.size, scopeRing.size)
            var at = 0
            // Newest samples win when a chunk is longer than the window.
            for (i in audio.size - keep until audio.size) scopeRing[at++] = audio[i]
            while (at < scopeRing.size) scopeRing[at++] = 0f
            onScope?.invoke(scopeRing.copyOf())
        }
        if (now - statsAt < 200) return
        statsAt = now
        val audioMs = audio.size * 1000.0 / sampleRateOut
        if (audioMs <= 0.0) return
        val blockRtf = (inferMs / audioMs).toFloat()
        rtfEma = if (rtfEma == 0f) blockRtf else rtfEma * 0.8f + blockRtf * 0.2f
        inferEma = if (inferEma == 0.0) inferMs else inferEma * 0.8 + inferMs * 0.2
        val backlog = (received - processor.outputSamples).coerceAtLeast(0)
        // Written but not played yet: the speaker's own share of the delay.
        val unplayed = (outFrames - (track?.playbackHeadPosition ?: 0)).coerceAtLeast(0)
        val speakerMs = (unplayed * 1000 / sampleRateOut).toInt()
        onStats?.invoke(Stats(rtfEma, inferEma, (backlog * 1000 / sampleRateIn).toInt() + speakerMs))
    }

    fun stop() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        runCatching { record?.stop() }
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        producer?.let { runCatching { it.join(400) } }
        consumer?.let { runCatching { it.join(1200) } }
        runCatching { record?.release() }
        runCatching { track?.release() }
        record = null
        track = null
        producer = null
        consumer = null
    }
}
