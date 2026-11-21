package org.voice.changer

import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.voice.changer.audio.AudioIO
import org.voice.changer.audio.FloatPlayer
import org.voice.changer.audio.NoiseGate
import org.voice.changer.engine.Backend
import org.voice.changer.engine.EngineHub
import org.voice.changer.engine.EngineInfo
import org.voice.changer.engine.EngineParams
import org.voice.changer.engine.LiveProcessor
import org.voice.changer.engine.ModelLoader
import org.voice.changer.engine.rvc.RvcStore
import org.voice.changer.hook.HookBridge
import org.voice.changer.hook.HookSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A launchable app the hook can be pointed at. */
data class AppEntry(val pkg: String, val label: String)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    data class UiState(
        val backend: Backend? = null,
        val modelName: String? = null,
        val modelVersion: String? = null,
        val voices: List<String> = emptyList(),
        val voice: Int = 0,
        val outputRate: Int = 24_000,
        val loadingModel: Boolean = false,
        val importing: Boolean = false,
        val inputName: String? = null,
        val inputSeconds: Double = 0.0,
        val converting: Boolean = false,
        val progress: Float = 0f,
        val resultSeconds: Double = 0.0,
        val playing: Boolean = false,
        val live: Boolean = false,
        val level: Float = 0f,
        val savedName: String? = null,
        val message: String? = null,
        val error: String? = null,
        val pitch: Float = 0f,
        val formant: Float = 0f,
        val intonation: Float = 1f,
        val correction: Float = 0f,
        val vq: Int = 0,
        val output: Float = 1f,
        val rmsMix: Float = 0.25f,
        val protect: Float = 0.5f,
        val filter: Int = 3,
        val f0Tail: Int = 0,
        val indexRate: Float = 0f,
        val nprobe: Int = 0,
        val noiseGate: Float = -50f,
        /** Parallel to [voices]: whether that pack ships a retrieval index. */
        val voiceHasIndex: List<Boolean> = emptyList(),
        /** Labels for the backend that is not resident, read from disk. */
        val rvcName: String? = null,
        val rvcSubtitle: String? = null,
        val beatriceName: String? = null,
        val beatriceSubtitle: String? = null,
        val hookEnabled: Boolean = false,
        val hookAll: Boolean = false,
        val hookTargets: Set<String> = emptySet(),
        val hookActive: Boolean = false,
        val hookedPkg: String? = null,
        val hookFrames: Long = 0L,
        val hookLatencyMs: Int = 0,
        val hookLookaheadMs: Int = 0,
        val hookQueueMs: Int = 0,
        val hookInferMs: Double = 0.0,
        val hasBeatrice: Boolean = false,
        val hasRvc: Boolean = false,
        val apps: List<AppEntry> = emptyList(),
    ) {
        val ready: Boolean get() = modelName != null && !loadingModel
        val busy: Boolean get() = loadingModel || importing || converting
        val isRvc: Boolean get() = backend == Backend.RVC
    }

    private val prefs = app.getSharedPreferences("beatrice", Application.MODE_PRIVATE)
    private val store = RvcStore(app.filesDir)

    private val _state = MutableStateFlow(
        UiState(
            voice = prefs.getInt("voice", 0),
            pitch = prefs.getFloat("pitch", 0f),
            formant = prefs.getFloat("formant", 0f),
            intonation = prefs.getFloat("intonation", 1f),
            correction = prefs.getFloat("correction", 0f),
            vq = prefs.getInt("vq", 0),
            output = prefs.getFloat("output", 1f),
            rmsMix = prefs.getFloat("rmsMix", 0.25f),
            protect = prefs.getFloat("protect", 0.5f),
            filter = prefs.getInt("filter", 3),
            // 1 used to mean the middle setting, which produced exactly the
            // audio of 2 (see EngineParams.f0Tail); fold both onto low latency.
            f0Tail = if (prefs.getInt("f0Tail", 0) >= 1) 1 else 0,
            indexRate = prefs.getFloat("indexRate", 0f),
            nprobe = prefs.getInt("nprobe", 0),
            noiseGate = prefs.getFloat("noiseGate", -50f),
            hookEnabled = HookSettings.enabled(app),
            hookAll = HookSettings.allApps(app),
            hookTargets = HookSettings.targets(app),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val player = FloatPlayer()
    private var input16k: FloatArray? = null
    private var output: FloatArray? = null

    private val beatriceDir: File get() = File(getApplication<Application>().filesDir, "model")

    init {
        if (LiveService.running) {
            _state.value = _state.value.copy(live = true)
            attachLiveCallbacks()
        }
        refreshAvailability()
        if (ModelLoader.isModelDir(beatriceDir) || store.isInstalled()) loadEngine()
        watchHook()
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) { loadApps() }
            _state.value = _state.value.copy(apps = apps)
        }
    }

    // --------------------------------------------------------------- hook

    /** Mirrors the bridge's state into the UI once a second. */
    private fun watchHook() {
        viewModelScope.launch {
            while (true) {
                val pkg = HookBridge.lastPkg
                val stats = HookBridge.stats()
                _state.value = _state.value.copy(
                    hookActive = HookBridge.isActive(),
                    hookedPkg = pkg,
                    hookFrames = pkg?.let { HookBridge.deliveredFor(it) } ?: 0L,
                    hookLatencyMs = stats?.totalMs ?: 0,
                    hookLookaheadMs = stats?.lookaheadMs ?: 0,
                    hookQueueMs = stats?.queueMs ?: 0,
                    hookInferMs = stats?.inferMs ?: 0.0,
                )
                delay(1_000)
            }
        }
    }

    fun setHookEnabled(on: Boolean) {
        val app = getApplication<Application>()
        HookSettings.setEnabled(app, on)
        _state.value = _state.value.copy(hookEnabled = on, error = null)
        try {
            if (on) {
                if (_state.value.live) stopLive()
                // Foreground service keeps the provider and the engine alive
                // while the hooked app is in front.
                LiveService.startHookMode(app)
            } else if (!_state.value.live) {
                runCatching { LiveService.stop(app) }
            }
        } catch (t: Throwable) {
            fail(t)
        }
    }

    fun setHookAll(on: Boolean) {
        HookSettings.setAllApps(getApplication(), on)
        _state.value = _state.value.copy(hookAll = on)
    }

    fun toggleHookTarget(pkg: String) {
        val now = _state.value.hookTargets.toMutableSet()
        if (!now.add(pkg)) now.remove(pkg)
        HookSettings.setTargets(getApplication(), now)
        _state.value = _state.value.copy(hookTargets = now)
    }

    private fun loadApps(): List<AppEntry> {
        val pm = getApplication<Application>().packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
            .addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        val resolved = runCatching {
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }.getOrDefault(emptyList())
        val self = getApplication<Application>().packageName
        return resolved
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .filter { it.pkg != self && !it.pkg.startsWith("org.voice.changer") }
            .distinctBy { it.pkg }
            .sortedBy { it.label.lowercase() }
    }

    private fun attachLiveCallbacks() {
        LiveService.onLevel = { lvl -> _state.value = _state.value.copy(level = lvl) }
        LiveService.onError = { t ->
            viewModelScope.launch {
                _state.value = _state.value.copy(live = false, level = 0f, error = t.message ?: t.toString())
            }
        }
    }

    private fun params(s: UiState): EngineParams = EngineParams(
        pitchShift = s.pitch.toDouble(),
        formantShift = s.formant.toDouble(),
        intonationIntensity = s.intonation.toDouble(),
        pitchCorrection = s.correction.toDouble(),
        speaker = s.voice,
        vqNeighbors = s.vq,
        outputGain = s.output.toDouble(),
        rmsMixRate = s.rmsMix.toDouble(),
        protect = s.protect.toDouble(),
        filterRadius = s.filter,
        f0Tail = s.f0Tail,
        indexRate = s.indexRate,
        nprobe = s.nprobe,
        noiseGateDb = s.noiseGate,
    )

    // ------------------------------------------------------------- import

    fun importModel(uri: Uri) {
        if (_state.value.busy || _state.value.live) return
        _state.value = _state.value.copy(importing = true, error = null, message = "Inspecting the folder…")
        viewModelScope.launch {
            try {
                val summary = withContext(Dispatchers.IO) {
                    val tree = DocumentFile.fromTreeUri(getApplication(), uri)
                        ?: error("The picked folder could not be opened")
                    val found = store.scan(tree, getApplication<Application>().contentResolver)
                    when {
                        found.beatrice != null -> {
                            importBeatrice(found.beatrice)
                            prefs.edit().putString("backend", Backend.BEATRICE.name).apply()
                            "Beatrice model"
                        }
                        found.shared != null || found.voices.isNotEmpty() -> {
                            val notes = store.import(
                                tree,
                                getApplication<Application>().contentResolver,
                            ) { msg ->
                                _state.value = _state.value.copy(message = "Copying $msg…")
                            }
                            prefs.edit().putString("backend", Backend.RVC.name).apply()
                            notes
                        }
                        else -> error(
                            "That folder holds neither a Beatrice model (.toml + .bin) " +
                                "nor an RVC pack (hubert/rmvpe graphs or encflow/flowreverse/decnsf)",
                        )
                    }
                }
                EngineHub.reset()
                refreshAvailability()
                _state.value = _state.value.copy(importing = false, message = "Imported $summary")
                loadEngine(force = true)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _state.value = _state.value.copy(importing = false, message = null)
                fail(t)
            }
        }
    }

    private fun importBeatrice(root: DocumentFile) {
        val target = beatriceDir
        if (target.exists()) target.deleteRecursively()
        if (!target.mkdirs()) error("Could not create the model folder")
        copyTree(root, target)
        if (!ModelLoader.isModelDir(target)) error("The folder is missing the .toml or the .bin files")
    }

    private fun copyTree(dir: DocumentFile, out: File) {
        val resolver = getApplication<Application>().contentResolver
        for (f in dir.listFiles()) {
            val name = f.name ?: continue
            if (f.isDirectory) {
                val sub = File(out, name)
                sub.mkdirs()
                copyTree(f, sub)
            } else {
                resolver.openInputStream(f.uri)?.use { input ->
                    File(out, name).outputStream().use { input.copyTo(it, 1 shl 16) }
                }
            }
        }
    }

    // ------------------------------------------------------------- engine

    private fun loadEngine(force: Boolean = false) {
        val cached = EngineHub.processor
        val cachedInfo = EngineHub.info
        if (!force && cached != null && cachedInfo != null) {
            adopt(cached, cachedInfo)
            return
        }
        _state.value = _state.value.copy(loadingModel = true, error = null, message = "Preparing the engine…")
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.Default) {
                    EngineHub.ensureDefault(getApplication(), params(_state.value))
                }
                val created = EngineHub.processor ?: error("The engine could not be created")
                adopt(created, info)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e("Beatrice", "engine load failed", t)
                _state.value = _state.value.copy(loadingModel = false, message = null)
                fail(t)
            }
        }
    }

    /**
     * Which backends have their files on disk. Both can be installed at once,
     * but only one is resident, so the UI offers a switch instead of making
     * the user re-import a folder to change engine.
     */
    private fun refreshAvailability() {
        val hasBeatrice = ModelLoader.isModelDir(beatriceDir)
        val beatrice = if (hasBeatrice) ModelLoader.describe(beatriceDir) else null
        val hasRvc = runCatching { store.sharedReady() && store.voices().isNotEmpty() }
            .getOrDefault(false)
        val packs = if (hasRvc) runCatching { store.voices() }.getOrDefault(emptyList()) else emptyList()
        // The pack the engine will stage: the last one used, else the first.
        val selected = packs.firstOrNull { it.name == prefs.getString("rvcVoice", null) }
            ?: packs.firstOrNull()
        _state.value = _state.value.copy(
            hasBeatrice = hasBeatrice,
            hasRvc = hasRvc,
            beatriceName = beatrice?.first,
            beatriceSubtitle = beatrice?.let { "${it.second} · on-device CPU" },
            rvcName = selected?.name,
            rvcSubtitle = selected?.let { if (it.hasIndex) "RVC v2 · Hexagon NPU · index" else "RVC v2 · Hexagon NPU" },
        )
    }

    /** Swaps the resident backend; the choice is persisted for the hook path too. */
    fun setBackend(backend: Backend) {
        val s = _state.value
        if (s.backend == backend || s.busy || s.live) return
        if (s.hookEnabled) {
            showError("Turn off “Use in other apps” before switching the engine.")
            return
        }
        if (backend == Backend.BEATRICE && !s.hasBeatrice) return
        if (backend == Backend.RVC && !s.hasRvc) return
        prefs.edit().putString("backend", backend.name).apply()
        EngineHub.reset()
        loadEngine(force = true)
    }

    private fun adopt(processor: LiveProcessor, info: EngineInfo) {
        Log.i("Beatrice", "engine ready: ${info.backend} ${info.name} @${info.outputRate}")
        val packs = if (info.backend == Backend.RVC) store.voices() else emptyList()
        val voices = if (info.backend == Backend.RVC) {
            packs.map { it.name }.ifEmpty { info.voices }
        } else info.voices
        val index = when {
            info.backend == Backend.RVC -> voices.indexOf(info.name).coerceAtLeast(0)
            _state.value.voice in voices.indices -> _state.value.voice
            else -> 0
        }
        if (info.backend == Backend.RVC) {
            prefs.edit().putString("rvcVoice", info.name).apply()
        }
        val isRvc = info.backend == Backend.RVC
        _state.value = _state.value.copy(
            backend = info.backend,
            modelName = info.name,
            modelVersion = info.subtitle,
            rvcName = if (isRvc) info.name else _state.value.rvcName,
            rvcSubtitle = if (isRvc) info.subtitle else _state.value.rvcSubtitle,
            beatriceName = if (isRvc) _state.value.beatriceName else info.name,
            beatriceSubtitle = if (isRvc) _state.value.beatriceSubtitle else info.subtitle,
            voices = voices,
            voiceHasIndex = voices.map { name -> packs.firstOrNull { it.name == name }?.hasIndex == true },
            voice = index,
            outputRate = info.outputRate,
            loadingModel = false,
            message = null,
        )
        applyParams()
    }

    /** Switches voice: a parameter change for rc.0, an engine reload for RVC. */
    fun setVoice(index: Int) {
        val s = _state.value
        if (index !in s.voices.indices || s.busy || s.live) return
        if (s.backend == Backend.RVC) {
            val voices = store.voices()
            val target = voices.firstOrNull { it.name == s.voices[index] } ?: return
            if (target.name == s.modelName) {
                _state.value = s.copy(voice = index)
                return
            }
            _state.value = s.copy(loadingModel = true, voice = index, message = "Loading ${target.name}…")
            viewModelScope.launch {
                try {
                    val info = withContext(Dispatchers.Default) {
                        EngineHub.ensureRvc(
                            store, target,
                            getApplication<Application>().applicationInfo.nativeLibraryDir,
                            params(_state.value),
                        )
                    }
                    val created = EngineHub.processor ?: error("The engine could not be created")
                    adopt(created, info)
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    _state.value = _state.value.copy(loadingModel = false, message = null)
                    fail(t)
                }
            }
        } else {
            _state.value = s.copy(voice = index)
            applyParams()
        }
    }

    // ------------------------------------------------------------- params

    fun setPitch(v: Float) { _state.value = _state.value.copy(pitch = v); applyParams() }
    fun setFormant(v: Float) { _state.value = _state.value.copy(formant = v); applyParams() }
    fun setIntonation(v: Float) { _state.value = _state.value.copy(intonation = v); applyParams() }
    fun setCorrection(v: Float) { _state.value = _state.value.copy(correction = v); applyParams() }
    fun setVq(v: Int) { _state.value = _state.value.copy(vq = v); applyParams() }
    fun setOutput(v: Float) { _state.value = _state.value.copy(output = v); applyParams() }
    fun setRmsMix(v: Float) { _state.value = _state.value.copy(rmsMix = v); applyParams() }
    fun setProtect(v: Float) { _state.value = _state.value.copy(protect = v); applyParams() }
    fun setFilter(v: Int) { _state.value = _state.value.copy(filter = v); applyParams() }
    fun setF0Tail(v: Int) { _state.value = _state.value.copy(f0Tail = v); applyParams() }
    fun setIndexRate(v: Float) { _state.value = _state.value.copy(indexRate = v); applyParams() }
    fun setNprobe(v: Int) { _state.value = _state.value.copy(nprobe = v); applyParams() }
    fun setNoiseGate(v: Float) { _state.value = _state.value.copy(noiseGate = v); applyParams() }

    /**
     * Retrieval is staged with the voice: the index is only read when the
     * pipeline is created, so a rate or probe change needs the voice reloaded.
     * Called when the slider is released rather than on every step.
     */
    fun commitIndexChange() {
        val s = _state.value
        if (s.backend != Backend.RVC || s.busy || s.live) return
        if (s.hookEnabled) {
            showError("Turn off “Use in other apps” before changing the index.")
            return
        }
        EngineHub.reset()
        loadEngine(force = true)
    }

    private fun applyParams() {
        val s = _state.value
        val p = params(s)
        EngineHub.processor?.setParams(p)
        LiveService.updateParams(p)
        prefs.edit()
            .putInt("voice", s.voice)
            .putFloat("pitch", s.pitch)
            .putFloat("formant", s.formant)
            .putFloat("intonation", s.intonation)
            .putFloat("correction", s.correction)
            .putInt("vq", s.vq)
            .putFloat("output", s.output)
            .putFloat("rmsMix", s.rmsMix)
            .putFloat("protect", s.protect)
            .putInt("filter", s.filter)
            .putInt("f0Tail", s.f0Tail)
            .putFloat("indexRate", s.indexRate)
            .putInt("nprobe", s.nprobe)
            .putFloat("noiseGate", s.noiseGate)
            .apply()
    }

    // --------------------------------------------------------------- live

    fun startLive() {
        if (_state.value.busy || _state.value.live || !_state.value.ready) return
        if (EngineHub.processor == null) return
        // Both modes want the same stateful engine; the microphone loop wins
        // and the hook passes raw audio through while it runs.
        if (_state.value.hookEnabled) {
            HookSettings.setEnabled(getApplication(), false)
            _state.value = _state.value.copy(hookEnabled = false)
        }
        player.stop()
        attachLiveCallbacks()
        LiveService.updateParams(params(_state.value))
        _state.value = _state.value.copy(live = true, level = 0f, error = null, message = null, playing = false)
        try {
            LiveService.start(getApplication())
        } catch (t: Throwable) {
            _state.value = _state.value.copy(live = false, error = t.message ?: t.toString())
        }
    }

    fun stopLive() {
        if (!_state.value.live) return
        _state.value = _state.value.copy(live = false, level = 0f)
        runCatching { LiveService.stop(getApplication()) }
    }

    fun showError(text: String) {
        _state.value = _state.value.copy(error = text)
    }

    // ----------------------------------------------------------- offline

    fun pickAudio(uri: Uri, name: String?) {
        if (_state.value.busy || _state.value.live) return
        _state.value = _state.value.copy(message = "Decoding audio…", error = null)
        viewModelScope.launch {
            try {
                val decoded = withContext(Dispatchers.IO) {
                    val audio = AudioIO.decodeMono(getApplication(), uri)
                    val pcm16 = AudioIO.resample(audio.pcm, audio.sampleRate, 16_000)
                    Triple(pcm16, audio.pcm.size.toDouble() / audio.sampleRate, name)
                }
                input16k = decoded.first
                output = null
                _state.value = _state.value.copy(
                    inputName = decoded.third ?: "Clip",
                    inputSeconds = decoded.second,
                    resultSeconds = 0.0,
                    message = null,
                )
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _state.value = _state.value.copy(message = null)
                fail(t)
            }
        }
    }

    fun convert() {
        val pcm = input16k ?: return
        val e = EngineHub.processor ?: return
        if (_state.value.busy || _state.value.live) return
        if (_state.value.hookEnabled) {
            // The hooked stream owns the stateful processor while it is on.
            showError("Turn off “Use in other apps” before converting a file.")
            return
        }
        player.stop()
        val rate = e.outputRate
        _state.value = _state.value.copy(converting = true, progress = 0f, error = null, savedName = null, message = null)
        viewModelScope.launch {
            try {
                val out = withContext(Dispatchers.Default) {
                    val p = params(_state.value)
                    e.setParams(p)
                    val gated = pcm.copyOf()
                    NoiseGate(16_000).apply {
                        setThresholdDb(p.noiseGateDb)
                        process(gated)
                    }
                    e.convert(gated) { done -> _state.value = _state.value.copy(progress = done) }
                }
                Log.i("Beatrice", "converted ${out.size} samples @$rate")
                output = out
                _state.value = _state.value.copy(
                    converting = false,
                    outputRate = rate,
                    resultSeconds = out.size / rate.toDouble(),
                    message = null,
                )
            } catch (c: CancellationException) {
                _state.value = _state.value.copy(converting = false)
                throw c
            } catch (t: Throwable) {
                _state.value = _state.value.copy(converting = false)
                fail(t)
            }
        }
    }

    fun togglePlay() {
        val out = output ?: return
        if (_state.value.playing) {
            player.stop()
            _state.value = _state.value.copy(playing = false)
        } else {
            _state.value = _state.value.copy(playing = true)
            player.play(out, _state.value.outputRate, viewModelScope) {
                _state.value = _state.value.copy(playing = false)
            }
        }
    }

    fun save() {
        val out = output ?: return
        val stamp = System.currentTimeMillis() / 1000
        val prefix = if (_state.value.backend == Backend.RVC) "rvc" else "beatrice"
        val rate = _state.value.outputRate
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    AudioIO.saveWav(getApplication(), out, rate, "${prefix}_$stamp.wav")
                }
                _state.value = _state.value.copy(savedName = "${prefix}_$stamp.wav", message = "Saved to Music/Beatrice")
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                fail(t)
            }
        }
    }

    fun clearMessage() {
        _state.value = _state.value.copy(message = null, error = null, savedName = null)
    }

    private fun fail(t: Throwable) {
        _state.value = _state.value.copy(error = t.message ?: t.toString())
    }

    override fun onCleared() {
        player.stop()
        LiveService.onLevel = null
        LiveService.onError = null
        // The engine stays in EngineHub: the live service or the next activity may still need it.
    }
}
