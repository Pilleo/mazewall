import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Split-topology coverage target. Its JSON is declarative only and never reports recovery. */
public final class AgentCoverageWorkload {
    private static final int PATH_COUNT = 72;

    public static void main(String[] args) throws Exception {
        if (args.length != 8 || !args[0].startsWith("gate:")) {
            throw new IllegalArgumentException("usage: gate:<path> <oracle> <socket> <marker> <offset> <session> defer-terminal <host-pid>");
        }
        Path oracle = Path.of(args[1]);
        int operations = Integer.parseInt(System.getenv().getOrDefault("TIER_E_COVERAGE_OPERATIONS", "72"));
        long seed = Long.parseLong(System.getenv().getOrDefault("TIER_E_COVERAGE_SEED", "23063"));
        List<Integer> schedule = schedule(operations, seed);
        publishOracle(oracle, schedule, seed);
        Path gate = Path.of(args[0].substring(5));
        while (!Files.exists(gate)) Thread.sleep(1L);
        long pid = Long.parseLong(Files.readString(Path.of(args[7])).trim());
        if ("1".equals(System.getenv().getOrDefault("TIER_E_SPLIT_COVERAGE_BASELINE", "0"))) {
            runWorkload(schedule);
            System.out.println("tier-e-coverage-target: operations=" + schedule.size());
            publishCpuSample(pid);
            return;
        }
        try (SocketChannel socket = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            socket.connect(UnixDomainSocketAddress.of(args[2]));
            DataInputStream input = new DataInputStream(Channels.newInputStream(socket));
            DataOutputStream output = new DataOutputStream(Channels.newOutputStream(socket));
            String emissionMode = System.getenv().getOrDefault("TIER_E_SPLIT_EMISSION_MODE", "full-stream");
            String markerBackend = System.getenv().getOrDefault("TIER_E_SPLIT_MARKER_BACKEND", "uprobe");
            if (!markerBackend.equals("uprobe") && !markerBackend.equals("usdt")) {
                throw new IllegalArgumentException("unsupported marker backend: " + markerBackend);
            }
            String attach = rpc(output, input, "ATTACH " + pid + " " + markerBackend + " " + args[3] + " " + args[4] + " " + args[5] + " " + emissionMode);
            if (!attach.startsWith("OK")) throw new IllegalStateException("attach failed: " + attach);
            System.out.println("tier-e-workflow: " + rpc(output, input, "STATUS").trim());
            runWorkload(schedule);
            System.out.println("tier-e-coverage-target: operations=" + schedule.size());
            System.out.println("tier-e-workflow: capture complete; terminal control deferred");
        }
        publishCpuSample(pid);
    }

