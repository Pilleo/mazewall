import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/** Finite ordinary-user benchmark; no injected reference profiler or agent. */
public final class BenchmarkFixture {
    static void leaf(String file) throws Exception { try (var in = new FileInputStream(file)) { in.read(); } }
    static void c1(String f) throws Exception { leaf(f); }
    static void c2(String f) throws Exception { c1(f); }
    static void c3(String f) throws Exception { c2(f); }
    static void c4(String f) throws Exception { c3(f); }
    static void c5(String f) throws Exception { c4(f); }
    static void c6(String f) throws Exception { c5(f); }
    static void c7(String f) throws Exception { c6(f); }
    static void route0(String f) throws Exception { leaf(f); }
    static void route1(String f) throws Exception { c1(f); }
    static void route2(String f) throws Exception { c2(f); }
    static void route3(String f) throws Exception { c3(f); }
    static void route4(String f) throws Exception { c4(f); }
    static void route5(String f) throws Exception { c5(f); }
    static void route6(String f) throws Exception { c6(f); }
    static void route7(String f) throws Exception { c7(f); }
    static void call(int route, String f) throws Exception {
        switch (route) {
            case 0 -> route0(f); case 1 -> route1(f); case 2 -> route2(f); case 3 -> route3(f);
            case 4 -> route4(f); case 5 -> route5(f); case 6 -> route6(f); case 7 -> route7(f);
            default -> throw new IllegalArgumentException("route");
        }
    }
    static long percentile(long[] sorted, double fraction) { return sorted[(int)Math.ceil(sorted.length * fraction) - 1]; }
    public static void main(String[] args) throws Exception {
        if (args.length != 7) throw new IllegalArgumentException("directory run operations warmup diversity pause_ns post_attach_warmup");
        Path dir = Path.of(args[0]); String run = args[1]; int n = Integer.parseInt(args[2]);
        int warmup = Integer.parseInt(args[3]), diversity = Integer.parseInt(args[4]); long pause = Long.parseLong(args[5]);
        int preparation = Integer.parseInt(args[6]);
        if (!run.matches("[a-f0-9]{16}") || n < 1 || n > 100000 || warmup < 0 || warmup > 100000 || (diversity != 1 && diversity != 8) || pause < 0 || pause > 10000000) throw new IllegalArgumentException("bounded workload");
        if (preparation < 0 || preparation > 10000 || (preparation > 0 && preparation < diversity)) throw new IllegalArgumentException("bounded preparation");
        String[] files = new String[n];
        for (int i=0;i<n;i++) { files[i] = dir.resolve(String.format("mw-bench-%s-%06d-r%d",run,i,i%diversity)).toString(); Files.write(Path.of(files[i]), new byte[]{1}); }
        String[] preparationFiles = new String[preparation];
        for (int i=0;i<preparation;i++) { preparationFiles[i] = dir.resolve(String.format("mw-ready-%s-%06d-r%d",run,i,i%diversity)).toString(); Files.write(Path.of(preparationFiles[i]),new byte[]{1}); }
        for (int i=0;i<warmup;i++) call(i%diversity, files[i%n]);
        System.out.println("ready " + ProcessHandle.current().pid()); System.out.flush();
        if (preparation > 0) {
            while (!Files.exists(dir.resolve("prepare"))) Thread.sleep(2);
            long preparationStart = System.nanoTime();
            for (int i=0;i<preparation;i++) { call(i%diversity,preparationFiles[i]); TimeUnit.NANOSECONDS.sleep(500000); }
            Files.writeString(dir.resolve("preparation-target.json"),String.format("{\"successful_operations\":%d,\"elapsed_ns\":%d,\"pause_ns\":500000}\n",preparation,System.nanoTime()-preparationStart));
        }
        while (!Files.exists(dir.resolve("gate"))) Thread.sleep(2);
        long[] latency = new long[n]; long start = System.nanoTime();
        for (int i=0;i<n;i++) { long before = System.nanoTime(); call(i%diversity,files[i]); latency[i] = System.nanoTime()-before; if (pause != 0) TimeUnit.NANOSECONDS.sleep(pause); }
        long elapsed = System.nanoTime()-start; Arrays.sort(latency);
        String status = Files.readString(Path.of("/proc/self/status")); long uid = -1;
        for (String line : status.split("\n")) if (line.startsWith("Uid:")) uid = Long.parseLong(line.trim().split("\\s+")[1]);
        String json = String.format("{\"run\":\"%s\",\"pid\":%d,\"uid\":%d,\"operations\":%d,\"successful_operations\":%d,\"warmup\":%d,\"post_attach_warmup\":%d,\"diversity\":%d,\"pause_ns\":%d,\"elapsed_ns\":%d,\"p50_ns\":%d,\"p95_ns\":%d,\"p99_ns\":%d,\"max_ns\":%d}\n", run,ProcessHandle.current().pid(),uid,n,n,warmup,preparation,diversity,pause,elapsed,percentile(latency,.5),percentile(latency,.95),percentile(latency,.99),latency[n-1]);
        Files.writeString(dir.resolve("benchmark-target.json"),json);
        System.out.println("done " + ProcessHandle.current().pid()); System.out.flush();
        while (!Files.exists(dir.resolve("finish"))) Thread.sleep(2);
    }
}
