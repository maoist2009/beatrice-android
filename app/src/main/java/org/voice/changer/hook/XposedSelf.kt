package org.voice.changer.hook

/**
 * Whether the Xposed framework has injected into this process.
 *
 * This is the only statement about the module that can be made from inside the
 * app without guessing: the framework loads its API into exactly the processes
 * it selected, so the class is present precisely when this app is in the
 * module's scope. Absence proves nothing — a module is normally not scoped
 * into itself — so the UI shows that as "unknown", never as "disabled".
 *
 * Manager apps are deliberately not looked up. LSPosed and its forks ship the
 * manager as a parasitic APK inside the module directory, it can be hidden
 * from the launcher, and Android 11 filters package queries anyway, so a
 * package-name scan cannot answer whether the module is enabled.
 */
object XposedSelf {
    /** API level of the injected framework, or null when this process is not scoped. */
    fun apiVersion(): Int? = runCatching {
        val bridge = Class.forName("de.robv.android.xposed.XposedBridge")
        bridge.getMethod("getXposedVersion").invoke(null) as Int
    }.getOrNull()
}
