package org.voice.changer.engine

/**
 * User-facing controls, shared by the two backends.
 *
 * Beatrice rc.0 reads the pitch/format/vq/speaker fields; RVC v2 reads
 * [pitchShift], [protect], [rmsMixRate], [filterRadius] and the gains. Each
 * engine simply ignores the fields that do not apply to it.
 */
data class EngineParams(
    // ---- shared ----
    val pitchShift: Double = 0.0,
    val inputGain: Double = 1.0,
    val outputGain: Double = 1.0,

    // ---- Beatrice rc.0 ----
    val averageSourcePitch: Double = 52.0,
    val intonationIntensity: Double = 1.0,
    val pitchCorrection: Double = 0.0,
    val pitchCorrectionType: Int = 0,
    val formantShift: Double = 0.0,
    val speaker: Int = 0,
    val vqNeighbors: Int = 0,
    val minMidi: Double = 33.125,
    val maxMidi: Double = 80.875,

    // ---- RVC v2 (NPU) ----
    val speakerId: Int = 0,
    val rmsMixRate: Double = 0.25,
    /** 0..0.5; 0.5 is the official client's "off". */
    val protect: Double = 0.5,
    /** 1..7 median window over the f0 track; 1 is a no-op. */
    val filterRadius: Int = 3,
    /**
     * Block-tail f0 policy, trading latency for the accuracy of the last f0
     * frames of each 250 ms block:
     *  - 0 quality: wait for the RMVPE window that puts those frames at the
     *    window centre (the longest look-ahead)
     *  - 1 low latency: use the earliest window that still contains them and
     *    zero-pad its tail instead of waiting for it (~100 ms less)
     * Everything outside the block tail is identical between the two.
     */
    val f0Tail: Int = 0,
)
