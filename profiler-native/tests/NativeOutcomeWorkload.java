import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/** Original native outcomes must survive a recoverable profiler capture failure. */
public final class NativeOutcomeWorkload {
    private static final int AT_FDCWD = -100;

    public static void main(String[] args) throws Exception {
        Class<?> dispatcher = Class.forName("sun.nio.fs.UnixNativeDispatcher");
        Method openat = dispatcher.getDeclaredMethod("openat", int.class, byte[].class, int.class, int.class);
        Method close = dispatcher.getDeclaredMethod("close", int.class);
        openat.setAccessible(true);
        close.setAccessible(true);

        int descriptor = (int) openat.invoke(null, AT_FDCWD, "/etc/passwd".getBytes(StandardCharsets.UTF_8), 0, 0);
        if (descriptor < 0) throw new AssertionError("original native return value was lost");
        close.invoke(null, descriptor);

        String absent = "/tmp/mazewall-tier-e-native-outcome-absent-" + ProcessHandle.current().pid();
        try {
            openat.invoke(null, AT_FDCWD, absent.getBytes(StandardCharsets.UTF_8), 0, 0);
            throw new AssertionError("original native exception was lost");
        } catch (InvocationTargetException invocation) {
            Throwable applicationFailure = invocation.getCause();
            if (!applicationFailure.getClass().getName().equals("sun.nio.fs.UnixException")) {
                throw new AssertionError("unexpected native failure", applicationFailure);
            }
            Method errno = applicationFailure.getClass().getDeclaredMethod("errno");
            errno.setAccessible(true);
            if ((int) errno.invoke(applicationFailure) != 2) {
                throw new AssertionError("original ENOENT was lost", applicationFailure);
            }
        }
        System.out.println("native-outcome-workload: return=preserved exception=preserved errno=2");
    }
}
