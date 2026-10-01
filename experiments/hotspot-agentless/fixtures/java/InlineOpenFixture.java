import java.nio.file.Files;
import java.nio.file.Path;

/** Marked ordinary Java opens; compiler evidence is a later acceptance gate. */
public final class InlineOpenFixture {
    private static volatile long checksum;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("usage: <root> <gate>");
        Path root = Path.of(args[0]);
        Path gate = Path.of(args[1]);
        Files.createDirectories(root);
        Path a = root.resolve("mw-inline-open-A");
        Path b = root.resolve("mw-inline-open-B");
        Files.write(a, new byte[] {1});
        Files.write(b, new byte[] {2});
        System.out.println("ready " + ProcessHandle.current().pid());
        while (!Files.exists(gate)) Thread.sleep(20);
        for (int i = 0; i < 200; i++) {
            pathA(a);
            pathB(b);
            Thread.sleep(20);
        }
        System.out.println("completed " + checksum);
    }

    private static void pathA(Path path) throws Exception { sharedLeaf(path); }
    private static void pathB(Path path) throws Exception { sharedLeaf(path); }
    private static void sharedLeaf(Path path) throws Exception {
        try (var in = Files.newInputStream(path)) { checksum += in.read(); }
    }
}
