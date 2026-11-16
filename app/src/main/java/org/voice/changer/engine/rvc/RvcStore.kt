package org.voice.changer.engine.rvc

import android.content.ContentResolver
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * On-device RVC voice storage.
 *
 * Layout under filesDir/rvc:
 * ```
 * shared/            hubert_c0_s23.bin, hubert_r_2063_s23.bin,
 *                    hubert_enc_140_s23.bin, rmvpe_e2e_s23.bin, conv0_gn.f32
 * voices/<name>/     encflow.bin, flowreverse.bin, decnsf_ext.bin, voice.json
 * models/            the flat directory the native pipeline reads (staged)
 * ```
 * Voice folders are staged into `models/` with symlinks (the graph set is
 * ~250 MB; copying on every switch would be wasteful). The fallback is a
 * plain copy on filesystems that refuse links.
 */
class RvcStore(private val filesDir: File) {

    companion object {
        private const val TAG = "rvc_store"

        /** Shared components, in the native pipeline's canonical names. */
        val SHARED = listOf(
            "hubert_c0_s23.bin",
            "hubert_r_2063_s23.bin",
            "hubert_enc_140_s23.bin",
            "rmvpe_e2e_s23.bin",
            "conv0_gn.f32",
        )

        /** Voice-specific roles: source prefix -> canonical staged name. */
        val VOICE_ROLES = linkedMapOf(
            "encflow" to "encflow.bin",
            "flowreverse" to "flowreverse.bin",
            "decnsf" to "decnsf_ext.bin",
        )

        /** canonical name -> filename stem prefix, for import matching. */
        private val SHARED_ROLES = linkedMapOf(
            "hubert_c0_s23.bin" to "hubert_c0_s23",
            "hubert_r_2063_s23.bin" to "hubert_r_2063_s23",
            "hubert_enc_140_s23.bin" to "hubert_enc_140_s23",
            "rmvpe_e2e_s23.bin" to "rmvpe_e2e_s23",
            "conv0_gn.f32" to "conv0_gn",
        )

        private val VOICE_PREFIXES = linkedMapOf(
            "encflow.bin" to "encflow",
            "flowreverse.bin" to "flowreverse",
            "decnsf_ext.bin" to "decnsf",
        )

        /** Sanity floors so a truncated copy is rejected before staging. */
        private val MIN_SIZE = mapOf(
            "hubert_c0_s23.bin" to 50_000L,
            "hubert_r_2063_s23.bin" to 5_000_000L,
            "hubert_enc_140_s23.bin" to 50_000_000L,
            "rmvpe_e2e_s23.bin" to 50_000_000L,
            "conv0_gn.f32" to 4_096L,
        )
    }

    data class Voice(val name: String, val dir: File, val hasIndex: Boolean)

    val root: File get() = File(filesDir, "rvc")
    val sharedDir: File get() = File(root, "shared")
    val voicesDir: File get() = File(root, "voices")
    val stageDir: File get() = File(root, "models")

    fun sharedReady(): Boolean = SHARED.all { File(sharedDir, it).isFile }

    fun missingShared(): List<String> = SHARED.filterNot { File(sharedDir, it).isFile }

