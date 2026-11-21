package org.voice.changer.hook

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import org.voice.changer.BuildConfig
import de.robv.android.xposed.XposedBridge

/**
 * Hook-process side of the bridge: configuration cache plus the binder calls
 * that carry audio to the app.
 *
 * Nothing here touches the engine, and nothing here blocks the audio thread:
 * the configuration is refreshed on a helper thread and the audio itself is
 * moved by [HookTap].
 */
object HookRemote {
    private const val TAG = "beatrice-hook"
    private const val CFG_TTL_MS = 1_000L

    private val authority = BuildConfig.APPLICATION_ID + ".provider"
    private val uri: Uri = Uri.parse("content://$authority")

    @Volatile private var app: Context? = null
    @Volatile private var cfgEnabled = false
    @Volatile private var cfgAll = false
    @Volatile private var cfgTargets: Set<String> = emptySet()

    /** Last refresh attempt, not last success: a dead app must not be retried per buffer. */
    @Volatile private var cfgAt = 0L
    @Volatile private var refreshing = false

    /** The hooked app's own context, resolved lazily (it may not exist at load time). */
    private fun context(): Context? {
        app?.let { return it }
        val found = try {
            val thread = Class.forName("android.app.ActivityThread")
            thread.getMethod("currentApplication").invoke(null) as? Context
        } catch (t: Throwable) {
            null
        }
        if (found != null) app = found
        return found
    }

    /**
     * Cheap, allocation-free gate called on the target's audio thread. The
     * configuration is fetched in the background, at most once a second.
     */
    fun enabled(pkg: String): Boolean {
        scheduleRefresh()
        return cfgEnabled && (cfgAll || cfgTargets.contains(pkg))
    }

    private fun scheduleRefresh() {
        val now = SystemClock.elapsedRealtime()
        if (now - cfgAt < CFG_TTL_MS || refreshing) return
        cfgAt = now
        refreshing = true
        Thread {
            try {
                val ctx = context()
                if (ctx != null) {
                    ctx.contentResolver.call(uri, "config", null, null)?.let { bundle ->
                        cfgEnabled = bundle.getBoolean("enabled", false)
                        cfgAll = bundle.getBoolean("all", false)
                        cfgTargets = bundle.getStringArrayList("targets")?.toSet() ?: emptySet()
                    }
                }
            } catch (t: Throwable) {
                // App not reachable: fail closed, the raw microphone passes.
                cfgEnabled = false
            } finally {
                refreshing = false
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Announces the module to the app once per process. The app records the
     * caller, which is what lets its status card tell a scope that works from
     * one that only looks right.
     */
    fun hello(pkg: String, process: String) {
        Thread {
            try {
                val ctx = context() ?: return@Thread.also {
                    android.util.Log.i(TAG, "hello skipped: no context yet")
                }
                ctx.contentResolver.call(uri, "hello", null, Bundle().apply {
                    putString("pkg", pkg)
                    putString("process", process)
                })
                android.util.Log.i(TAG, "hello sent for $process")
            } catch (t: Throwable) {
                // The app is not running; nothing to report to.
                android.util.Log.i(TAG, "hello failed for $process: $t")
            }
        }.apply { isDaemon = true }.start()
    }

    fun process(key: String, pkg: String, rate: Int, channels: Int, pcm: FloatArray): FloatArray {
        val ctx = context() ?: return EMPTY
        return try {
            val extras = Bundle().apply {
                putString("key", key)
                putString("pkg", pkg)
                putInt("rate", rate)
                putInt("channels", channels)
                putFloatArray("pcm", pcm)
            }
            val reply = ctx.contentResolver.call(uri, "process", null, extras) ?: return EMPTY
            reply.getFloatArray("pcm") ?: EMPTY
        } catch (t: Throwable) {
            EMPTY
        }
    }

    fun report(pkg: String, frames: Long) {
        val ctx = context() ?: return
        try {
            ctx.contentResolver.call(uri, "report", null, Bundle().apply {
                putString("pkg", pkg)
                putLong("frames", frames)
            })
        } catch (t: Throwable) {
            // stats only
        }
    }

    fun log(text: String) {
        runCatching { XposedBridge.log("$TAG: $text") }
    }

    private val EMPTY = FloatArray(0)
}
