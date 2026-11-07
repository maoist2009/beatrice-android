package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/** Compile-time stub of the Xposed API; see XC_MethodHook for the rationale. */
public interface IXposedHookLoadPackage {
    void handleLoadPackage(LoadPackageParam lpparam) throws Throwable;
}
