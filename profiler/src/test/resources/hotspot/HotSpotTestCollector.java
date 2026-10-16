import java.nio.file.Files;
import java.nio.file.Path;

/** Test-only child: exercises real stdin/stdout/stderr and exit, never attaches native probes. */
class HotSpotTestCollector {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        var rows = Files.readAllLines(Path.of(args[1]));
        if (mode.equals("early")) System.exit(7);
        if (mode.equals("stderr")) System.err.write(new byte[1024 * 1024]);
        System.out.println(rows.get(0));
        System.out.flush();
        if (mode.equals("no-ready")) Thread.sleep(60_000);
        if (mode.equals("denied")) {
            System.out.println(rows.get(5));
            System.exit(1);
        }
        System.out.println(rows.get(1));
        System.out.println(rows.get(2));
        System.out.flush();
        String command = new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();
        if (!"stop".equals(command)) System.exit(9);
        if (mode.equals("hang")) Thread.sleep(60_000);
        if (mode.equals("malformed")) System.out.println("broken");
        else {
            Thread.sleep(50);
            System.out.println(rows.get(3));
            System.out.println(rows.get(4));
        }
        System.out.flush();
    }
}
