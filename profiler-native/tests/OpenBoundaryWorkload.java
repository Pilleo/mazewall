import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Uninstrumented owner waits for child exit before checking its private sidecar. */
public final class OpenBoundaryWorkload {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("child")) {
            child(Path.of(args[1]));
            return;
        }
        boolean enabled = args[0].equals("enabled");
        require(enabled || args[0].equals("disabled"), "invalid mode");
        Path directory = Files.createTempDirectory(Path.of("/tmp/opencode"), "a4-open-jni-");
        Files.writeString(directory.resolve("first-input"), "first");
        Files.writeString(directory.resolve("second-input"), "second");
        String options = "session=4d5a,session-bound-definitions,mode=full-stream,"
                + "definitions=" + directory.resolve("definitions.mzse")
                + ",capture-yield=" + directory.resolve("capture-yield.mzcy");
        if (enabled) options += ",test-open-boundary=" + directory.resolve("open-calls.diag");
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-agentpath:" + args[1] + "=" + options,
                "-cp", System.getProperty("java.class.path"),
                OpenBoundaryWorkload.class.getName(), "child", directory.toString()));
        int exit = new ProcessBuilder(command).inheritIO().start().waitFor();
        require(exit == 0, "child exit=" + exit + " artifacts=" + directory);
        Path sidecar = directory.resolve("open-calls.diag");
        if (!enabled) {
            require(!Files.exists(sidecar) && !Files.exists(Path.of(sidecar + ".tmp")), "disabled created sidecar");
        } else {
            require(Files.exists(sidecar), "enabled sidecar absent: " + sidecar);
            validate(Files.readAllBytes(sidecar), directory);
            verifyRejectedOptions(args[1], directory);
        }
        byte[] yield = Files.readAllBytes(directory.resolve("capture-yield.mzcy"));
        require(yield.length >= 24 && yield[0] == 'M' && yield[3] == 'Y', "MZCY lost");
        System.out.println("open-boundary-workload: " + args[0] + " passed artifacts=" + directory);
    }

    private static void verifyRejectedOptions(String library, Path directory) throws Exception {
        String base = "session=4d5a,session-bound-definitions,mode=full-stream,definitions="
                + directory.resolve("rejected.mzse") + ",capture-yield=" + directory.resolve("rejected.mzcy");
        String diagnostic = ",test-open-boundary=" + directory.resolve("rejected.diag");
        var invalid = new ArrayList<>(List.of(
                base + ",test-open-boundary=", base + ",test-open-boundary=relative", base + ",test-open-boundary=/",
                base + diagnostic + diagnostic,
                base.replace("session=4d5a,", "") + diagnostic,
                base.replace("session-bound-definitions,", "") + diagnostic,
                base.replace("definitions=" + directory.resolve("rejected.mzse") + ",", "") + diagnostic,
                base.substring(0, base.indexOf(",capture-yield=")) + diagnostic));
        for (String control : List.of("benchmark-phases", "force-capture-failure-once", "force-exec-once",
                "callback-suppression-probe", "capture-timing-diagnostic", "capture-profile=jdk25-unix-openat0",
                "mode=unique-stack-syscall", "marker-version=v2", "stack-transport=inline-per-invocation")) {
            invalid.add(base + diagnostic + "," + control);
        }
        for (int i = 0; i < invalid.size(); ++i) {
            Path output = directory.resolve("option-rejection-" + i + ".log");
            int exit = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-agentpath:" + library + "=" + invalid.get(i), "-version")
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start().waitFor();
            require(exit != 0 && Files.readString(output).contains("load rejected invalid VM or options"),
                    "invalid options accepted: " + invalid.get(i));
        }
        require(!Files.exists(directory.resolve("rejected.diag")) && !Files.exists(directory.resolve("rejected.diag.tmp")),
                "rejected options created sidecar");
        System.out.println("open-boundary-options: rejected=" + invalid.size());
    }

    private static void child(Path directory) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread first = Thread.ofPlatform().name("boundary-first").unstarted(() -> read(directory, "first", failure));
        Thread second = Thread.ofPlatform().name("boundary-second").unstarted(() -> read(directory, "second", failure));
        first.start(); second.start(); first.join(); second.join();
        if (failure.get() != null) throw new AssertionError("worker failed", failure.get());
        try {
            Files.readString(directory.resolve("missing-input"));
            throw new AssertionError("original exception lost");
        } catch (NoSuchFileException expected) {
            require(expected.getFile().equals(directory.resolve("missing-input").toString()), "exception path replaced");
        }
    }

    private static void read(Path directory, String name, AtomicReference<Throwable> failure) {
        try {
            require(Files.readString(directory.resolve(name + "-input")).equals(name), "original result lost");
        } catch (Throwable problem) {
            failure.compareAndSet(null, problem);
        }
    }

    private static void validate(byte[] bytes, Path directory) {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        require(bytes.length >= 128 && b.getInt(0) == 0x424f5a4d, "MZOB magic");
        require(b.getShort(4) == 1 && b.getShort(6) == 128 && b.getInt(8) == 384 && b.getInt(12) == 1024, "layout");
        long count = b.getLong(32);
        require(count > 0 && count <= 1024 && bytes.length == 128 + 384 * count, "record count/size");
        require(b.getLong(16) == 0x4d5a && b.getLong(24) == count && b.getLong(40) == count
                && b.getLong(48) == count && b.getLong(88) == 1, "incomplete producer");
        for (int offset = 56; offset <= 80; offset += 8) require(b.getLong(offset) == 0, "diagnostic failures @" + offset);
        for (int offset = 104; offset < 128; offset++) require(bytes[offset] == 0, "header reserved");
        var ids = new HashSet<Long>();
        var tids = new HashSet<Integer>();
        var found = new HashSet<String>();
        for (int offset = 128; offset < bytes.length; offset += 384) {
            require(b.getInt(offset) == 3 && b.getInt(offset + 4) == 0, "partial/failed record");
            int length = b.getInt(offset + 84);
            require(length > 0 && length <= 256 && bytes[offset + 96 + length - 1] == 0, "path length");
            String path = new String(bytes, offset + 96, length - 1, StandardCharsets.UTF_8);
            String name = null;
            for (String candidate : List.of("first-input", "second-input", "missing-input")) {
                if (path.equals(directory.resolve(candidate).toString())) name = candidate;
            }
            if (name == null) continue;
            require(found.add(name), "duplicate fixture read: " + name);
            require(Arrays.equals(Arrays.copyOfRange(bytes, offset + 96, offset + 96 + length - 1),
                    directory.resolve(name).toString().getBytes(StandardCharsets.UTF_8)), "path bytes");
            require(b.getInt(offset + 24) == b.getLong(96) && b.getInt(offset + 28) > 0, "namespace identity");
            require(b.getLong(offset + 16) > 0 && b.getLong(offset + 352) > 0 && b.getInt(offset + 88) == 1, "call identity");
            long bridgeStart = b.getLong(offset + 32), bridgeEnd = b.getLong(offset + 40);
            long entry = b.getLong(offset + 48), returned = b.getLong(offset + 56);
            require(bridgeStart > 0 && bridgeStart <= bridgeEnd && bridgeEnd <= entry && entry <= returned, "monotonic intervals");
            require(b.getInt(offset + 72) == 0, "setup open counted as read");
            int result = b.getInt(offset + 80);
            if (name.equals("missing-input")) require(result == -1, "failed-open result=" + result);
            else {
                require(result >= 0, "successful FD lost");
                require(ids.add(b.getLong(offset + 8)) && tids.add(b.getInt(offset + 28)), "worker identities reused");
            }
        }
        require(found.size() == 3 && ids.size() == 2 && tids.size() == 2, "fixture reads missing");
    }
}
