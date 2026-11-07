package com.beatrice.lite.hook

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.beatrice.lite.LiveService
import java.util.concurrent.ConcurrentHashMap

/**
 * App-side end of the hook bridge.
 *
 * A hooked target app talks to us through [HookProvider] (a binder call into
 * this process), which is the only way to reach the engine: the RVC NPU
 * contexts are ~220 MB and must not be duplicated into every hooked process,
 * so the hook itself only moves audio and all conversion happens here.
 *
 * Only one stream can drive the resident engine at a time — the processor is
 * stateful — so sessions claim ownership; an idle owner is stolen after
 * [OWNER_TIMEOUT_MS].
 */
object HookBridge {
    private const val TAG = "hook_bridge"
    private const val OWNER_TIMEOUT_MS = 2_000L
    private const val SESSION_IDLE_MS = 8_000L

    private val sessions = ConcurrentHashMap<String, HookSession>()
    private val counts = ConcurrentHashMap<String, Long>()
    private val ownerLock = Any()

    @Volatile private var appContext: Context? = null
    private var owner: String? = null
    private var ownerAt = 0L
    private var pruner: Thread? = null

    /** Last stream the UI should show as "hooked". */
    @Volatile var lastPkg: String? = null
        private set

    @Volatile var lastAt = 0L
        private set

    val delivered: Long get() = counts.values.sum()

    fun deliveredFor(pkg: String): Long = counts[pkg] ?: 0L

    fun attach(context: Context) {
        appContext = context.applicationContext
        if (pruner == null) {
            pruner = Thread(::pruneLoop, "hook-pruner").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    /** Drops every stream; called when the hosting service goes away. */
    fun detach() {
        owner = null
        val stale = sessions.values.toList()
        sessions.clear()
        stale.forEach { runCatching { it.close() } }
    }

    fun isActive(): Boolean = sessions.values.any { it.isActive() }

    /** Pushed when the user moves a slider while a stream is hooked. */
    fun setParams(params: com.beatrice.lite.engine.EngineParams) {
        sessions.values.forEach { it.setParams(params) }
    }

    fun config(): Bundle {
        val ctx = appContext
        val bundle = Bundle()
        if (ctx == null) {
            bundle.putBoolean("enabled", false)
            return bundle
        }
        val enabled = HookSettings.enabled(ctx) && !LiveService.micActive
        bundle.putBoolean("enabled", enabled)
        bundle.putBoolean("all", HookSettings.allApps(ctx))
        bundle.putStringArrayList("targets", ArrayList(HookSettings.targets(ctx)))
        return bundle
    }

    /** Binder entry point: one capture buffer in, one converted buffer out. */
    fun processCall(extras: Bundle?): Bundle {
        val ctx = appContext ?: return Bundle()
        if (extras == null) return Bundle()
        if (LiveService.micActive) return Bundle()
        if (!HookSettings.enabled(ctx)) return Bundle()

        val key = extras.getString("key") ?: return Bundle()
        val pkg = extras.getString("pkg") ?: return Bundle()
        val rate = extras.getInt("rate")
        val channels = extras.getInt("channels", 1)
        val pcm = extras.getFloatArray("pcm") ?: return Bundle()
        if (rate <= 0 || pcm.isEmpty()) return Bundle()

        val session = sessions[key] ?: run {
            val fresh = HookSession(key, pkg, rate, channels, ctx)
            val raced = sessions.putIfAbsent(key, fresh)
            if (raced != null) {
                fresh.close()
                raced
            } else {
                Log.i(TAG, "session $key opened (${pcm.size} frames/buffer)")
                fresh
            }
        }
        val out = session.exchange(pcm)
        if (out.isEmpty()) return Bundle()
        lastPkg = pkg
        lastAt = System.currentTimeMillis()
        counts.merge(pkg, out.size.toLong(), Long::plus)
        return Bundle().apply { putFloatArray("pcm", out) }
    }

    fun reportCall(extras: Bundle?) {
        val pkg = extras?.getString("pkg") ?: return
        val frames = extras.getLong("frames", 0L)
        if (frames > 0) counts.merge(pkg, frames, Long::plus)
        lastPkg = pkg
        lastAt = System.currentTimeMillis()
    }

    // -------------------------------------------------------- ownership

    fun isOwner(key: String): Boolean = synchronized(ownerLock) { owner == key }

    /**
     * Claims the engine for [key]. A different stream may only take over once
     * the current owner has stopped sending audio, and the old owner is shut
     * down first so two sessions never drive the stateful processor at once.
     */
    fun claim(key: String): Boolean {
        val retired: HookSession?
        synchronized(ownerLock) {
            val now = SystemClock.elapsedRealtime()
            if (owner == key) {
                ownerAt = now
                return true
            }
            if (owner != null && now - ownerAt < OWNER_TIMEOUT_MS) return false
            val old = owner
            owner = key
            ownerAt = now
            retired = if (old != null) sessions.remove(old) else null
            if (old != null) Log.i(TAG, "engine handed from $old to $key")
        }
        retired?.let { runCatching { it.close() } }
        return true
    }

    fun touch(key: String) {
        synchronized(ownerLock) { if (owner == key) ownerAt = SystemClock.elapsedRealtime() }
    }

    fun release(key: String) {
        synchronized(ownerLock) { if (owner == key) owner = null }
    }

    private fun pruneLoop() {
        while (true) {
            try {
                Thread.sleep(2_000)
                val now = System.currentTimeMillis()
                for ((key, session) in sessions) {
                    if (now - session.lastUse > SESSION_IDLE_MS) {
                        sessions.remove(key)
                        session.close()
                        release(key)
                        Log.i(TAG, "session $key idle, closed")
                    }
                }
            } catch (t: InterruptedException) {
                return
            } catch (t: Throwable) {
                Log.w(TAG, "prune failed", t)
            }
        }
    }
}
