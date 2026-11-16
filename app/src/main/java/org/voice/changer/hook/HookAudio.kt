package org.voice.changer.hook

import android.media.AudioFormat
import android.media.AudioRecord
import de.robv.android.xposed.XC_MethodHook
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Rewrites `AudioRecord.read` results in place.
 *
 * Every overload (byte[], short[], float[], ByteBuffer) is handled, and the
 * channel layout is respected: the buffer is downmixed to mono for the engine
 * and the result is copied back to every channel, which is what a voice call
 * path expects.
 */
object HookAudio {
    fun intercept(param: XC_MethodHook.MethodHookParam, pkg: String, process: String) {
        val read = param.result as? Int ?: return
        if (read <= 0) return
        val record = param.thisObject as? AudioRecord ?: return
        val args = param.args ?: return
        if (args.isEmpty()) return

        val rate = runCatching { record.sampleRate }.getOrDefault(0)
        val channels = runCatching { record.channelCount }.getOrDefault(0)
        if (rate <= 0 || channels <= 0) return
        val encoding = runCatching { record.audioFormat }.getOrDefault(AudioFormat.ENCODING_PCM_16BIT)

        val mono = readMono(args[0], args, read, channels, encoding) ?: return
        if (mono.isEmpty()) return

        val tap = HookTaps.get(pkg, process, rate, channels)
        val out = tap.exchange(mono)
        if (out.size != mono.size) return
        writeMono(args[0], args, read, channels, encoding, out)
    }

    // ------------------------------------------------------------ reading

    private fun readMono(
        target: Any?,
        args: Array<Any?>,
        read: Int,
        channels: Int,
        encoding: Int,
    ): FloatArray? = when (target) {
        is ShortArray -> shortsToMono(target, offset(args, 1), read, channels)
        is FloatArray -> floatsToMono(target, offset(args, 1), read, channels)
        is ByteArray -> bytesToMono(target, offset(args, 1), read, channels, bytesPerSample(encoding))
        is ByteBuffer -> bufferToMono(target, read, channels, bytesPerSample(encoding))
        else -> null
    }

    private fun writeMono(
        target: Any?,
        args: Array<Any?>,
        read: Int,
        channels: Int,
        encoding: Int,
        mono: FloatArray,
    ) {
        when (target) {
            is ShortArray -> writeShorts(target, offset(args, 1), mono, channels)
            is FloatArray -> writeFloats(target, offset(args, 1), mono, channels)
            is ByteArray -> writeBytes(target, offset(args, 1), mono, channels, bytesPerSample(encoding))
            is ByteBuffer -> writeBuffer(target, read, mono, channels, bytesPerSample(encoding))
        }
    }

    private fun offset(args: Array<Any?>, index: Int): Int = (args.getOrNull(index) as? Int) ?: 0

    private fun bytesPerSample(encoding: Int): Int =
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2

    private fun shortsToMono(array: ShortArray, off: Int, read: Int, channels: Int): FloatArray? {
        val frames = read / channels
        if (frames <= 0 || off < 0 || off + read > array.size) return null
        val mono = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            val base = off + i * channels
            for (c in 0 until channels) sum += array[base + c] / 32768f
            mono[i] = sum / channels
        }
        return mono
    }

    private fun floatsToMono(array: FloatArray, off: Int, read: Int, channels: Int): FloatArray? {
        val frames = read / channels
        if (frames <= 0 || off < 0 || off + read > array.size) return null
        val mono = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            val base = off + i * channels
            for (c in 0 until channels) sum += array[base + c]
            mono[i] = sum / channels
        }
        return mono
    }

    private fun bytesToMono(
        array: ByteArray,
        off: Int,
        read: Int,
        channels: Int,
        bytesPer: Int,
    ): FloatArray? {
        val frameBytes = bytesPer * channels
        val frames = read / frameBytes
        if (frames <= 0 || off < 0 || off + frames * frameBytes > array.size) return null
        val mono = FloatArray(frames)
        var p = off
        for (i in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += if (bytesPer == 4) {
                    val bits = (array[p].toInt() and 0xFF) or
                        ((array[p + 1].toInt() and 0xFF) shl 8) or
                        ((array[p + 2].toInt() and 0xFF) shl 16) or
                        ((array[p + 3].toInt() and 0xFF) shl 24)
                    Float.fromBits(bits)
                } else {
                    val v = (array[p].toInt() and 0xFF) or (array[p + 1].toInt() shl 8)
                    v.toShort() / 32768f
                }
                p += bytesPer
            }
            mono[i] = sum / channels
        }
        return mono
    }

    private fun bufferToMono(
        buffer: ByteBuffer,
        read: Int,
        channels: Int,
        bytesPer: Int,
    ): FloatArray? {
        val frameBytes = bytesPer * channels
        val frames = read / frameBytes
        if (frames <= 0) return null
        val start = buffer.position() - read
        if (start < 0) return null
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(start)
        val mono = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += if (bytesPer == 4) view.getFloat() else view.getShort() / 32768f
            }
            mono[i] = sum / channels
        }
        return mono
    }

    // ------------------------------------------------------------ writing

    private fun writeShorts(array: ShortArray, off: Int, mono: FloatArray, channels: Int) {
        if (off < 0 || off + mono.size * channels > array.size) return
        for (i in mono.indices) {
            val v = (mono[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            val base = off + i * channels
            for (c in 0 until channels) array[base + c] = v
        }
    }

    private fun writeFloats(array: FloatArray, off: Int, mono: FloatArray, channels: Int) {
        if (off < 0 || off + mono.size * channels > array.size) return
        for (i in mono.indices) {
            val v = mono[i].coerceIn(-1f, 1f)
            val base = off + i * channels
            for (c in 0 until channels) array[base + c] = v
        }
    }

    private fun writeBytes(
        array: ByteArray,
        off: Int,
        mono: FloatArray,
        channels: Int,
        bytesPer: Int,
    ) {
        if (off < 0 || off + mono.size * channels * bytesPer > array.size) return
        var p = off
        for (v in mono) {
            val clamped = v.coerceIn(-1f, 1f)
            for (c in 0 until channels) {
                if (bytesPer == 4) {
                    val bits = clamped.toBits()
                    array[p] = (bits and 0xFF).toByte()
                    array[p + 1] = ((bits ushr 8) and 0xFF).toByte()
                    array[p + 2] = ((bits ushr 16) and 0xFF).toByte()
                    array[p + 3] = ((bits ushr 24) and 0xFF).toByte()
                } else {
                    val s = (clamped * 32767f).toInt()
                    array[p] = (s and 0xFF).toByte()
                    array[p + 1] = ((s ushr 8) and 0xFF).toByte()
                }
                p += bytesPer
            }
        }
    }

    private fun writeBuffer(
        buffer: ByteBuffer,
        read: Int,
        mono: FloatArray,
        channels: Int,
        bytesPer: Int,
    ) {
        val start = buffer.position() - read
        if (start < 0) return
        val view = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        view.position(start)
        for (v in mono) {
            val clamped = v.coerceIn(-1f, 1f)
            for (c in 0 until channels) {
                if (bytesPer == 4) view.putFloat(clamped) else view.putShort((clamped * 32767f).toInt().toShort())
            }
        }
    }
}
