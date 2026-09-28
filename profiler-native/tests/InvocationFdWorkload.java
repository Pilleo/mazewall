import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/** A positive, stable directory FD is the per-operation native witness target. */
public final class InvocationFdWorkload {
    public static void main(String[] args) throws Exception {
        Class<?> dispatcher = Class.forName("sun.nio.fs.UnixNativeDispatcher");
        Method openat = dispatcher.getDeclaredMethod("openat", int.class, byte[].class, int.class, int.class);
        Method close = dispatcher.getDeclaredMethod("close", int.class);
        openat.setAccessible(true);
        close.setAccessible(true);
        int directoryFd = (int) openat.invoke(null, -100, "/etc".getBytes(StandardCharsets.UTF_8), 0x10000, 0);
        try {
            for (int operation = 0; operation < 2; ++operation) {
                int fd = (int) openat.invoke(null, directoryFd, "passwd".getBytes(StandardCharsets.UTF_8), 0, 0);
                close.invoke(null, fd);
            }
        } finally {
            close.invoke(null, directoryFd);
        }
        System.out.println("invocation-fd-workload: completed=2");
    }
}
