package org.voice.changer

import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import org.voice.changer.audio.AudioIO
import org.voice.changer.audio.LiveEngine
import org.voice.changer.engine.EngineHub
import org.voice.changer.engine.EngineParams
import org.voice.changer.engine.LiveProcessor
import org.voice.changer.engine.ModelLoader
import org.voice.changer.engine.Rc0Processor
import org.voice.changer.engine.rvc.RvcJni
import org.voice.changer.engine.rvc.RvcStore
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Debug-only harness, reachable from `adb shell am start`:
 *
 *   # rc.0 file conversion
 *   am start -n org.voice.changer.tmp/org.voice.changer.DebugActivity \
 *       --es input /data/data/.../files/test/in.wav --es output /data/data/.../files/out.wav \
 *       --ei speaker 0 --ei chunk 40 --ef pitch 0 --ef formant 0 --ei vq 0
 *
 *   # RVC file conversion (NPU)
 *   am start ... --es backend rvc --es voice mygf_zbhy \
 *       --es input /data/data/.../files/test/in.wav --es output .../rvc.wav
 *
 *   # live loop (plays `play` out of the speaker while the microphone runs)
 *   am start ... --es mode live --es play .../in.wav --es output .../live.wav \
 *       --ei seconds 8 --ei chunk 12 --es backend rvc --es voice mygf_zbhy
 */
class DebugActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply { text = "Beatrice debug\nstarting…"; textSize = 14f }
        setContentView(status)
        // These runs last tens of seconds; a screen-off would hand the process
        // to the low-memory killer in the middle of the measurement.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        fun show(s: String) {
            Log.i(TAG, s)
            runCatching { File(filesDir, "debug_status.txt").writeText(s + "\n") }
            runOnUiThread { status.text = s }
        }

        val mode = intent.getStringExtra("mode") ?: "file"
        val backend = intent.getStringExtra("backend") ?: "rc0"
        val chunk = intent.getIntExtra("chunk", 40)
        val speaker = intent.getIntExtra("speaker", 0)
        val pitch = intent.getFloatExtra("pitch", 0f)
        val formant = intent.getFloatExtra("formant", 0f)
        val vq = intent.getIntExtra("vq", 0)
        val output = intent.getStringExtra("output")

        Thread {
            try {
                if (backend == "qnn") {
                    val libDir = applicationInfo.nativeLibraryDir
                    show("loading librvc_qnn…")
                    check(RvcJni.load()) { "System.loadLibrary(rvc_qnn) failed" }
                    val t = SystemClock.elapsedRealtime()
                    val err = RvcJni.nativeInit(libDir, libDir)
                    if (err.isNotEmpty()) error("nativeInit: $err")
                    val created = RvcJni.pipelineCreate()
                    if (created.isNotEmpty()) error("pipelineCreate: $created")
                    RvcJni.pipelineDestroy()
                    RvcJni.shutdown()
                    show("QNN OK in ${SystemClock.elapsedRealtime() - t} ms")
                    return@Thread
                }
                val t0 = SystemClock.elapsedRealtime()
                val params = EngineParams(
                    speaker = speaker,
                    pitchShift = pitch.toDouble(),
                    formantShift = formant.toDouble(),
                    vqNeighbors = vq,
                )
                val engine: LiveProcessor = if (backend == "rvc") {
                    val store = RvcStore(filesDir)
                    val wanted = intent.getStringExtra("voice")
                    val voice = store.voices().firstOrNull { it.name == wanted }
                        ?: store.voices().firstOrNull()
                        ?: error("no RVC voice installed (put packs under files/rvc)")
                    show("staging ${voice.name}…")
                    val info = EngineHub.ensureRvc(store, voice, applicationInfo.nativeLibraryDir, params)
                    show("RVC ${info.name} @${info.outputRate} Hz ready in ${SystemClock.elapsedRealtime() - t0} ms")
                    EngineHub.processor ?: error("no processor")
                } else {
                    val model = ModelLoader.load(File(filesDir, "model"))
                    show("model ${model.name} voices=${model.voices.size} in ${SystemClock.elapsedRealtime() - t0} ms")
                    Rc0Processor.create(
                        ai.onnxruntime.OrtEnvironment.getEnvironment(),
                        model,
                        chunkFrames = chunk, attnPositions = 64, params = params,
                    )
                }
                show("engine ready in ${SystemClock.elapsedRealtime() - t0} ms")
                if (mode == "live") runLive(engine, output, ::show)
                else if (mode == "hook") runHook(engine, ::show)
                else runFile(engine, ::show)
                if (backend == "rvc") EngineHub.reset() else engine.close()
            } catch (t: Throwable) {
                Log.e(TAG, "FAILED", t)
                show("FAILED: ${t.message}")
            }
        }.start()
    }

    private fun runFile(engine: LiveProcessor, show: (String) -> Unit) {
        val input = intent.getStringExtra("input") ?: error("need --es input")
        val output = intent.getStringExtra("output") ?: error("need --es output")
        val decoded = AudioIO.decodeMono(input)
        val pcm = AudioIO.resample(decoded.pcm, decoded.sampleRate, 16_000)
        show("input ${decoded.pcm.size} @${decoded.sampleRate} -> ${pcm.size} @16k")

        val rate = engine.outputRate
        val t1 = SystemClock.elapsedRealtime()
        val out = engine.convert(pcm)
        val ms = SystemClock.elapsedRealtime() - t1
        val rtf = ms.toDouble() / (out.size * 1000.0 / rate)
        FileOutputStream(output).use { it.write(AudioIO.encodeWav(out, rate)) }
        show(
            "OK ${out.size} samples (${"%.2f".format(out.size.toDouble() / rate)} s @$rate) in $ms ms " +
                "RTF ${"%.3f".format(rtf)} rms=${"%.4f".format(rms(out))} peak=${"%.4f".format(peak(out))}",
        )
        if (rate == 40_000) {
            val payload = org.voice.changer.engine.rvc.RvcStats.parse(RvcJni.pipelineStats())
            if (payload != null) show("RTF ${"%.3f".format(payload.rtf)} blocks=${payload.blocks} ms=${payload.msTotal}")
        }
    }

    private fun runLive(engine: LiveProcessor, output: String?, show: (String) -> Unit) {
        val playPath = intent.getStringExtra("play")
        val seconds = intent.getIntExtra("seconds", 8)
        val playing = AtomicBoolean(true)
        var player: AudioTrack? = null
        val rate = engine.outputRate

        val playerThread = if (playPath != null) Thread {
            try {
                val decoded = AudioIO.decodeMono(playPath)
                val pcm = if (decoded.sampleRate == rate) decoded.pcm
                else AudioIO.resample(decoded.pcm, decoded.sampleRate, rate)
                val minBuf = AudioTrack.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT,
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
                            .setSampleRate(rate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(max(minBuf, rate / 5 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                player = t
                t.play()
                while (playing.get()) {
                    var off = 0
                    while (off < pcm.size && playing.get()) {
                        val n = t.write(pcm, off, minOf(4096, pcm.size - off), AudioTrack.WRITE_BLOCKING)
                        if (n <= 0) break
                        off += n
                    }
                }
                runCatching { t.stop() }
                runCatching { t.release() }
            } catch (t: Throwable) {
                Log.e(TAG, "player failed", t)
            }
        }.also { it.start() } else null

        val chunks = Collections.synchronizedList(ArrayList<FloatArray>())
        var maxLevel = 0f
        val live = LiveEngine(engine)
        live.onAudio = { chunks.add(it) }
        live.start(
            onLevel = { l -> if (l > maxLevel) maxLevel = l },
            onError = { t -> Log.e(TAG, "live error", t) },
        )
        show("live for ${seconds}s (mic level so far $maxLevel)")
        Thread.sleep(seconds * 1000L)
        live.stop()
        playing.set(false)
        playerThread?.let { runCatching { it.join(1500) } }
        player?.let { runCatching { it.release() } }

        val total = chunks.sumOf { it.size }
        val out = FloatArray(total)
        var off = 0
        for (c in chunks) {
            System.arraycopy(c, 0, out, off, c.size)
            off += c.size
        }
        if (output != null && out.isNotEmpty()) {
            FileOutputStream(output).use { it.write(AudioIO.encodeWav(out, rate)) }
        }
        show(
            "LIVE OK ${out.size} samples (${"%.2f".format(out.size.toDouble() / rate)} s @$rate) " +
                "rms=${"%.4f".format(rms(out))} peak=${"%.4f".format(peak(out))} micLevel=$maxLevel",
        )
    }

    /**
     * Exercises the whole hook path without Xposed: feeds a clip to
     * [org.voice.changer.hook.HookBridge.processCall] in real time, exactly
     * like a hooked AudioRecord at [clientRate], and compares the returned
     * audio with the direct conversion of the same clip.
     */
    private fun runHook(engine: LiveProcessor, show: (String) -> Unit) {
        val input = intent.getStringExtra("input") ?: error("need --es input")
        val output = intent.getStringExtra("output")
        val clientRate = intent.getIntExtra("clientRate", 48_000)
        val chunk = intent.getIntExtra("frames", 960)
        val decoded = AudioIO.decodeMono(input)
        val client = if (decoded.sampleRate == clientRate) decoded.pcm else
            AudioIO.resample(decoded.pcm, decoded.sampleRate, clientRate)
        show("hook client ${client.size} frames @$clientRate Hz, buffer=$chunk")

        val prefs = getSharedPreferences("beatrice", MODE_PRIVATE)
        val wasEnabled = org.voice.changer.hook.HookSettings.enabled(this)
        val oldBackend = prefs.getString("backend", null)
        val oldVoice = prefs.getString("rvcVoice", null)
        prefs.edit()
            .putString("backend", if (engine is org.voice.changer.engine.rvc.RvcProcessor) "RVC" else "BEATRICE")
            .putString("rvcVoice", intent.getStringExtra("voice"))
            .apply()
        org.voice.changer.hook.HookSettings.setEnabled(this, true)
        org.voice.changer.hook.HookBridge.attach(applicationContext)
        // Production loads the engine when the hook is switched on, so the
        // first hooked buffer does not have to pay for it.
        if (EngineHub.processor == null) {
            show("preloading engine for the hook…")
            EngineHub.ensureDefault(this, EngineParams())
        }

        val collected = ArrayList<FloatArray>()
        var off = 0
        val started = SystemClock.elapsedRealtime()
        val startedNanos = System.nanoTime()
        var calls = 0

        // Wall-clock latency: when a converted buffer comes back, the samples
        // in it correspond one-to-one to input samples starting at [delivered].
        // Compare "now" with the moment that input chunk was handed over.
        val sendAt = ArrayList<LongArray>()   // (input offset, elapsedRealtime)
        val latencies = ArrayList<Long>()
        var delivered = 0
        var cursor = 0

        fun sentAt(index: Int): Long {
            while (cursor + 1 < sendAt.size && sendAt[cursor + 1][0] <= index) cursor++
            val e = sendAt.getOrNull(cursor) ?: return -1
            return if (e[0] <= index) e[1] else -1
        }

        fun send(samples: FloatArray, offset: Int) {
            sendAt.add(longArrayOf(offset.toLong(), SystemClock.elapsedRealtime()))
            val b = Bundle().apply {
                putString("key", "debug|$clientRate")
                putString("pkg", "debug")
                putInt("rate", clientRate)
                putInt("channels", 1)
                putFloatArray("pcm", samples)
            }
            val reply = org.voice.changer.hook.HookBridge.processCall(b)
            reply.getFloatArray("pcm")?.let { part ->
                // Sample 0 of the converted stream is the capture sample the
                // session started from, not the first chunk we ever sent.
                val base = org.voice.changer.hook.HookBridge.lastBaseSamples * clientRate / 16_000
                val t = sentAt((base + delivered).toInt())
                if (t > 0) latencies.add(SystemClock.elapsedRealtime() - t)
                collected.add(part)
                delivered += part.size
            }
            calls++
        }

        while (off < client.size) {
            val n = minOf(chunk, client.size - off)
            send(client.copyOfRange(off, off + n), off)
            off += n
            // The real AudioRecord is clocked by hardware, so the harness must
            // not drift: sleep most of the gap, then spin the last millisecond.
            val due = startedNanos + off * 1_000_000_000L / clientRate
            while (true) {
                val left = due - System.nanoTime()
                if (left <= 0) break
                if (left > 2_000_000) Thread.sleep(left / 1_000_000 - 1) else Thread.onSpinWait()
            }
        }
        // Flush the backend: the stream only emits a block once it has enough
        // input, so push a second of silence.
        val silence = FloatArray(chunk)
        for (i in 0 until clientRate / chunk) {
            send(silence, off)
            off += chunk
            Thread.sleep(chunk * 1000L / clientRate)
        }

        val total = collected.sumOf { it.size }
        val out = FloatArray(total)
        var o = 0
        for (c in collected) {
            System.arraycopy(c, 0, out, o, c.size)
            o += c.size
        }
        // The session drives the same stateful processor; stop it before
        // converting the reference clip or the two would interleave.
        org.voice.changer.hook.HookBridge.detach()
        Thread.sleep(300)
        output?.let { path ->
            if (total > 0) FileOutputStream(path).use { it.write(AudioIO.encodeWav(out, clientRate)) }
        }
        show(
            "hook returned ${out.size} samples (${"%.2f".format(out.size.toDouble() / clientRate)} s) " +
                "in ${SystemClock.elapsedRealtime() - started} ms, calls=$calls " +
                "base=${org.voice.changer.hook.HookBridge.lastBaseSamples} samples",
        )
        if (latencies.isNotEmpty()) {
            val s = latencies.sorted()
            val tail = latencies.subList(latencies.size * 2 / 3, latencies.size).sorted()
            show(
                "hook latency: first=${s.first()} ms, p50=${s[s.size / 2]} ms, " +
                    "p95=${s[(s.size * 95) / 100]} ms, max=${s.last()} ms (n=${s.size}) " +
                    "| steady p50=${tail[tail.size / 2]} ms",
            )
        }
        if (engine is org.voice.changer.engine.rvc.RvcProcessor) {
            android.util.Log.i("BeatriceDbg", "stats: " + RvcJni.pipelineStats().take(400))
        }
        if (total > 0) {
            show("hook rms=${"%.4f".format(rms(out))} peak=${"%.4f".format(peak(out))}")
        if (engine is org.voice.changer.engine.rvc.RvcProcessor) {
            val stats = org.voice.changer.engine.rvc.RvcStats.parse(RvcJni.pipelineStats())
            if (stats != null) show("native RTF ${"%.3f".format(stats.rtf)} blocks=${stats.blocks} ms=${stats.msTotal} ${stats.breakdown}")
        }
            val ref = engine.convert(AudioIO.resample(client, clientRate, 16_000))
            val refAt = AudioIO.resample(ref, engine.outputRate, clientRate)
            val a = envelope(out, clientRate)
            val b = envelope(refAt, clientRate)
            // The hook path adds latency (engine start + priming), so find the
            // lag with the best match instead of assuming they line up.
            var best = 0.0
            var bestLag = 0
            for (lag in 0 until 4 * 50) {
                val n = minOf(a.size, b.size - lag)
                if (n < 20) break
                var num = 0.0
                var da = 0.0
                var db = 0.0
                for (i in 0 until n) {
                    num += a[i] * b[i + lag]
                    da += a[i] * a[i]
                    db += b[i + lag] * b[i + lag]
                }
                val corr = if (da > 0 && db > 0) num / sqrt(da * db) else 0.0
                if (corr > best) {
                    best = corr
                    bestLag = lag
                }
            }
            show("hook vs direct: envelope corr=${"%.3f".format(best)} at lag ${bestLag * 20} ms")
        }
        org.voice.changer.hook.HookSettings.setEnabled(this, wasEnabled)
        prefs.edit().putString("backend", oldBackend).putString("rvcVoice", oldVoice).apply()
    }

    /** 20 ms RMS envelope; enough to compare two versions of the same speech. */
    private fun envelope(x: FloatArray, rate: Int): DoubleArray {
        val hop = rate / 50
        if (hop <= 0) return DoubleArray(0)
        val out = DoubleArray(x.size / hop)
        for (i in out.indices) {
            var sum = 0.0
            val base = i * hop
            for (j in 0 until hop) {
                val v = x[base + j].toDouble()
                sum += v * v
            }
            out[i] = sqrt(sum / hop)
        }
        return out
    }

    private fun rms(x: FloatArray): Double {
        if (x.isEmpty()) return 0.0
        var sum = 0.0
        for (v in x) sum += v.toDouble() * v
        return sqrt(sum / x.size)
    }

    private fun peak(x: FloatArray): Float {
        var p = 0f
        for (v in x) {
            val a = if (v < 0) -v else v
            if (a > p) p = a
        }
        return p
    }

    companion object {
        private const val TAG = "BeatriceDbg"
    }
}
