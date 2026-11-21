package consumer.jpms;

import io.mazewall.lockdown.Mazewall;
import io.mazewall.lockdown.ProcessInstallation;
import io.mazewall.lockdown.ProcessPolicy;
import io.mazewall.lockdown.RuntimeProfile;
import java.io.IOException;

public final class JpmsLockdownConsumer {
    private JpmsLockdownConsumer() {}

    public static void main(String[] args) throws Exception {
        Process pre = new ProcessBuilder("/bin/echo", "pre-jpms-ok").start();
        if (pre.waitFor() != 0) {
            throw new AssertionError("Pre-install /bin/echo exited with " + pre.exitValue());
        }

        ProcessInstallation installed = Mazewall.installOnProcess(
            ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT)
        );
        if (installed.filterDepth() != 1) {
            throw new AssertionError("Expected filterDepth == 1, got " + installed.filterDepth());
        }

        try {
            new ProcessBuilder("/bin/echo", "blocked-jpms").start();
            throw new AssertionError("ProcessBuilder unexpectedly succeeded in JPMS module");
        } catch (IOException expected) {
            String msg = String.valueOf(expected.getMessage());
            if (!msg.contains("Operation not permitted") && !msg.contains("error=1") && !msg.contains("errno=1")) {
                throw new AssertionError("Expected EPERM message in JPMS module, got: " + msg, expected);
            }
        }
        System.out.println("JAVA_JPMS_LOCKDOWN_OK");
    }
}
