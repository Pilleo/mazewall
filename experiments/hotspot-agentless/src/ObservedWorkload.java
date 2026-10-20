import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only source oracle and gates; runs without a collection agent. */
public final class ObservedWorkload {
    private static volatile long sink;

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("gate count workers pace");
        Path gate = Path.of(args[0]);
        int count = Integer.parseInt(args[1]);
        int workers = Integer.parseInt(args[2]);
        int pace = Integer.parseInt(args[3]);
        if (count != 400 || workers != 4 || pace != 20) throw new IllegalArgumentException("fixed A-OBS corpus");
        Files.writeString(gate.resolve("runtime.txt"), Runtime.version() + "\n"
                + ManagementFactory.getRuntimeMXBean().getInputArguments() + "\n"
                + ManagementFactory.getGarbageCollectorMXBeans().stream().map(x -> x.getName()).toList() + "\n");
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for (int worker = 0; worker < workers; worker++) {
            final int w = worker;
            Thread thread = Thread.ofPlatform().name("observed-worker-" + w).unstarted(() -> {
                try {
                    String tid = Files.readSymbolicLink(Path.of("/proc/thread-self")).getFileName().toString();
                    String method = w % 2 == 0 ? "routeA" : "routeB";
                    StringBuilder oracle = new StringBuilder();
                    for (int n = w; n < count; n += workers) {
                        oracle.append("marked-").append(n).append('\t').append(method).append('\t').append(tid).append('\n');
                    }
                    Files.writeString(gate.resolve("oracle-" + w + ".tsv"), oracle);
                    for (int n = 0; n < 10; n++) open(w, gate.resolve("warmup-" + w + "-" + n));
                    ready.countDown();
                    start.await();
                    if (Files.exists(gate.resolve("release"))) return;
                    for (int n = w; n < count; n += workers) {
                        open(w, gate.resolve("marked-" + n));
                        Thread.sleep(pace);
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                    ready.countDown();
                }
            });
            threads.add(thread);
            thread.start();
        }
        ready.await();
        if (failure.get() != null) throw new AssertionError(failure.get());
        Files.writeString(gate.resolve("ready"), Long.toString(ProcessHandle.current().pid()));
        while (!Files.exists(gate.resolve("measure"))) {
            if (Files.exists(gate.resolve("release"))) {
                start.countDown();
                return;
            }
            Thread.sleep(20);
        }
        Thread churn = null;
        if (Files.exists(gate.resolve("churn"))) {
            churn = Thread.ofPlatform().name("compiled-churn").start(() -> {
                while (!Files.exists(gate.resolve("done"))) {
                    for (int n = 0; n < 100_000; n++) sink = compiled(n);
                }
            });
        }
        Thread unload = null;
        if (Files.exists(gate.resolve("unload"))) {
            byte[] bytes = Files.readAllBytes(Path.of("/fixture/classes/ObservedWorkload$Payload.class"));
            unload = Thread.ofPlatform().name("class-unload-churn").start(() -> {
                try {
                    while (!Files.exists(gate.resolve("done"))) {
                        Class<?> type = new ClassLoader(null) {
                            Class<?> payload() { return defineClass(null, bytes, 0, bytes.length); }
                        }.payload();
                        sink = type.getDeclaredMethods().length;
                        byte[] pressure = new byte[256 * 1024];
                        sink += pressure.length;
                    }
                } catch (Throwable error) { failure.compareAndSet(null, error); }
            });
        }
        start.countDown();
        for (Thread thread : threads) thread.join();
        Files.writeString(gate.resolve("done"), "400\n");
        if (churn != null) churn.join();
        if (unload != null) unload.join();
        if (failure.get() != null) throw new AssertionError(failure.get());
        await(gate.resolve("release"));
    }

    private static void await(Path path) throws InterruptedException {
        while (!Files.exists(path)) Thread.sleep(20);
    }

    private static long compiled(long value) {
        for (int n = 0; n < 30; n++) value = Long.rotateLeft(value * 31 + n, 3);
        return value;
    }

    private static void open(int worker, Path path) throws Exception {
        if (worker % 2 == 0) routeA(path); else routeB(path);
    }

    static void routeA(Path path) throws Exception { leaf(path); }
    static void routeB(Path path) throws Exception { leaf(path); }
    static void leaf(Path path) throws Exception {
        try (var channel = Files.newByteChannel(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            sink = channel.position();
        }
    }

    public static final class Payload { public int value() { return 1; } }
}
