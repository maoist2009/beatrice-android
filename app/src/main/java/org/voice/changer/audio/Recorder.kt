package org.voice.changer.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs
import kotlin.math.max

/**
 * Microphone capture for the convert page.
 *
 * Same format as the live path (16 kHz mono, voice-recognition source), so a
 * recording and a picked file differ only in where the samples came from: both
 * are fed to the same streaming pipeline afterwards.
 */
class Recorder(private val maxSeconds: Int = MAX_SECONDS) {
    companion object {
        const val SAMPLE_RATE = 16_000
        const val MAX_SECONDS = 120
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private val chunks = ArrayList<FloatArray>()
    private var total = 0

    @Volatile private var running = false

    /** Whether capture is still going (false once [maxSeconds] is reached). */
    val isRunning: Boolean get() = running

    /** Peak of the last read, 0..1, for the level meter. */
    @Volatile var level: Float = 0f
        private set

    /** Seconds captured so far. */
    @Volatile var seconds: Double = 0.0
        private set

    fun start(): Boolean {
        if (running) return false
        chunks.clear()
        total = 0
        seconds = 0.0
        level = 0f
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBuf, SAMPLE_RATE / 5 * 2),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }
        record = rec
        running = true
        rec.startRecording()
        thread = Thread(::loop, "convert-mic").also { it.start() }
        return true
    }

    private fun loop() {
        val rec = record ?: return
        val buf = ShortArray(SAMPLE_RATE / 10)
        val limit = SAMPLE_RATE * maxSeconds
        while (running) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) continue
            val out = FloatArray(n)
            var peak = 0
            for (i in 0 until n) {
                val s = buf[i].toInt()
                val a = abs(s)
                if (a > peak) peak = a
                out[i] = s / 32768f
            }
            level = peak / 32768f
            chunks += out
            total += n
            seconds = total.toDouble() / SAMPLE_RATE
            if (total >= limit) running = false
        }
    }

    /** Stops capture and returns everything recorded, 16 kHz mono. */
    fun stop(): FloatArray {
        running = false
        thread?.let { runCatching { it.join(600) } }
        thread = null
        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        record = null
        level = 0f
        val out = FloatArray(total)
        var off = 0
        for (c in chunks) {
            c.copyInto(out, off)
            off += c.size
        }
        chunks.clear()
        total = 0
        return out
    }
}
