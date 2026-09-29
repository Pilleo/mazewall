import java.nio.file.Files;
import java.nio.file.Path;

/** Short gated JVM fixture: two distinct managed paths and a later unattributed phase. */
public final class TierEAllTargetWorkload {
    private static void await(Path gate) throws Exception {
        while (!Files.exists(gate)) Thread.sleep(2);
    }

    private static void readConfiguration(Path path) throws Exception {
        Files.readString(path);
    }

    private static void loadTemplate(Path path) throws Exception {
        Files.readString(path);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("first-gate second-gate phase-ready input output");
        var firstGate = Path.of(args[0]);
        var secondGate = Path.of(args[1]);
        var phaseReady = Path.of(args[2]);
        var input = Path.of(args[3]);
        var output = Path.of(args[4]);
        await(firstGate);
        var left = Thread.ofPlatform().start(() -> {
            try {
                for (int i = 0; i < 20; i++) readConfiguration(input);
            } catch (Exception failure) { throw new RuntimeException(failure); }
        });
        var right = Thread.ofPlatform().start(() -> {
            try {
                for (int i = 0; i < 20; i++) loadTemplate(input);
            } catch (Exception failure) { throw new RuntimeException(failure); }
        });
        left.join();
        right.join();
        Files.writeString(phaseReady, "attributed-complete");
        await(secondGate);
        for (int i = 0; i < 20; i++) Files.readString(input);
        Files.writeString(output, "completed");
    }
}
