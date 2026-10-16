import java.io.FileInputStream;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.concurrent.*;

/** Oracle identity validates experiments only; no production capture consumes it. */
public class VirtualThreadOracle {
    record Oracle(String token, long logicalId, boolean virtual, boolean branchA) {}
    static void leaf(Path path) throws Exception {
        try (var input = new FileInputStream(path.toFile())) { if (input.read() != 1) throw new AssertionError(); }
    }
    static void routeA(Path path) throws Exception { leaf(path); }
    static void routeB(Path path) throws Exception { bridge(path); }
    static void bridge(Path path) throws Exception { leaf(path); }
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        for (int task = 0; task < 32; task++) for (int sequence = 0; sequence < 8; sequence++)
            Files.write(directory.resolve("vt-" + task + "-" + sequence + "-" + ((sequence & 1) == 0 ? "A" : "B")), new byte[]{1});
        Files.writeString(directory.resolve("ready"), Long.toString(ProcessHandle.current().pid()));
        RuntimeCandidateFixture.waitFor(directory.resolve("measure"));
        var oracle = new ConcurrentLinkedQueue<Oracle>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var jobs = new ArrayList<Future<?>>();
            for (int task = 0; task < 32; task++) {
                final int index = task;
                jobs.add(executor.submit(() -> {
                    for (int sequence = 0; sequence < 8; sequence++) {
                        boolean branchA = (sequence & 1) == 0;
                        String token = "vt-" + index + "-" + sequence + "-" + (branchA ? "A" : "B");
                        if (branchA) routeA(directory.resolve(token)); else routeB(directory.resolve(token));
                        oracle.add(new Oracle(token, Thread.currentThread().threadId(), Thread.currentThread().isVirtual(), branchA));
                        Thread.sleep(10);
                    }
                    return null;
                }));
            }
            for (var job : jobs) job.get();
        }
        var lines = new StringBuilder();
        for (var value : oracle) {
            lines.append("{\"token\":\"").append(value.token()).append("\",\"logical_id\":").append(value.logicalId())
                .append(",\"is_virtual\":").append(value.virtual()).append(",\"parent_available\":false,\"route\":[\"VirtualThreadOracle.leaf\",");
            if (!value.branchA()) lines.append("\"VirtualThreadOracle.bridge\",");
            lines.append("\"VirtualThreadOracle.route").append(value.branchA() ? 'A' : 'B').append("\"]}\n");
        }
        Files.writeString(directory.resolve("oracle.jsonl"), lines.toString());
        Files.writeString(directory.resolve("done"), "done"); RuntimeCandidateFixture.waitFor(directory.resolve("release"));
    }
}
