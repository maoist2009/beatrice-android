package org.voice.changer.hook

import android.content.Context
import android.os.Binder
import java.util.concurrent.ConcurrentHashMap

/**
 * Evidence that the Xposed module is really running, as opposed to the switch
 * in this app being on.
 *
 * A hooked app can only reach us through the provider, so every configuration
 * request it makes doubles as a heartbeat: it proves the framework loaded the
 * module into that package, which is the only way to check the scope from here
 * without root. Times are kept in memory (fresh, no I/O on the audio path) and
 * in prefs (survives a restart), written at most once a minute per package.
 */
object HookStatus {
    private const val PREFS = "hook_status"
    private const val WRITE_EVERY_MS = 60_000L

    private val live = ConcurrentHashMap<String, Long>()
    private val stored = ConcurrentHashMap<String, Long>()
    /**
     * API level the framework reported, set by [HookEntry] when it runs in this
     * process. The app cannot check this itself: the Xposed API classes are
     * injected into the module's classloader, not into the app's, so
     * `Class.forName` from app code fails even when the module is loaded — and
     * the module gets its own copy of this class, so a plain static would not
     * be shared either.
     */
    private const val PROP_API = "beatrice.hook.api"

    @Volatile private var moduleApi: Int? = null

    fun markLoaded(api: Int) {
        moduleApi = api
        // The module's classes are loaded by the framework's classloader, which
        // is a *different* class with its own statics, so the marker has to go
        // through something the whole process shares: the system properties.
        runCatching { System.setProperty(PROP_API, api.toString()) }
    }

    /** API level of the framework in this process, or null when it did not load here. */
    fun loadedApi(): Int? = moduleApi
        ?: runCatching { System.getProperty(PROP_API)?.toIntOrNull() }.getOrNull()

    /** uid -> package, so a once-per-second config call does not hit the PM every time. */
    private val uidPackage = ConcurrentHashMap<Int, String>()

    /**
     * Records the package behind the current binder call. The uid is
     * authoritative; the name the hook sends is only used when the uid cannot
     * be resolved (shared uids, restricted package visibility).
     */
    fun touchCaller(ctx: Context, claimed: String?) {
        val uid = Binder.getCallingUid()
        val pkg = uidPackage[uid] ?: run {
            val byUid = runCatching { ctx.packageManager.getPackagesForUid(uid) }.getOrNull()
            val resolved = when {
                byUid != null && claimed != null && byUid.contains(claimed) -> claimed
                !byUid.isNullOrEmpty() -> byUid.first()
                claimed != null -> claimed
                else -> return
            }
            uidPackage[uid] = resolved
            resolved
        }
        touch(ctx, pkg)
    }

    fun touch(ctx: Context, pkg: String) {
        val now = System.currentTimeMillis()
        val previous = live.put(pkg, now)
        if (previous != null && now - previous < WRITE_EVERY_MS) return
        stored[pkg] = now
        // Synchronous on purpose: this runs on a binder thread at most once a
        // minute per package, and a status card that forgets across restarts is
        // worse than a few milliseconds of that thread.
        runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(pkg, now).commit()
        }.onFailure { android.util.Log.i("hook_status", "prefs failed for $pkg: $it") }
    }

    /** package -> last time that package asked us for configuration. */
    fun seen(ctx: Context): Map<String, Long> {
        if (stored.isEmpty()) {
            runCatching {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                    if (value is Long) stored[key] = value
                }
            }
        }
        return stored + live
    }
}
