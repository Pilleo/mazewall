import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/** A gated same-PID JVM exec action, not a collection agent. */
public final class ObservedExecWorkload {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("gate count workers pace");
        Path gate = Path.of(args[0]);
        NativeEngine.prepare();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread exec = Thread.ofPlatform().name("observed-exec").start(() -> {
            try {
                while (!Files.exists(gate.resolve("exec"))) {
                    if (Files.exists(gate.resolve("release"))) return;
                    Thread.sleep(20);
                }
                NativeEngine.replaceWithSleep();
            } catch (Throwable error) {
                if (error instanceof InterruptedException && Files.exists(gate.resolve("release"))) return;
                failure.set(error);
                StringWriter text = new StringWriter();
                error.printStackTrace(new PrintWriter(text));
                try {
                    Files.writeString(gate.resolve("exec-error"), text.toString());
                } catch (IOException writeError) {
                    error.addSuppressed(writeError);
                }
            }
        });
        try {
            ObservedWorkload.main(args);
        } finally {
            exec.interrupt();
            exec.join();
        }
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static final class NativeEngine {
        private static final StructLayout STATE = Linker.Option.captureStateLayout();
        private static final VarHandle ERRNO = STATE.varHandle(MemoryLayout.PathElement.groupElement("errno"));
        private static final MethodHandle EXECV = execv();

        private static MethodHandle execv() {
            Linker linker = Linker.nativeLinker();
            MemorySegment address = linker.defaultLookup().find("execv").orElseThrow();
            if (address.address() == 0) throw new IllegalStateException("NULL execv symbol");
            return linker.downcallHandle(address,
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                    Linker.Option.captureCallState("errno"));
        }

        private static void prepare() {
            if (ValueLayout.ADDRESS.byteSize() != 8 || ValueLayout.JAVA_INT.byteSize() != 4) {
                throw new IllegalStateException("qualification requires 64-bit pointers and C int32");
            }
        }

        private static void replaceWithSleep() throws Throwable {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment path = arena.allocateFrom("/bin/sleep");
                MemorySegment duration = arena.allocateFrom("4");
                MemorySegment argv = arena.allocate(ValueLayout.ADDRESS, 3);
                argv.setAtIndex(ValueLayout.ADDRESS, 0, path);
                argv.setAtIndex(ValueLayout.ADDRESS, 1, duration);
                argv.setAtIndex(ValueLayout.ADDRESS, 2, MemorySegment.NULL);
                MemorySegment state = arena.allocate(STATE);
                int result = (int) EXECV.invokeExact(state, path, argv);
                int errno = (int) ERRNO.get(state, 0L);
                String name = switch (errno) { case 1 -> "EPERM"; case 13 -> "EACCES"; default -> "native_error"; };
                throw new IOException("execv returned " + result + ": " + name + " errno=" + errno);
            }
        }
    }
}