    private static void runWorkload(List<Integer> schedule) throws Exception {
        Path file = Files.createTempFile("mazewall-coverage", ".txt");
        try {
            Files.writeString(file, "coverage");
            long started = System.nanoTime();
            for (int index = 0; index < schedule.size(); index++) {
                dispatch(schedule.get(index), file);
                pace(started, index + 1);
            }
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void publishCpuSample(long hostPid) throws Exception {
        long cpuNs = ProcessHandle.current().info().totalCpuDuration()
            .orElseThrow(() -> new IllegalStateException("target process CPU duration is unavailable"))
            .toNanos();
        if (cpuNs < 0L) throw new IllegalStateException("negative target CPU duration: " + cpuNs);
        long pid = ProcessHandle.current().pid();
        if (pid <= 0L || hostPid <= 0L) throw new IllegalStateException("invalid target pid binding");
        String json = "{\"schema_version\":1,\"version\":1,\"pid\":" + pid + ",\"host_pid\":" + hostPid
            + ",\"starttime\":" + selfStarttime()
            + ",\"sample_boundary\":\"after_workload_before_exit\",\"unit\":\"ns\",\"target_cpu_ns\":" + cpuNs + "}";
        Path output = Path.of(System.getenv().getOrDefault("TIER_E_TARGET_CPU_OUT", "target-cpu.json")).toAbsolutePath();
        Path temporary = Files.createTempFile(output.getParent(), ".target-cpu.", ".tmp");
        try {
            Files.writeString(temporary, json);
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static long selfStarttime() throws Exception {
        String stat = Files.readString(Path.of("/proc/self/stat"));
        int close = stat.lastIndexOf(')');
        if (close < 0) throw new IllegalStateException("malformed /proc/self/stat");
        String[] fields = stat.substring(close + 1).trim().split("\\s+");
        if (fields.length < 20) throw new IllegalStateException("short /proc/self/stat");
        long starttime = Long.parseLong(fields[19]);
        if (starttime <= 0L) throw new IllegalStateException("invalid target starttime: " + starttime);
        return starttime;
    }

    private static void publishOracle(Path output, List<Integer> schedule, long seed) throws Exception {
        int[] counts = new int[PATH_COUNT];
        for (int path : schedule) counts[path]++;
        StringBuilder json = new StringBuilder("{\"schema_version\":1,\"seed\":").append(seed).append(",\"declared_operations\":")
            .append(schedule.size()).append(",\"paths\":[");
        for (int path = 0; path < PATH_COUNT; path++) {
            if (path != 0) json.append(',');
            String category = path < 24 ? "common" : path < 48 ? "infrequent" : "singleton";
            json.append("{\"path\":").append(path).append(",\"category\":\"").append(category)
                .append("\",\"frequency\":").append(counts[path]).append(",\"method\":\"path")
                .append(String.format("%02d", path)).append("\",\"syscall_number\":257}");
        }
        json.append("]}");
        Path temporary = Files.createTempFile(output.getParent(), ".oracle.", ".tmp");
        try {
            Files.writeString(temporary, json.toString());
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static List<Integer> schedule(int operations, long seed) {
        if (operations < PATH_COUNT || operations % 24 != 0) {
            throw new IllegalArgumentException("coverage operations must be at least 72 and divisible by 24");
        }
        int infrequent = operations == 75_000 ? 124 : Math.max(1, (operations / 24 - 1) / 25);
        int common = operations / 24 - infrequent - 1;
        if (common <= 0) throw new IllegalArgumentException("coverage operation count is too small");
        List<Integer> repeated = new ArrayList<>();
        for (int path = 0; path < 24; path++) for (int i = 0; i < common; i++) repeated.add(path);
        for (int path = 24; path < 48; path++) for (int i = 0; i < infrequent; i++) repeated.add(path);
        Collections.shuffle(repeated, new Random(seed));
        List<Integer> singletons = new ArrayList<>();
        for (int path = 48; path < 72; path++) singletons.add(path);
        Collections.shuffle(singletons, new Random(seed ^ 0x5a5aL));
        List<Integer> result = new ArrayList<>(operations);
        int repeatedIndex = 0;
        for (int position = 0; position < operations; position++) {
            if (position % ((operations - 1) / 23) == 0 && singletons.size() > 0) result.add(singletons.remove(0));
            else result.add(repeated.get(repeatedIndex++));
        }
        return result;
    }

    private static String rpc(DataOutputStream output, DataInputStream input, String command) throws Exception {
        output.writeUTF(command); output.flush(); return input.readUTF();
    }
    private static void pace(long started, int completed) throws InterruptedException {
        long remaining = started + completed * 800_000L - System.nanoTime();
        if (remaining > 0L) Thread.sleep(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
    }
    private static void operation(Path file) throws Exception { Files.readString(file); }
    private static void dispatch(int path, Path file) throws Exception {
        switch (path) {
            case 0 -> path00(file);
            case 1 -> path01(file);
            case 2 -> path02(file);
            case 3 -> path03(file);
            case 4 -> path04(file);
            case 5 -> path05(file);
            case 6 -> path06(file);
            case 7 -> path07(file);
            case 8 -> path08(file);
            case 9 -> path09(file);
            case 10 -> path10(file);
            case 11 -> path11(file);
            case 12 -> path12(file);
            case 13 -> path13(file);
            case 14 -> path14(file);
            case 15 -> path15(file);
            case 16 -> path16(file);
            case 17 -> path17(file);
            case 18 -> path18(file);
            case 19 -> path19(file);
            case 20 -> path20(file);
            case 21 -> path21(file);
            case 22 -> path22(file);
            case 23 -> path23(file);
            case 24 -> path24(file);
            case 25 -> path25(file);
            case 26 -> path26(file);
            case 27 -> path27(file);
            case 28 -> path28(file);
            case 29 -> path29(file);
            case 30 -> path30(file);
            case 31 -> path31(file);
            case 32 -> path32(file);
            case 33 -> path33(file);
            case 34 -> path34(file);
            case 35 -> path35(file);
            case 36 -> path36(file);
            case 37 -> path37(file);
            case 38 -> path38(file);
            case 39 -> path39(file);
            case 40 -> path40(file);
            case 41 -> path41(file);
            case 42 -> path42(file);
            case 43 -> path43(file);
            case 44 -> path44(file);
            case 45 -> path45(file);
            case 46 -> path46(file);
            case 47 -> path47(file);
            case 48 -> path48(file);
            case 49 -> path49(file);
            case 50 -> path50(file);
            case 51 -> path51(file);
            case 52 -> path52(file);
            case 53 -> path53(file);
            case 54 -> path54(file);
            case 55 -> path55(file);
            case 56 -> path56(file);
            case 57 -> path57(file);
            case 58 -> path58(file);
            case 59 -> path59(file);
            case 60 -> path60(file);
            case 61 -> path61(file);
            case 62 -> path62(file);
            case 63 -> path63(file);
            case 64 -> path64(file);
            case 65 -> path65(file);
            case 66 -> path66(file);
            case 67 -> path67(file);
            case 68 -> path68(file);
            case 69 -> path69(file);
            case 70 -> path70(file);
            case 71 -> path71(file);
            default -> throw new IllegalArgumentException("unknown path " + path);
        }
    }
    private static void path00(Path file) throws Exception { operation(file); }
    private static void path01(Path file) throws Exception { operation(file); }
    private static void path02(Path file) throws Exception { operation(file); }
    private static void path03(Path file) throws Exception { operation(file); }
    private static void path04(Path file) throws Exception { operation(file); }
    private static void path05(Path file) throws Exception { operation(file); }
    private static void path06(Path file) throws Exception { operation(file); }
    private static void path07(Path file) throws Exception { operation(file); }
    private static void path08(Path file) throws Exception { operation(file); }
    private static void path09(Path file) throws Exception { operation(file); }
    private static void path10(Path file) throws Exception { operation(file); }
    private static void path11(Path file) throws Exception { operation(file); }
    private static void path12(Path file) throws Exception { operation(file); }
    private static void path13(Path file) throws Exception { operation(file); }
    private static void path14(Path file) throws Exception { operation(file); }
    private static void path15(Path file) throws Exception { operation(file); }
    private static void path16(Path file) throws Exception { operation(file); }
    private static void path17(Path file) throws Exception { operation(file); }
    private static void path18(Path file) throws Exception { operation(file); }
    private static void path19(Path file) throws Exception { operation(file); }
    private static void path20(Path file) throws Exception { operation(file); }
    private static void path21(Path file) throws Exception { operation(file); }
    private static void path22(Path file) throws Exception { operation(file); }
    private static void path23(Path file) throws Exception { operation(file); }
    private static void path24(Path file) throws Exception { operation(file); }
    private static void path25(Path file) throws Exception { operation(file); }
    private static void path26(Path file) throws Exception { operation(file); }
    private static void path27(Path file) throws Exception { operation(file); }
    private static void path28(Path file) throws Exception { operation(file); }
    private static void path29(Path file) throws Exception { operation(file); }
    private static void path30(Path file) throws Exception { operation(file); }
    private static void path31(Path file) throws Exception { operation(file); }
    private static void path32(Path file) throws Exception { operation(file); }
    private static void path33(Path file) throws Exception { operation(file); }
    private static void path34(Path file) throws Exception { operation(file); }
    private static void path35(Path file) throws Exception { operation(file); }
    private static void path36(Path file) throws Exception { operation(file); }
    private static void path37(Path file) throws Exception { operation(file); }
    private static void path38(Path file) throws Exception { operation(file); }
    private static void path39(Path file) throws Exception { operation(file); }
    private static void path40(Path file) throws Exception { operation(file); }
    private static void path41(Path file) throws Exception { operation(file); }
    private static void path42(Path file) throws Exception { operation(file); }
    private static void path43(Path file) throws Exception { operation(file); }
    private static void path44(Path file) throws Exception { operation(file); }
    private static void path45(Path file) throws Exception { operation(file); }
    private static void path46(Path file) throws Exception { operation(file); }
    private static void path47(Path file) throws Exception { operation(file); }
    private static void path48(Path file) throws Exception { operation(file); }
    private static void path49(Path file) throws Exception { operation(file); }
    private static void path50(Path file) throws Exception { operation(file); }
    private static void path51(Path file) throws Exception { operation(file); }
    private static void path52(Path file) throws Exception { operation(file); }
    private static void path53(Path file) throws Exception { operation(file); }
    private static void path54(Path file) throws Exception { operation(file); }
    private static void path55(Path file) throws Exception { operation(file); }
    private static void path56(Path file) throws Exception { operation(file); }
    private static void path57(Path file) throws Exception { operation(file); }
    private static void path58(Path file) throws Exception { operation(file); }
    private static void path59(Path file) throws Exception { operation(file); }
    private static void path60(Path file) throws Exception { operation(file); }
    private static void path61(Path file) throws Exception { operation(file); }
    private static void path62(Path file) throws Exception { operation(file); }
    private static void path63(Path file) throws Exception { operation(file); }
    private static void path64(Path file) throws Exception { operation(file); }
    private static void path65(Path file) throws Exception { operation(file); }
    private static void path66(Path file) throws Exception { operation(file); }
    private static void path67(Path file) throws Exception { operation(file); }
    private static void path68(Path file) throws Exception { operation(file); }
    private static void path69(Path file) throws Exception { operation(file); }
    private static void path70(Path file) throws Exception { operation(file); }
    private static void path71(Path file) throws Exception { operation(file); }
}