    /** Voices whose own graphs and manifest are present. */
    fun voices(): List<Voice> =
        voicesDir.listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name }
            ?.mapNotNull { dir ->
                val complete = VOICE_ROLES.values.all { File(dir, it).isFile } &&
                    File(dir, "voice.json").isFile
                if (complete) Voice(dir.name, dir, File(dir, "retrieval.index").isFile) else null
            }
            ?: emptyList()

    /** True when a complete engine (shared pack + at least one voice) is installed. */
    fun isInstalled(): Boolean = sharedReady() && voices().isNotEmpty()

    /**
     * Links [voice] into the flat stage directory the pipeline reads.
     * Returns null on success, else the reason.
     */
    fun stage(voice: Voice): String? {
        return try {
            if (!stageDir.isDirectory && !stageDir.mkdirs()) return "cannot create ${stageDir.name}"
            for (name in SHARED + VOICE_ROLES.values + "voice.json" + "retrieval.index") {
                File(stageDir, name).delete()
            }
            val sharedErr = linkAll(sharedDir, SHARED)
            if (sharedErr != null) return sharedErr
            val voiceErr = linkAll(voice.dir, VOICE_ROLES.values.toList())
            if (voiceErr != null) return voiceErr
            link(File(voice.dir, "voice.json"), File(stageDir, "voice.json"))
                ?.let { return "cannot stage voice.json: $it" }
            if (voice.hasIndex) {
                link(File(voice.dir, "retrieval.index"), File(stageDir, "retrieval.index"))
            }
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
    }

    private fun linkAll(src: File, names: List<String>): String? {
        for (name in names) {
            val f = File(src, name)
            if (!f.isFile) return "$name is missing"
            val err = link(f, File(stageDir, name))
            if (err != null) return err
        }
        return null
    }

    /** Symlink, falling back to a copy. Returns null on success. */
    private fun link(src: File, dst: File): String? {
        dst.delete()
        try {
            Files.createSymbolicLink(dst.toPath(), src.toPath())
            return null
        } catch (e: Exception) {
            Log.w(TAG, "symlink ${dst.name} failed, copying: ${e.message}")
        }
        return try {
            src.inputStream().use { i ->
                dst.outputStream().use { o -> i.copyTo(o, 256 * 1024) }
            }
            null
        } catch (e: Exception) {
            "copy ${src.name}: ${e.message}"
        }
    }

    // ---------------------------------------------------------------- import

    /** One voice found in a picked tree. [tag] is null when the folder holds a single voice. */
    data class VoicePack(val dir: DocumentFile, val tag: String?)

    /** What a picked folder (and up to two levels of children) turned out to hold. */
    data class Found(
        val beatrice: DocumentFile? = null,
        val shared: DocumentFile? = null,
        val voices: List<VoicePack> = emptyList(),
    ) {
        val empty: Boolean get() = beatrice == null && shared == null && voices.isEmpty()
    }

    /**
     * Inspects a picked tree: the folder itself, then up to two levels of
     * subfolders, so picking a single voice folder, a multi-voice
     * `synthesizer/` dump, or a pack root all work.
     */
    fun scan(tree: DocumentFile, resolver: ContentResolver): Found {
        var beatrice: DocumentFile? = null
        var shared: DocumentFile? = null
        val voices = ArrayList<VoicePack>()

        fun addVoice(dir: DocumentFile) {
            val manifests = manifestFiles(dir)
            if (manifests.isEmpty()) return
            val tags = manifests.mapNotNull { manifestTag(it, resolver) }.distinct()
            if (tags.size <= 1) {
                voices += VoicePack(dir, tags.firstOrNull())
            } else {
                for (tag in tags) voices += VoicePack(dir, tag)
            }
        }

        fun classify(dir: DocumentFile) {
            val names = dir.listFiles().mapNotNull { if (it.isFile) it.name?.lowercase() else null }
            fun startsWith(p: String) = names.any { it.startsWith(p) }
            fun endsWith(p: String) = names.any { it.endsWith(p) }

            if (beatrice == null && endsWith(".toml") &&
                startsWith("phone_extractor") && startsWith("waveform_generator")
            ) {
                beatrice = dir
                return
            }
            val isShared = startsWith("hubert_c0") && startsWith("hubert_r") &&
                startsWith("hubert_enc") && startsWith("rmvpe") && startsWith("conv0_gn")
            if (isShared && shared == null) shared = dir
            val hasVoiceGraphs = startsWith("encflow") && startsWith("flowreverse") &&
                startsWith("decnsf")
            if (hasVoiceGraphs) addVoice(dir)
        }

        classify(tree)
        if (beatrice == null && shared == null && voices.isEmpty()) {
            for (child in tree.listFiles().filter { it.isDirectory }) classify(child)
            if (beatrice == null && shared == null && voices.isEmpty()) {
                for (child in tree.listFiles().filter { it.isDirectory }) {
                    for (grand in child.listFiles().filter { it.isDirectory }) classify(grand)
                }
            }
        }
        return Found(beatrice, shared, voices)
    }

    /**
     * Copies a shared pack and/or voice packs found under [tree].
     * Returns a human-readable summary; throws [IOException] on failure.
     */
    fun import(tree: DocumentFile, resolver: ContentResolver, onProgress: (String) -> Unit): String {
        val found = scan(tree, resolver)
        if (found.shared == null && found.voices.isEmpty()) {
            throw IOException("No RVC graphs found in that folder")
        }
        val notes = ArrayList<String>()

        found.shared?.let { dir ->
            onProgress("shared components…")
            sharedDir.mkdirs()
            copyRoles(dir, resolver, sharedDir, SHARED_ROLES, tag = null, onProgress)
            val missing = missingShared()
            if (missing.isNotEmpty()) throw IOException("Shared pack incomplete: ${missing.joinToString()}")
            notes += "shared components"
        }

        for (pack in found.voices) {
            val name = pack.tag ?: pack.dir.name?.takeIf { it.isNotBlank() } ?: "voice"
            onProgress("voice $name…")
            val target = File(voicesDir, name)
            if (target.isDirectory) target.deleteRecursively()
            target.mkdirs()
            copyRoles(pack.dir, resolver, target, VOICE_PREFIXES, pack.tag, onProgress)
            val manifest = manifestFor(pack.dir, pack.tag, resolver)
                ?: throw IOException("$name has no voice manifest (*_manifest.json / voice.json)")
            copyFile(manifest, resolver, File(target, "voice.json"), onProgress)
            val index = pack.dir.listFiles()
                .firstOrNull { it.isFile && it.name?.endsWith(".index") == true }
            index?.let { copyFile(it, resolver, File(target, "retrieval.index"), onProgress) }
            notes += name
        }

        if (found.voices.isNotEmpty() && !sharedReady()) {
            throw IOException(
                "Voices copied, but the shared pack (hubert/rmvpe/conv0_gn) is still missing",
            )
        }
        return notes.joinToString(", ")
    }

    /** Files that could be a voice manifest, in preference order. */
    private fun manifestFiles(dir: DocumentFile): List<DocumentFile> {
        val files = dir.listFiles().filter { it.isFile }
        fun name(f: DocumentFile) = f.name?.lowercase().orEmpty()
        val order = listOf(
            files.filter { name(it) == "voice.json" },
            files.filter { name(it).endsWith("_manifest.json") },
            files.filter { name(it).endsWith("_gate.json") },
        )
        return order.flatten().distinctBy { it.uri }
    }

    private fun manifestTag(f: DocumentFile, resolver: ContentResolver): String? = runCatching {
        val text = resolver.openInputStream(f.uri)?.use { it.readBytes().decodeToString() } ?: return null
        org.json.JSONObject(text).optString("tag").takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun manifestFor(dir: DocumentFile, tag: String?, resolver: ContentResolver): DocumentFile? {
        val manifests = manifestFiles(dir)
        if (tag == null) return manifests.firstOrNull()
        return manifests.firstOrNull { manifestTag(it, resolver) == tag }
            ?: manifests.firstOrNull { it.name?.contains(tag, ignoreCase = true) == true }
            ?: manifests.firstOrNull()
    }

    /**
     * Copies files whose stem starts with the prefix of a role into [target]
     * under the role's canonical name. When [tag] is set, only files that name
     * that voice are considered, so one flat folder can hold several voices.
     */
    private fun copyRoles(
        dir: DocumentFile,
        resolver: ContentResolver,
        target: File,
        roles: Map<String, String>,
        tag: String?,
        onProgress: (String) -> Unit,
    ) {
        val files = dir.listFiles().filter { it.isFile }
        for ((canonical, prefix) in roles) {
            val matches = files.filter { f ->
                val stem = f.name?.substringBeforeLast('.')?.lowercase() ?: return@filter false
                stem.startsWith(prefix) && (tag == null || stem.contains("_" + tag.lowercase()))
            }
            val pick = matches.firstOrNull()
                ?: files.firstOrNull { f ->
                    val stem = f.name?.substringBeforeLast('.')?.lowercase() ?: return@firstOrNull false
                    tag == null && stem.startsWith(prefix)
                }
                ?: throw IOException("${dir.name}: no $prefix graph" + (tag?.let { " for $it" } ?: ""))
            copyFile(pick, resolver, File(target, canonical), onProgress)
        }
        MIN_SIZE.forEach { (name, min) ->
            val f = File(target, name)
            if (f.isFile && f.length() < min) {
                throw IOException("${f.name} looks truncated (${f.length()} B)")
            }
        }
    }

    private fun copyFile(
        src: DocumentFile,
        resolver: ContentResolver,
        dst: File,
        onProgress: (String) -> Unit,
    ) {
        dst.parentFile?.mkdirs()
        val size = src.length()
        resolver.openInputStream(src.uri)?.use { input ->
            dst.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
        } ?: throw IOException("cannot read ${src.name}")
        if (size > 0 && dst.length() != size) {
            throw IOException("${dst.name}: copied ${dst.length()} of $size bytes")
        }
        onProgress(dst.name)
    }

    fun deleteAll() {
        root.deleteRecursively()
    }
}
