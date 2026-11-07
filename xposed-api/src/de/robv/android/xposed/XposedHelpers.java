package de.robv.android.xposed;

/** Compile-time stub of the Xposed API; see XC_MethodHook for the rationale. */
public final class XposedHelpers {
    private XposedHelpers() {}

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        throw new UnsupportedOperationException("stub");
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            String className, ClassLoader classLoader, String methodName,
            Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("stub");
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        throw new UnsupportedOperationException("stub");
    }

    public static Object getObjectField(Object obj, String fieldName) {
        throw new UnsupportedOperationException("stub");
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        throw new UnsupportedOperationException("stub");
    }
}
