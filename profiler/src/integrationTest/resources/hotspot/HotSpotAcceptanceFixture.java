import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Ordinary-user unique-token interpreter corpus; no agent or reference unwinder. */
public class HotSpotAcceptanceFixture {
    static long total;
    static void sharedLeaf(String path) throws Exception { try (var in = new FileInputStream(path)) { total += in.read(); } }
    static void bridge(String path) throws Exception { sharedLeaf(path); }
    static void pathA(String path) throws Exception { sharedLeaf(path); }
    static void pathB(String path) throws Exception { bridge(path); }
    static void call(int i, String path) throws Exception { if (i % 2 == 0) pathA(path); else pathB(path); }
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]); String run = args[1]; int operations = Integer.parseInt(args[2]);
        String[] paths = new String[operations];
        for (int i = 0; i < operations; i++) {
            paths[i] = root.resolve(String.format(Locale.ROOT, "hs-%s-%06d-%s", run, i, i % 2 == 0 ? "A" : "B")).toString();
            Files.write(Path.of(paths[i]), new byte[]{1});
        }
        String warm = root.resolve("warmup").toString(); Files.write(Path.of(warm), new byte[]{1});
        for (int i=0;i<400;i++) call(i,warm);
        System.out.println("ready " + ProcessHandle.current().pid()); System.out.flush();
        while (!Files.exists(root.resolve("prepare"))) Thread.sleep(2);
        for (int i=0;i<400;i++) { call(i,warm); Thread.sleep(1); }
        Files.writeString(root.resolve("prepared"), "ready\n");
        while (!Files.exists(root.resolve("gate"))) Thread.sleep(2);
        long start = System.nanoTime();
        for (int i=0;i<operations;i++) { call(i,paths[i]); Thread.sleep(1); }
        long elapsed = System.nanoTime()-start;
        Files.writeString(root.resolve("target.json"), String.format(Locale.ROOT, "{\"run\":\"%s\",\"pid\":%d,\"operations\":%d,\"elapsed_ns\":%d,\"checksum\":%d}\n",run,ProcessHandle.current().pid(),operations,elapsed,total));
        System.out.println("completed"); System.out.flush();
        while (!Files.exists(root.resolve("finish"))) Thread.sleep(2);
    }
}
