import java.nio.file.Files;
import java.nio.file.Path;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;

public final class AgentWorkload {
    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("control")) {
            runTerminalControl(args);
            return;
        }
        if (args.length > 0 && args[0].equals("benchmark")) {
            runBenchmark(args);
            return;
        }
        if (args.length > 0 && args[0].startsWith("gate:")) {
            var gate = Path.of(args[0].substring(5));
            while (!Files.exists(gate)) Thread.sleep(1);
        } else if (args.length > 0) {
            Thread.sleep(Long.parseLong(args[0]));
        }
        int iterations = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        long workloadStarted = System.nanoTime();
        try {
            if (args.length > 6) {
                runDaemonWorkflow(args, iterations);
                return;
            }
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

    private static void runBenchmark(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("usage: benchmark <warmups> <batches> <iterations>");
        }
        int warmups = Integer.parseInt(args[1]);
        int batches = Integer.parseInt(args[2]);
        int iterations = Integer.parseInt(args[3]);
        if (warmups < 0 || batches <= 0 || iterations <= 0) {
            throw new IllegalArgumentException("benchmark counts must be positive");
        }
        for (int index = 0; index < warmups; index++) exercise(iterations);
        for (int index = 1; index <= batches; index++) {
            long started = System.nanoTime();
            exercise(iterations);
            System.out.println("agent-workload-batch: index=" + index + " durationNs=" + (System.nanoTime() - started));
        }
    }

    private static void runDaemonWorkflow(String[] args, int iterations) throws Exception {
        String socketPath = args[3];
        String markerPath = args[4];
        String markerOffset = args[5];
        String sessionTag = args[6];
        boolean deferTerminalControl = args.length > 7 && args[7].equals("defer-terminal");
        long registeredPid = args.length > 8
            ? Long.parseLong(Files.readString(Path.of(args[8])).trim())
            : ProcessHandle.current().pid();
        try (SocketChannel socket = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            socket.connect(UnixDomainSocketAddress.of(socketPath));
            var input = new DataInputStream(Channels.newInputStream(socket));
            var output = new DataOutputStream(Channels.newOutputStream(socket));
            String attach = rpc(output, input, "ATTACH " + registeredPid + " uprobe " + markerPath + " " + markerOffset + " " + sessionTag);
            checkReply("attach", attach);
            System.out.println("tier-e-workflow: " + attach.trim());
            System.out.println("tier-e-workflow: " + rpc(output, input, "STATUS").trim());
            exercise(iterations);
            String simulatedReuseMarker = System.getenv("TIER_E_SPLIT_SIMULATE_PID_REUSE_MARKER");
            if (simulatedReuseMarker != null) {
                Files.writeString(Path.of(simulatedReuseMarker), "reused");
                // The collector's 100 ms lifetime watcher must see the
                // simulated new incarnation while this JVM is still alive.
                Thread.sleep(250L);
            }
            System.out.println("tier-e-workflow: " + rpc(output, input, "STATUS").trim());
            String reuseReady = System.getenv("TIER_E_SPLIT_REAL_REUSE_READY");
            if (reuseReady != null) {
                Files.writeString(Path.of(reuseReady), "attached\n");
                while (!Files.exists(Path.of(reuseReady + ".release"))) Thread.sleep(10);
            }
            if (deferTerminalControl) {
                System.out.println("tier-e-workflow: capture complete; terminal control deferred");
                return;
            }
            checkReply("detach", rpc(output, input, "DETACH"));
            System.out.println("tier-e-workflow: detached");
            checkReply("shutdown", rpc(output, input, "SHUTDOWN"));
            System.out.println("tier-e-workflow: shutdown");
        }
        if (!deferTerminalControl && args.length > 7) {
            Path terminalGate = Path.of(args[7]);
            while (!Files.exists(terminalGate)) Thread.sleep(1);
            System.out.println("tier-e-workflow: terminal release");
        }
    }

    private static void runTerminalControl(String[] args) throws Exception {
        if (args.length != 2 && (args.length != 3 || !args[2].equals("shutdown"))) {
            throw new IllegalArgumentException("usage: control <unix-socket> [shutdown]");
        }
        try (SocketChannel socket = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            socket.connect(UnixDomainSocketAddress.of(args[1]));
            var input = new DataInputStream(Channels.newInputStream(socket));
            var output = new DataOutputStream(Channels.newOutputStream(socket));
            if (args.length == 2) {
                checkReply("detach", rpc(output, input, "DETACH"));
                System.out.println("tier-e-control: detached");
            }
            checkReply("shutdown", rpc(output, input, "SHUTDOWN"));
            System.out.println("tier-e-control: shutdown");
        }
    }

    private static String rpc(DataOutputStream output, DataInputStream input, String command) throws Exception {
        output.writeUTF(command);
        output.flush();
        return input.readUTF();
    }

    private static void checkReply(String operation, String reply) {
        if (!reply.startsWith("OK")) throw new IllegalStateException(operation + " failed: " + reply);
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
