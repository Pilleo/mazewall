import java.io.FileInputStream;

/** Bytecode template; its fixed-width class name is replaced per generation. */
public final class UnloadG00000 {
    private static long checksum;
    public static void pathA(String path) throws Exception { sharedLeaf(path); }
    public static void pathB(String path) throws Exception { sharedLeaf(path); }
    private static void sharedLeaf(String path) throws Exception {
        try (var in = new FileInputStream(path)) { checksum += in.read(); }
    }
    public static long checksum() { return checksum; }
}
