package com.beatrice.lite.hook

/**
 * Fixed-capacity float ring used on both sides of the hook bridge.
 *
 * Producers never block: when the consumer falls behind the oldest samples are
 * dropped, which keeps the latency bounded instead of letting the queue grow
 * for the rest of the call.
 */
class FloatFifo(private val capacity: Int) {
    private val data = FloatArray(capacity)
    private var head = 0
    private var count = 0

    val size: Int get() = count
    val free: Int get() = capacity - count

    fun clear() {
        head = 0
        count = 0
    }

    fun write(src: FloatArray) {
        var i = 0
        if (src.size > capacity) {
            i = src.size - capacity
            clear()
        } else if (src.size > free) {
            dropOldest(src.size - free)
        }
        var tail = (head + count) % capacity
        while (i < src.size) {
            data[tail] = src[i++]
            tail = (tail + 1) % capacity
            count++
        }
    }

    /** Removes and returns [n] samples, or null when fewer are queued. */
    fun read(n: Int): FloatArray? {
        if (n <= 0) return FloatArray(0)
        if (count < n) return null
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = data[(head + i) % capacity]
        head = (head + n) % capacity
        count -= n
        return out
    }

    /** Up to [n] samples, whatever is queued. */
    fun take(n: Int): FloatArray {
        val k = minOf(n, count)
        if (k == 0) return FloatArray(0)
        return read(k)!!
    }

    fun dropOldest(n: Int) {
        val k = minOf(n, count)
        head = (head + k) % capacity
        count -= k
    }
}
