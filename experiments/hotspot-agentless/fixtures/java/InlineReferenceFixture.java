import io.mazewall.profiler.api.Profiler;
import io.mazewall.profiler.api.ProfilingResult;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;

/** Separate reference execution, never injected into the agentless candidate. */
public final class InlineReferenceFixture {
    private static volatile long checksum;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("usage: <artifact> <per-path>");
        Path root = Path.of(args[0]);
        int count = Integer.parseInt(args[1]);
        if (count < 1 || count > 1000) throw new IllegalArgumentException("invalid reference corpus size");
        Files.createDirectories(root);
        String[] a = new String[count];
        String[] b = new String[count];
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < count; i++) {
            a[i] = root.resolve("mw-reference-s" + i + "-A").toString();
            b[i] = root.resolve("mw-reference-s" + i + "-B").toString();
            Files.write(Path.of(a[i]), new byte[] {1});
            Files.write(Path.of(b[i]), new byte[] {2});
            expected.add(a[i]); expected.add(b[i]);
        }
        for (int i = 0; i < 3000; i++) { pathA(a[0]); pathB(b[0]); }
        checksum = 0;
        try {
            ProfilingResult<Long> result = Profiler.profile((Callable<Long>) () -> {
                for (int i = 0; i < count; i++) { pathA(a[i]); pathB(b[i]); }
                return checksum;
            }, false, true);
            if (!result.getCoverage().getStrategy().toString().equals("USER_NOTIF"))
                throw new AssertionError("unexpected reference profiler strategy");
            Set<String> seen = new HashSet<>();
            int wrong = 0;
            int unavailable = 0;
            int branchA = 0;
            int branchB = 0;
            StringBuilder rawStacks = new StringBuilder();
            for (var entry : result.getStackProfile().entrySet()) {
                if (!entry.getKey().getSyscallName().equals("OPENAT")) continue;
                for (String path : entry.getKey().getPaths()) {
                    if (!expected.contains(path)) continue;
                    for (StackTraceElement[] stack : entry.getValue()) {
                        rawStacks.append(path).append('\t');
                        for (StackTraceElement frame : stack) rawStacks.append(frame).append('|');
                        rawStacks.append('\n');
                        if (!seen.add(path)) { wrong++; continue; }
                        String branch = path.endsWith("-A") ? "pathA" : "pathB";
                        int leaf = -1; int caller = -1;
                        for (int i = 0; i < stack.length; i++) {
                            var frame = stack[i];
                            if (!frame.getClassName().equals("InlineReferenceFixture")) continue;
                            if (frame.getMethodName().equals("sharedLeaf")) {
                                if (leaf != -1) wrong++;
                                leaf = i;
                            }
                            if (frame.getMethodName().equals("pathA") || frame.getMethodName().equals("pathB")) {
                                if (!frame.getMethodName().equals(branch) || caller != -1) wrong++;
                                caller = i;
                            }
                        }
                        if (leaf < 0 || caller < 0) unavailable++;
                        else if (leaf >= caller) wrong++;
                        else if (branch.equals("pathA")) branchA++; else branchB++;
                    }
                }
            }
            String json = "{\"schema_version\":1,\"reference\":\"USER_NOTIF\",\"expected\":" + expected.size()
                    + ",\"observed\":" + seen.size() + ",\"wrong\":" + wrong + ",\"unavailable_segments\":" + unavailable
                    + ",\"branch_a_segments\":" + branchA + ",\"branch_b_segments\":" + branchB
                    + ",\"coverage_complete\":" + result.getCoverage().getComplete() + ",\"checksum\":" + result.getValue() + "}";
            Files.writeString(root.resolve("reference-report.json"), json + "\n");
            Files.writeString(root.resolve("reference-stacks.tsv"), rawStacks.toString());
            Files.writeString(root.resolve("reference-profile.json"), result.toProfileDocumentJson());
            System.out.println(json);
            if (seen.size() != expected.size() || wrong != 0 || unavailable != 0 || !result.getCoverage().getComplete() || result.getValue() != count * 3L)
                throw new AssertionError("incomplete or incorrect independent reference corpus");
        } finally { Profiler.shutdown(); }
    }

    private static void pathA(String path) throws Exception { sharedLeaf(path); }
    private static void pathB(String path) throws Exception { sharedLeaf(path); }
    private static void sharedLeaf(String path) throws Exception {
        try (var in = new FileInputStream(path)) { checksum += in.read(); }
    }
}
