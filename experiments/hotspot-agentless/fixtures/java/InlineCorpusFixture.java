import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** A finite, single-platform-thread corpus; compilation evidence is external. */
public final class InlineCorpusFixture {
    private static volatile long checksum;

    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("usage: <root> <gate> <run> <per-path>");
        Path root = Path.of(args[0]);
        Path gate = Path.of(args[1]);
        String run = args[2];
        int count = Integer.parseInt(args[3]);
        if (!run.matches("[a-f0-9]{16}") || count != 1000) throw new IllegalArgumentException("invalid corpus identity/size");
        Files.createDirectories(root);
        String warmA = root.resolve("warmup-A").toString();
        String warmB = root.resolve("warmup-B").toString();
        Files.write(Path.of(warmA), new byte[] {1});
        Files.write(Path.of(warmB), new byte[] {2});
        // The collector has not attached yet. These unmarked operations train
        // both root methods under controlled C2; no discovery losses are hidden.
        for (int i = 0; i < 3000; i++) { pathA(warmA); pathB(warmB); }
        String[] a = new String[count];
        String[] b = new String[count];
        try (var manifest = Files.newBufferedWriter(root.resolve("operations.csv"))) {
            manifest.write("run,worker,sequence,branch,filename\n");
            for (int i = 0; i < count; i++) {
                a[i] = root.resolve("mw-inline-corpus-" + run + "-w0-s" + i + "-A").toString();
                b[i] = root.resolve("mw-inline-corpus-" + run + "-w0-s" + i + "-B").toString();
                Files.write(Path.of(a[i]), new byte[] {1});
                Files.write(Path.of(b[i]), new byte[] {2});
                manifest.write(run + ",0," + i + ",A," + a[i] + "\n");
                manifest.write(run + ",0," + i + ",B," + b[i] + "\n");
            }
        }
        checksum = 0;
        System.out.println("ready " + ProcessHandle.current().pid());
        while (!Files.exists(gate)) Thread.sleep(20);
        for (int i = 0; i < count; i++) {
            pathA(a[i]);
            pathB(b[i]);
            Thread.sleep(10);
        }
        if (checksum != count * 3L) throw new AssertionError("corpus completion checksum");
        System.out.println("completed " + checksum);
        // Give existing async readers a bounded tail before target exit. This
        // is not claimed as a kernel loss/drain barrier.
        Thread.sleep(1000);
    }

    private static void pathA(String path) throws Exception { sharedLeaf(path); }
    private static void pathB(String path) throws Exception { sharedLeaf(path); }
    private static void sharedLeaf(String path) throws Exception {
        try (var in = new FileInputStream(path)) { checksum += in.read(); }
    }
}
