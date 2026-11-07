package de.robv.android.xposed.callbacks;

/** Compile-time stub of the Xposed API; see XC_MethodHook for the rationale. */
public class XC_LoadPackage {
    public static final class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public boolean isFirstApplication;
    }
}
