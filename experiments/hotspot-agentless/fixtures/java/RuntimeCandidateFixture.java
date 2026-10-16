import java.io.FileInputStream;
import java.nio.file.*;

/** Oracle-only candidate fixture; native frames never come from this manifest. */
public class RuntimeCandidateFixture {
    static void leaf(Path path) throws Exception {
        try (var input = new FileInputStream(path.toFile())) { if (input.read() != 1) throw new AssertionError(); }
    }
    static void routeA(Path path) throws Exception { leaf(path); }
    static void routeB(Path path) throws Exception { bridge(path); }
    static void bridge(Path path) throws Exception { leaf(path); }
    static void waitFor(Path path) throws Exception {
        long deadline = System.nanoTime() + 120_000_000_000L;
        while (!Files.exists(path)) { if (System.nanoTime() >= deadline) throw new IllegalStateException("fixture gate expired"); Thread.sleep(10); }
    }
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]); int count = Integer.parseInt(args[1]);
        if (count < 1 || count > 400) throw new IllegalArgumentException();
        Path warm = directory.resolve("warm"); Files.write(warm, new byte[]{1});
        for (int i = 0; i < 20000; i++) { if ((i & 1) == 0) routeA(warm); else routeB(warm); }
        long pid = ProcessHandle.current().pid();
        String stat = Files.readString(Path.of("/proc/self/stat"));
        String ticks = stat.substring(stat.lastIndexOf(')') + 1).trim().split("\\s+")[19];
        var manifest = new StringBuilder("{\"pid\":" + pid + ",\"start_ticks\":" + ticks + ",\"tokens\":{");
        for (int i = 0; i < count; i++) {
            String branch = (i & 1) == 0 ? "A" : "B";
            Path path = directory.resolve("token-" + i + "-" + branch); Files.write(path, new byte[]{1});
            if (i != 0) manifest.append(',');
            manifest.append('"').append(path).append("\":[\"RuntimeCandidateFixture.leaf\",");
            if (branch.equals("B")) manifest.append("\"RuntimeCandidateFixture.bridge\",");
            manifest.append("\"RuntimeCandidateFixture.route").append(branch).append("\"]");
        }
        Files.writeString(directory.resolve("manifest.json"), manifest.append("}}").toString());
        Files.writeString(directory.resolve("ready"), Long.toString(pid));
        waitFor(directory.resolve("measure"));
        for (int i = 0; i < count; i++) {
            Path path = directory.resolve("token-" + i + "-" + ((i & 1) == 0 ? "A" : "B"));
            if ((i & 1) == 0) routeA(path); else routeB(path);
            Thread.sleep(10);
        }
        Files.writeString(directory.resolve("done"), "done"); waitFor(directory.resolve("release"));
    }
}
