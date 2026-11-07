package com.beatrice.lite.hook

import com.beatrice.lite.BuildConfig
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
 */
class HookEntry : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName ?: return
        if (pkg == BuildConfig.APPLICATION_ID) return
        val process = lpparam.processName ?: pkg
        if (SKIP.containsMatchIn(process)) return
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
        /** Never touch our own recorder, or apps whose job is recording. */
        val SKIP = Regex("recorder|voicenote|soundrecorder", RegexOption.IGNORE_CASE)
        val installed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
}
