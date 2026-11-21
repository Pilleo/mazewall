package consumer.jpms;

import io.mazewall.lockdown.LockdownPreset;
import io.mazewall.lockdown.LockdownSyscall;
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

        ProcessPolicy elastic = ProcessPolicy.fromPreset(LockdownPreset.ELASTICSEARCH, RuntimeProfile.HOTSPOT_JIT)
            .block(LockdownSyscall.PIDFD_GETFD)
            .unblock(LockdownSyscall.VFORK);
        if (!elastic.blockedSyscalls().contains(LockdownSyscall.PIDFD_GETFD)
            || elastic.blockedSyscalls().contains(LockdownSyscall.VFORK)) {
            throw new AssertionError("Unexpected JPMS preset syscalls: " + elastic.blockedSyscalls());
        }

        ProcessInstallation installed = Mazewall.installOnProcess(
            ProcessPolicy.openSearch(RuntimeProfile.HOTSPOT_JIT)
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
