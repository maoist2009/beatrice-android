package org.voice.changer.engine.rvc

import android.util.Log

/**
 * Native interface to librvc_qnn.so (RVC v2 on the Hexagon NPU).
 *
 * Ported from dreamlite's `RvcJni`; the entry points are bound to this package
 * (see `rvc_jni.cpp`). Every call that can fail returns "" on success and an
 * error string otherwise; `execGraph` returns null on failure with the reason
 * available from [lastError]. Heavy work runs on the calling thread.
 */
object RvcJni {

    /** Initialise the QNN HTP backend. libDir/skelDir are the app's own native
     * library dir, which carries libQnnHtp.so and libQnnHtpV79Skel.so. */
    external fun nativeInit(libDir: String, skelDir: String): String

    external fun loadGraph(tag: String, binPath: String): String

    /** Load a .dlc container, composing its graphs onto a fresh context. */
    external fun loadGraphDlc(tag: String, dlcPath: String): String

    external fun freeGraph(tag: String): String

    /** Graph metadata as JSON: {"tag","inputs":[{"name","dtype","dims"}],...}. */
    external fun graphInfo(tag: String): String

    external fun shutdown()

    external fun lastError(): String

    /**
     * Execute a loaded graph. [data] entries are FloatArray for float32
     * tensors and IntArray for int32 tensors, matched to graph inputs by
     * [names]. Outputs come back in graph declaration order as FloatArray.
     */
    external fun execGraph(
        tag: String,
        names: Array<String>,
        data: Array<Any>
    ): Array<FloatArray>?

    /* ---------- full pipeline ---------- */

    external fun pipelineCreate(): String

    /** Loads the seven QNN contexts and the conv0 GroupNorm affine.
     * [voiceJson] is the voice's manifest; missing keys fall back to the
     * mygf_zbhy ctx-280 defaults. */
    external fun pipelineInit(modelsDir: String, libDir: String,
                              voiceJson: String): String

    external fun pipelineConvert(input: FloatArray): FloatArray?

    /** Timing breakdown of the last convert as JSON. */
    external fun pipelineStats(): String

    external fun pipelineSetPitch(semitones: Float): String

    external fun pipelineSetRmsMixRate(rate: Float): String

    external fun pipelineSetProtect(protect: Float): String

    external fun pipelineSetFilterRadius(radius: Int): String

    external fun pipelineDestroy()

    /** Per-graph graphExecute wall-time stats as a JSON array. */
    external fun graphTimings(): String

    /* ---------- streaming pipeline ---------- */

    external fun pipelineStreamStart(): String

    /** Appends 16 kHz mono audio and returns every block that just became
     * computable (10 000 samples @40 kHz per 250 ms of input). */
    external fun pipelineStreamFeed(input: FloatArray): FloatArray

    /** Zero-pads the look-ahead window and flushes the trailing block. */
    external fun pipelineStreamEnd(): FloatArray

    external fun pipelineLastError(): String

    @Volatile private var loaded = false

    @Synchronized
    fun load(): Boolean {
        if (loaded) return true
        loaded = try {
            System.loadLibrary("rvc_qnn")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "loadLibrary rvc_qnn failed", e)
            false
        }
        return loaded
    }

    private const val TAG = "rvc_jni"
}
