package timber.log;

/**
 * A silent stand-in for Jake Wharton's Timber, which the Plaud SDK logs through but doesn't
 * bundle. The SDK's own trees print whole HTTP exchanges, including the user token and the RSA
 * private key Plaud returns, to logcat and to app files. Here planted trees are dropped and every
 * call does nothing. Covers exactly the API the SDK uses (app/libs/README.md). Roudio itself
 * doesn't use Timber.
 */
public final class Timber {
    private static final Tree SILENT = new Tree() {
        @Override
        protected void log(int priority, String tag, String message, Throwable t) {
        }
    };

    private Timber() {
    }

    public static void plant(Tree tree) {
    }

    public static void uprootAll() {
    }

    public static Tree tag(String tag) {
        return SILENT;
    }

    public static void v(String message, Object... args) {
    }

    public static void v(Throwable t, String message, Object... args) {
    }

    public static void d(String message, Object... args) {
    }

    public static void d(Throwable t, String message, Object... args) {
    }

    public static void i(String message, Object... args) {
    }

    public static void i(Throwable t, String message, Object... args) {
    }

    public static void w(String message, Object... args) {
    }

    public static void w(Throwable t, String message, Object... args) {
    }

    public static void e(String message, Object... args) {
    }

    public static void e(Throwable t, String message, Object... args) {
    }

    public abstract static class Tree {
        public void v(String message, Object... args) {
        }

        public void d(String message, Object... args) {
        }

        public void i(String message, Object... args) {
        }

        public void w(String message, Object... args) {
        }

        public void e(String message, Object... args) {
        }

        protected abstract void log(int priority, String tag, String message, Throwable t);
    }

    public static class DebugTree extends Tree {
        protected String createStackElementTag(StackTraceElement element) {
            return null;
        }

        @Override
        protected void log(int priority, String tag, String message, Throwable t) {
        }
    }
}
