import java.io.FileInputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Ordinary-user, interpreter-only metadata lifetime investigation. */
public final class UnloadDriver {
    private static final int GENERATIONS = 80;
    private static final int PER_BRANCH = 10;

    private static final class DisposableLoader extends ClassLoader {
        DisposableLoader() { super(UnloadDriver.class.getClassLoader()); }
        Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
    }

    private static byte[] generationBytes(byte[] template, int generation) {
        byte[] from = "UnloadG00000".getBytes(StandardCharsets.US_ASCII);
        byte[] to = String.format("UnloadG%05d", generation).getBytes(StandardCharsets.US_ASCII);
        byte[] bytes = template.clone();
        int replacements = 0;
        for (int i = 0; i <= bytes.length - from.length; i++) {
            if (Arrays.equals(bytes, i, i + from.length, from, 0, from.length)) {
                System.arraycopy(to, 0, bytes, i, to.length); replacements++; i += from.length - 1;
            }
        }
        if (replacements == 0) throw new AssertionError("template name missing");
        return bytes;
    }

    private static String token(Path root, int g, int s, String branch) {
        return root.resolve(String.format("mw-unload-g%05d-s%03d-%s", g, s, branch)).toString();
    }

    private static WeakReference<ClassLoader> runGeneration(byte[] bytes, Path root, int generation) throws Throwable {
        DisposableLoader loader = new DisposableLoader();
        Class<?> type = loader.define(bytes);
        var lookup = MethodHandles.publicLookup();
        var signature = MethodType.methodType(void.class, String.class);
        var a = lookup.findStatic(type, "pathA", signature);
        var b = lookup.findStatic(type, "pathB", signature);
        for (int s = 0; s < PER_BRANCH; s++) {
            a.invokeExact(token(root, generation, s, "A"));
            b.invokeExact(token(root, generation, s, "B"));
            Thread.sleep(5);
        }
        var sum = lookup.findStatic(type, "checksum", MethodType.methodType(long.class));
        long value = (long) sum.invokeExact();
        if (value != PER_BRANCH * 3L) throw new AssertionError("generation checksum");
        // Allow the unchanged asynchronous provider to publish definitions while
        // this class is still alive. Logging makes this an instrumented test.
        Thread.sleep(generation == 0 ? 1000 : 300);
        return new WeakReference<>(loader);
    }

    public static void main(String[] args) throws Throwable {
        Path root = Path.of(args[0]); Path gate = Path.of(args[1]);
        byte[] template = Files.readAllBytes(Path.of(args[2]));
        Files.createDirectories(root);
        Path warm = root.resolve("unmarked-warmup"); Files.write(warm, new byte[] {1});
        for (int i = 0; i < 100; i++) { try (var in = new FileInputStream(warm.toFile())) { in.read(); } }
        try (var manifest = Files.newBufferedWriter(root.resolve("unload-operations.csv"))) {
            manifest.write("generation,sequence,branch,filename\n");
            for (int g = 0; g < GENERATIONS; g++) for (int s = 0; s < PER_BRANCH; s++) {
                for (String branch : new String[] {"A", "B"}) {
                    String filename = token(root, g, s, branch);
                    Files.write(Path.of(filename), new byte[] {(byte) (branch.equals("A") ? 1 : 2)});
                    manifest.write(g + "," + s + "," + branch + "," + filename + "\n");
                }
            }
        }
        System.out.println("ready " + ProcessHandle.current().pid());
        while (!Files.exists(gate)) Thread.sleep(20);
        int cleared = 0;
        for (int g = 0; g < GENERATIONS; g++) {
            WeakReference<ClassLoader> reference = runGeneration(generationBytes(template, g), root, g);
            for (int attempts = 0; attempts < 8 && reference.get() != null; attempts++) { System.gc(); Thread.sleep(10); }
            if (reference.get() == null) cleared++;
            System.out.println("generation " + g + " loader_cleared " + (reference.get() == null));
        }
        System.out.println("completed generations " + GENERATIONS + " loaders_cleared " + cleared);
        Thread.sleep(1000);
    }
}
