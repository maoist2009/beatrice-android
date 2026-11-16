package org.voice.changer.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

private fun product(dims: IntArray): Int = dims.fold(1) { a, b -> a * b }
private fun LongArray.toDims(): LongArray = this

/**
 * An ORT session whose recurrent state lives in Kotlin arrays.
 *
 * Each graph declares its conv/attention history as extra inputs and mirrors
 * them as `*_next` outputs, so a chunk's result depends only on the previous
 * chunk. Keeping the state outside ORT avoids IO-binding complexity and lets
 * one session serve both file conversion and live microphone use.
 */
class StatefulSession(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val states: List<StateSpec>,
    val inputs: List<GraphBuilder.IoSpec>,
    val outputs: List<GraphBuilder.IoSpec>,
) {
    private val state = HashMap<String, FloatArray>()

    init {
        for (s in states) state[s.input] = FloatArray(product(s.dims)) { s.init }
    }

    fun reset() {
        for (s in states) state[s.input]!!.fill(s.init)
    }

    fun run(feeds: Map<String, FloatArray>): Map<String, FloatArray> {
        val tensors = ArrayList<OnnxTensor>(inputs.size)
        val feedMap = HashMap<String, OnnxTensor>(inputs.size)
        try {
            for (spec in inputs) {
                val arr = feeds[spec.name] ?: state[spec.name]
                    ?: error("missing input ${spec.name}")
                val dims = spec.dims.map { it.toLong() }.toLongArray()
                require(product(spec.dims) == arr.size) {
                    "input ${spec.name}: ${arr.size} values for dims ${spec.dims.toList()}"
                }
                val t = OnnxTensor.createTensor(env, FloatBuffer.wrap(arr), dims)
                tensors += t
                feedMap[spec.name] = t
            }
            val result = session.run(feedMap)
            val out = HashMap<String, FloatArray>(outputs.size)
            try {
                for (spec in outputs) {
                    val value = result.get(spec.name).orElse(null)
                        ?: error("graph did not return ${spec.name}")
                    val t = value as? OnnxTensor ?: error("output ${spec.name} is not a tensor")
                    val n = product(spec.dims)
                    val arr = FloatArray(n)
                    val fb: FloatBuffer = t.floatBuffer
                    fb.get(arr)
                    out[spec.name] = arr
                }
            } finally {
                result.close()
            }
            for (s in states) {
                val next = out[s.output] ?: continue
                System.arraycopy(next, 0, state[s.input]!!, 0, next.size)
            }
            return out
        } finally {
            for (t in tensors) t.close()
            feedMap.clear()
        }
    }

    fun close() {
        session.close()
    }
}
