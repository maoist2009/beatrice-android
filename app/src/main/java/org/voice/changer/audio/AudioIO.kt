package org.voice.changer.audio

import android.content.ContentValues
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

data class DecodedAudio(val pcm: FloatArray, val sampleRate: Int, val channels: Int)

object AudioIO {
    private const val MAX_SECONDS = 60

    /** Decode any MediaExtractor-supported stream to mono float PCM at its native rate. */
    fun decodeMono(context: Context, uri: Uri): DecodedAudio {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        return decodeWith(extractor)
    }

    fun decodeMono(path: String): DecodedAudio {
        val extractor = MediaExtractor()
        extractor.setDataSource(path)
        return decodeWith(extractor)
    }

    private fun decodeWith(extractor: MediaExtractor): DecodedAudio {
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                track = i
                format = f
                break
            }
        }
        require(track >= 0 && format != null) { "No audio track in that file" }
        extractor.selectTrack(track)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val codec = MediaCodec.createDecoderByType(mime)
        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT

        val chunks = ArrayList<FloatArray>(64)
        var total = 0L
        codec.configure(format, null, null, 0)
        codec.start()
        try {
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var timeouts = 0
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else AudioFormat.ENCODING_PCM_16BIT
                } else if (outIdx >= 0) {
                    if (info.size > 0) {
                        val buf = codec.getOutputBuffer(outIdx)!!
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        buf.order(ByteOrder.LITTLE_ENDIAN)
                        val frames = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            info.size / 4
                        } else {
                            info.size / 2
                        }
                        val out = FloatArray(frames)
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            buf.asFloatBuffer().get(out)
                        } else {
                            for (i in 0 until frames) out[i] = buf.short / 32768f
                        }
                        chunks += out
                        total += frames
                        require(total / channels / sampleRate <= MAX_SECONDS) {
                            "Use clips of $MAX_SECONDS seconds or less"
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (inputDone && ++timeouts > 200) outputDone = true
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }

        val frames = (total / channels).toInt()
        require(frames > 0) { "The file decoded to no audio" }
        val mono = FloatArray(frames)
        var pos = 0
        for (chunk in chunks) {
            val n = chunk.size
            var i = 0
            while (i < n) {
                var acc = 0f
                for (c in 0 until channels) acc += chunk[i + c]
                mono[pos++] = acc / channels
                i += channels
                if (pos >= frames) break
            }
            if (pos >= frames) break
        }
        return DecodedAudio(mono.copyOf(pos), sampleRate, channels)
    }

    /**
     * Windowed-sinc resampler. The model boundary is 16 kHz, and plain linear
     * interpolation folds 48 kHz content back into the speech band, which shows
     * up as a lisp in the converted voice.
     */
    fun resample(pcm: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return pcm.copyOf()
        val ratio = to.toDouble() / from
        val outLen = maxOf(1, (pcm.size * ratio).roundToInt())
        val out = FloatArray(outLen)
        val cutoff = min(1.0, ratio)
        val half = 12
        val taps = half * 2
        val step = 1.0 / ratio
        for (n in 0 until outLen) {
            val t = n * step
            val base = floor(t).toInt()
            var acc = 0.0
            var norm = 0.0
            for (k in -half + 1..half) {
                val idx = base + k
                if (idx < 0 || idx >= pcm.size) continue
                val x = t - idx
                val w = if (abs(x) < 1e-9) 1.0 else sin(PI * x * cutoff) / (PI * x * cutoff)
                val a = (x / half + 1.0) * 0.5
                val win = if (a <= 0.0 || a >= 1.0) 0.0 else 0.5 - 0.5 * cos(2 * PI * a)
                val c = w * win
                acc += pcm[idx] * c
                norm += c
            }
            out[n] = if (norm != 0.0) (acc / norm).toFloat() else 0f
        }
        return out
    }

    fun encodeWav(samples: FloatArray, sampleRate: Int): ByteArray {
        val n = samples.size
        val out = ByteArray(44 + n * 2)
        fun putStr(off: Int, s: String) { for (i in s.indices) out[off + i] = s[i].code.toByte() }
        fun putInt(off: Int, v: Int) {
            out[off] = (v and 0xff).toByte()
            out[off + 1] = ((v ushr 8) and 0xff).toByte()
            out[off + 2] = ((v ushr 16) and 0xff).toByte()
            out[off + 3] = ((v ushr 24) and 0xff).toByte()
        }
        fun putShort(off: Int, v: Int) {
            out[off] = (v and 0xff).toByte()
            out[off + 1] = ((v ushr 8) and 0xff).toByte()
        }
        putStr(0, "RIFF"); putInt(4, 36 + n * 2); putStr(8, "WAVE")
        putStr(12, "fmt "); putInt(16, 16); putShort(20, 1); putShort(22, 1)
        putInt(24, sampleRate); putInt(28, sampleRate * 2); putShort(32, 2); putShort(34, 16)
        putStr(36, "data"); putInt(40, n * 2)
        for (i in 0 until n) {
            val v = samples[i]
            require(v.isFinite()) { "Non-finite output sample $i" }
            val s = v.coerceIn(-1f, 1f)
            val q = if (s < 0) (s * 0x8000).toInt() else (s * 0x7fff).toInt()
            putShort(44 + i * 2, q)
        }
        return out
    }

    fun saveWav(context: Context, samples: FloatArray, sampleRate: Int, displayName: String): Uri {
        val bytes = encodeWav(samples, sampleRate)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/Beatrice")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create the output file")
        resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Could not open the output file")
        values.clear()
        values.put(MediaStore.Audio.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}

/** Blocking float playback; the caller owns the coroutine scope. */
class FloatPlayer {
    private var track: AudioTrack? = null
    private var job: Job? = null

    fun play(samples: FloatArray, sampleRate: Int, scope: kotlinx.coroutines.CoroutineScope, onDone: () -> Unit) {
        stop()
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf, sampleRate))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        t.play()
        job = scope.launch(Dispatchers.Default) {
            try {
                var off = 0
                val block = 4096
                while (off < samples.size && isActive) {
                    val n = min(block, samples.size - off)
                    val wrote = t.write(samples, off, n, AudioTrack.WRITE_BLOCKING)
                    if (wrote <= 0) break
                    off += wrote
                }
            } finally {
                runCatching { t.stop() }
                runCatching { t.release() }
                withContext(Dispatchers.Main) { onDone() }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        track?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.release() }
        }
        track = null
    }
}
