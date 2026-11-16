package org.voice.changer.engine

import java.io.File

data class Voice(
    val id: Int,
    val name: String,
    val description: String,
    val averagePitch: Double?,
)

class BeatriceModel(
    val name: String,
    val version: String,
    val description: String,
    val voices: List<Voice>,
    val pitch: PitchWeights,
    val phone: PhoneWeights,
    val wg: WGWeights,
    val speakers: SpeakerEmbeddings,
    val setter: EmbeddingSetter,
)

/** Minimal TOML reader for `beatrice_paraphernalia_*.toml`. */
private fun parseToml(src: String): Map<String, MutableMap<String, Any>> {
    val out = LinkedHashMap<String, MutableMap<String, Any>>()
    var cur = "root"
    out[cur] = LinkedHashMap()
    val lines = src.replace("\r\n", "\n").split("\n")
    var i = 0
    while (i < lines.size) {
        val raw = lines[i]
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) { i++; continue }
        if (line.startsWith("[")) {
            val end = line.indexOf(']')
            if (end > 0) {
                cur = line.substring(1, end).trim()
                out.getOrPut(cur) { LinkedHashMap() }
                i++
                continue
            }
        }
        val eq = line.indexOf('=')
        if (eq < 0) { i++; continue }
        val key = line.substring(0, eq).trim().trim('"')
        var rhs = line.substring(eq + 1).trim()
        val value: Any = when {
            rhs.startsWith("\"\"\"") || rhs.startsWith("'''") -> {
                val q = rhs.substring(0, 3)
                var body = rhs.substring(3)
                val end = body.indexOf(q)
                if (end >= 0) {
                    body = body.substring(0, end)
                } else {
                    val parts = ArrayList<String>()
                    parts += body
                    i++
                    while (i < lines.size) {
                        val idx = lines[i].indexOf(q)
                        if (idx >= 0) { parts += lines[i].substring(0, idx); break }
                        parts += lines[i]
                        i++
                    }
                    body = parts.joinToString("\n")
                    if (body.startsWith("\n")) body = body.substring(1)
                }
                if (q == "\"\"\"") unescape(body) else body
            }
            rhs.startsWith("\"") -> {
                val sb = StringBuilder()
                var j = 1
                while (j < rhs.length && rhs[j] != '"') {
                    if (rhs[j] == '\\' && j + 1 < rhs.length) { sb.append(rhs[j]).append(rhs[j + 1]); j += 2 }
                    else sb.append(rhs[j++])
                }
                unescape(sb.toString())
            }
            rhs.startsWith("'") -> {
                val e = rhs.indexOf('\'', 1)
                if (e > 0) rhs.substring(1, e) else ""
            }
            else -> {
                rhs = rhs.replace(Regex("\\s+#.*$"), "")
                when {
                    rhs == "true" -> true
                    rhs == "false" -> false
                    Regex("^[+-]?(\\d[\\d_]*)(\\.\\d+)?([eE][+-]?\\d+)?$").matches(rhs) ->
                        rhs.replace("_", "").toDouble()
                    else -> rhs
                }
            }
        }
        out.getOrPut(cur) { LinkedHashMap() }[key] = value
        i++
    }
    return out
}

private fun unescape(s: String): String {
    if (!s.contains('\\')) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val ch = s[i]
        if (ch == '\\' && i + 1 < s.length) {
            when (val n = s[i + 1]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                '"' -> sb.append('"')
                '\\' -> sb.append('\\')
                'u' -> {
                    if (i + 5 < s.length) {
                        val code = s.substring(i + 2, i + 6).toIntOrNull(16)
                        if (code != null) { sb.append(code.toChar()); i += 4 }
                    }
                }
                else -> sb.append(n)
            }
            i += 2
        } else {
            sb.append(ch)
            i++
        }
    }
    return sb.toString()
}

object ModelLoader {
    const val SUPPORTED_VERSION = "2.0.0-rc.0"

    private fun find(dir: File, name: String): File? {
        val direct = File(dir, name)
        if (direct.isFile) return direct
        return dir.listFiles()?.firstOrNull { it.isFile && it.name.equals(name, ignoreCase = true) }
    }

    fun findToml(dir: File): File? =
        dir.listFiles()?.firstOrNull { it.isFile && it.name.endsWith(".toml", ignoreCase = true) }

    fun isModelDir(dir: File): Boolean =
        findToml(dir) != null && find(dir, "phone_extractor.bin") != null &&
            find(dir, "pitch_estimator.bin") != null && find(dir, "waveform_generator.bin") != null &&
            find(dir, "speaker_embeddings.bin") != null && find(dir, "embedding_setter.bin") != null

    fun load(dir: File): BeatriceModel {
        val tomlFile = findToml(dir) ?: error("No .toml found in ${dir.name}")
        val toml = parseToml(tomlFile.readText())
        val model = toml["model"] ?: error("The .toml has no [model] section")
        val version = model["version"] as? String ?: error("[model].version is missing")
        require(version == SUPPORTED_VERSION) {
            "Unsupported paraphernalia version $version (this build runs rc.0 weights only)"
        }
        val name = model["name"] as? String ?: "Beatrice"
        val description = model["description"] as? String ?: ""

        val voices = ArrayList<Voice>()
        for ((section, table) in toml) {
            val m = Regex("^voice\\.(\\d+)$").find(section) ?: continue
            voices += Voice(
                id = m.groupValues[1].toInt(),
                name = table["name"] as? String ?: "Voice ${m.groupValues[1]}",
                description = table["description"] as? String ?: "",
                averagePitch = table["average_pitch"] as? Double,
            )
        }

        fun bytes(name: String): ByteArray = (find(dir, name) ?: error("Missing $name")).readBytes()
        fun halves(bytes: ByteArray, what: String, expected: Int): FloatArray {
            require(bytes.size % 2 == 0) { "$what has an odd byte count" }
            val got = bytes.size / 2
            require(got == expected) { "$what: $got float16 values, rc.0 layout needs $expected" }
            return f16ToF32(bytes)
        }

        val pitch = extractPitch(halves(bytes("pitch_estimator.bin"), "pitch_estimator.bin", pitchExpectedHalves()))
        val phone = extractPhone(halves(bytes("phone_extractor.bin"), "phone_extractor.bin", phoneExpectedHalves()))
        val wg = extractWaveformGenerator(halves(bytes("waveform_generator.bin"), "waveform_generator.bin", wgExpectedHalves()))
        val speakers = parseSpeakerEmbeddings(bytes("speaker_embeddings.bin"))
        val setter = parseEmbeddingSetter(bytes("embedding_setter.bin"))

        return BeatriceModel(
            name = name,
            version = version,
            description = description,
            voices = voices.sortedBy { it.id },
            pitch = pitch,
            phone = phone,
            wg = wg,
            speakers = speakers,
            setter = setter,
        )
    }
}
