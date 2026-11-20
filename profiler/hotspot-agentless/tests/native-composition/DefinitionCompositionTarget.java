import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.LockSupport;

// A child of the selected native test binary, not an arbitrary existing target.
class DefinitionCompositionTarget {
    public static void main(String[] args) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("ready");
        for (String line; (line = input.readLine()) != null;) {
            if (line.equals("exit")) return;
            int count = Integer.parseInt(line);
            if (count < 1 || count > 32) throw new IllegalArgumentException(line);
            for (int i = 0; i < count; i++) {
                try (var file = Files.newInputStream(Path.of("/proc/self/stat"))) {
                    if (file.read() < 0) throw new IllegalStateException("empty stat");
                }
            }
            System.out.println("done");
        }
        // Endpoint loss is retention, never the coordinator's explicit exit.
        for (;;) {
            LockSupport.park();
            Thread.interrupted();
        }
    }
}
