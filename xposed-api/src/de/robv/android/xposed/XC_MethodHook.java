package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * Compile-time stub of the Xposed API.
 *
 * The module is built against these declarations only (compileOnly): at
 * runtime the framework injects its own implementation, which is why every
 * body here can throw. Keeping the stub in-tree means the build never needs
 * the network.
 */
public abstract class XC_MethodHook {
    public XC_MethodHook() {}

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static class Unhook {
        private final Member hookedMethod;

        public Unhook(Member hookedMethod) {
            this.hookedMethod = hookedMethod;
        }

        public Member getHookedMethod() {
            return hookedMethod;
        }

        public void unhook() {
            throw new UnsupportedOperationException("stub");
        }
    }

    public static final class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        private Object result;
        private Throwable throwable;
        private boolean returnEarly;

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.returnEarly = true;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.returnEarly = true;
        }

        public Object getObjectExtra(String key) {
            return null;
        }

        public void setObjectExtra(String key, Object value) {
        }

        public boolean isReturnEarly() {
            return returnEarly;
        }
    }
}
