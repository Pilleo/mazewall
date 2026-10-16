/** Ordinary-user process used only to validate preflight and PID visibility. */
public final class PreflightTarget {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("ready " + ProcessHandle.current().pid());
        Thread.sleep(120_000);
    }
}
