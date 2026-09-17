import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

/** Exercises the JDK 25 UnixNativeDispatcher.openat0 binding selected by the narrowed Tier E profile. */
public final class OpenatProfileWorkload {
    private static final int AT_FDCWD = -100;
    private static final int OPEN_READ_ONLY = 0;

    public static void main(String[] args) throws Exception {
        Class<?> dispatcher = Class.forName("sun.nio.fs.UnixNativeDispatcher");
        Method openat = dispatcher.getDeclaredMethod("openat", int.class, byte[].class, int.class, int.class);
        Method close = dispatcher.getDeclaredMethod("close", int.class);
        openat.setAccessible(true);
        close.setAccessible(true);
        byte[] path = "/etc/passwd".getBytes(StandardCharsets.UTF_8);
        for (int attempt = 0; attempt < 10; attempt++) {
            int fd = (int) openat.invoke(null, AT_FDCWD, path, OPEN_READ_ONLY, 0);
            close.invoke(null, fd);
        }
        System.out.println("openat-profile-workload: completed=10");
    }
}
