package org.voice.changer.engine.rvc

/**
 * Timing breakdown reported by the native pipeline (`pipelineStats` JSON).
 * Used by the debug harness and by the UI when it wants a live RTF readout.
 */
data class RvcStats(
    val msTotal: Float,
    val rtf: Float,
    val nBlocks: Long,
    val msIndex: Float,
    val msF0: Float,
    val msEnc: Float,
    val msSynth: Float,
    val msHighpass: Float = 0f,
    val msC0: Float = 0f,
    val msGn: Float = 0f,
    val msSola: Float = 0f,
) {
    val blocks: Long get() = nBlocks

    /** One line with every stage, in milliseconds. */
    val breakdown: String
        get() = "hp=${"%.0f".format(msHighpass)} f0=${"%.0f".format(msF0)} " +
            "c0=${"%.0f".format(msC0)} gn=${"%.0f".format(msGn)} " +
            "enc=${"%.0f".format(msEnc)} synth=${"%.0f".format(msSynth)} " +
            "sola=${"%.0f".format(msSola)} idx=${"%.0f".format(msIndex)}"

    companion object {
        fun parse(json: String): RvcStats? = try {
            val o = org.json.JSONObject(json)
            RvcStats(
                msTotal = o.optDouble("ms_total", 0.0).toFloat(),
                rtf = o.optDouble("rtf", 1.0).toFloat(),
                nBlocks = o.optLong("n_blocks", 0),
                msIndex = o.optDouble("ms_index", 0.0).toFloat(),
                msF0 = o.optDouble("ms_f0", 0.0).toFloat(),
                msEnc = o.optDouble("ms_enc", 0.0).toFloat(),
                msSynth = o.optDouble("ms_synth", 0.0).toFloat(),
                msHighpass = o.optDouble("ms_highpass", 0.0).toFloat(),
                msC0 = o.optDouble("ms_c0", 0.0).toFloat(),
                msGn = o.optDouble("ms_gn", 0.0).toFloat(),
                msSola = o.optDouble("ms_sola", 0.0).toFloat(),
            )
        } catch (e: Exception) {
            null
        }
    }
}
