package com.beatrice.lite.engine.rvc

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
) {
    val blocks: Long get() = nBlocks

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
            )
        } catch (e: Exception) {
            null
        }
    }
}
