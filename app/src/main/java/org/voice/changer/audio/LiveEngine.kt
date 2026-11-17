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
                val part = processor.next(source)
                if (part.audio.isNotEmpty()) {
                    onAudio?.invoke(part.audio)
                    var off = 0
                    while (off < part.audio.size) {
                        val wrote = out.write(part.audio, off, part.audio.size - off, AudioTrack.WRITE_BLOCKING)
                        if (wrote <= 0) break
                        off += wrote
                    }
                }
            }
        } catch (t: Throwable) {
            if (running) onError(t)
        }
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
