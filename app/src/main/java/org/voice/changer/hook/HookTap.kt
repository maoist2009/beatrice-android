package org.voice.changer.hook

import java.util.concurrent.ConcurrentHashMap

/**
 * A hooked stream inside the target process.
 *
 * The audio thread only moves samples in and out of two queues; a worker
 * performs the binder round trip. That way the target's capture callback is
 * never blocked by engine startup, inference or a slow binder reply — it just
 * keeps getting raw audio until converted audio is available.
 */
class HookTap(
    private val pkg: String,
    private val process: String,
    private val rate: Int,
    private val channels: Int,
) {
    private val send = FloatFifo(rate) // ~1 s of pending capture
    private val recv = FloatFifo(rate) // ~1 s of converted audio
    private val sendLock = Object()
    private val recvLock = Object()

    @Volatile private var running = true

    @Volatile var lastAt = System.currentTimeMillis()
        private set

    // One engine stream per process: two processes sharing a stream would
    // hear each other's audio.
    private val key = "$pkg#$process|$rate|$channels"

    private val worker = Thread(::loop, "beatrice-tap").also {
        it.isDaemon = true
        it.priority = Thread.NORM_PRIORITY + 1
        it.start()
    }

    /**
     * Queues [mono] and returns the matching converted buffer, or an empty
     * array while priming. Full buffers only: the caller replaces the whole
     * capture buffer or none of it.
     */
    fun exchange(mono: FloatArray): FloatArray {
        lastAt = System.currentTimeMillis()
        synchronized(sendLock) { send.write(mono) }
        return synchronized(recvLock) { recv.read(mono.size) } ?: EMPTY
    }

    fun close() {
        running = false
    }

    private fun loop() {
        while (running) {
            val chunk = synchronized(sendLock) { send.take(MAX_CHUNK) }
            if (chunk.isEmpty()) {
                // 2 ms: the audio thread just handed us a buffer, so a longer
                // nap only adds latency to the binder round trip.
                Thread.sleep(2)
                continue
            }
            val out = HookRemote.process(key, pkg, rate, channels, chunk)
            if (out.isNotEmpty()) {
                synchronized(recvLock) {
                    recv.write(out)
                    // The app stopped consuming (call ended, UI frozen): do
                    // not let a backlog turn into seconds of delay.
                    if (recv.size > rate) recv.dropOldest(recv.size - rate / 2)
                }
            }
        }
    }

    private companion object {
        const val MAX_CHUNK = 4_000
        val EMPTY = FloatArray(0)
    }
}

/** One tap per (package, format); streams die with the process. */
object HookTaps {
    private val taps = ConcurrentHashMap<String, HookTap>()

    fun get(pkg: String, process: String, rate: Int, channels: Int): HookTap {
        val key = "$pkg#$process|$rate|$channels"
        return taps.getOrPut(key) { HookTap(pkg, process, rate, channels) }
    }
}
