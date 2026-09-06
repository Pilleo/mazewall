import java.nio.file.Files;
import java.nio.file.Path;

public final class AgentWorkload {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].startsWith("gate:")) {
            var gate = Path.of(args[0].substring(5));
            while (!Files.exists(gate)) Thread.sleep(1);
        } else if (args.length > 0) {
            Thread.sleep(Long.parseLong(args[0]));
        }
        int iterations = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        long workloadStarted = System.nanoTime();
        try {
            if (args.length > 1 && args[1].equals("virtual")) {
                var thread = Thread.ofVirtual().start(() -> {
                    try { exercise(iterations); } catch (Exception failure) { throw new RuntimeException(failure); }
                });
                thread.join();
                return;
            }
            exercise(iterations);
        } finally {
            System.out.println("agent-workload: workloadNs=" + (System.nanoTime() - workloadStarted));
        }
    }

    private static void exercise(int iterations) throws Exception {
        Path file = Files.createTempFile("mazewall-stack-agent", ".txt");
        try {
            for (int i = 0; i < iterations; i++) {
                writeWorkload(file);
                readWorkload(file);
            }
        } finally {
            deleteWorkload(file);
        }
    }

    private static void writeWorkload(Path file) throws Exception {
        Files.writeString(file, "stack attribution smoke test");
    }

    private static void readWorkload(Path file) throws Exception {
        if (!Files.readString(file).equals("stack attribution smoke test")) {
            throw new AssertionError("unexpected file contents");
        }
    }

    private static void deleteWorkload(Path file) throws Exception {
        Files.deleteIfExists(file);
    }
}
