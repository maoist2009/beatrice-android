package org.voice.changer.hook

import android.util.Log
import org.voice.changer.BuildConfig
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.ConcurrentHashMap

/**
 * Xposed entry point, loaded by the framework into every scoped app.
 *
 * It installs a single hook — `AudioRecord.read` — and then stays out of the
 * way: whether the stream should be converted is decided per call from the
 * configuration the app serves, so enabling/disabling and changing targets
 * take effect within a second without restarting the target app.
 *
 * The only process it refuses is its own: the app records for the Convert page
 * through the same AudioRecord, and converting that would feed the engine its
 * own output. Which *other* apps are converted is the manager's scope plus the
 * app's own list — never a guess from the package name.
 */
class HookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return
        val process = lpparam.processName ?: pkg
        // First thing, before any filtering: the fact that this runs at all is
        // the proof the app's status card shows, and the only way the app can
        // learn it (the API classes are not visible from the app's classloader).
        // Reflected: the compile-only stub does not carry every framework member.
        val api = runCatching {
            XposedBridge::class.java.getMethod("getXposedVersion").invoke(null) as Int
        }.getOrNull()
        if (api != null) {
            runCatching { HookStatus.markLoaded(api) }
            Log.i("beatrice-hook", "entry loaded in $process (api $api)")
        }
        // Announce the module at app start, main process only: enough to prove
        // the scope, and subprocesses would wake the app for every push service.
        if (process == pkg) {
            runCatching {
                val instrumentation =
                    XposedHelpers.findClass("android.app.Instrumentation", lpparam.classLoader)
                XposedBridge.hookAllMethods(
                    instrumentation,
                    "callApplicationOnCreate",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                            runCatching { HookRemote.hello(pkg, pkg) }
                        }
                    },
                )
            }
        }
        if (pkg == BuildConfig.APPLICATION_ID) return
        if (!installed.add(process)) return
        try {
            val record = XposedHelpers.findClass("android.media.AudioRecord", lpparam.classLoader)
            XposedBridge.hookAllMethods(record, "read", object : XC_MethodHook() {
                override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    try {
                        if (HookRemote.enabled(pkg)) HookAudio.intercept(param, pkg, process)
                    } catch (t: Throwable) {
                        HookRemote.log("intercept failed: $t")
                    }
                }
            })
            HookRemote.log("AudioRecord.read hooked in $process")
        } catch (t: Throwable) {
            HookRemote.log("hook failed in $process: $t")
        }
    }

    private companion object {
        val installed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
}
