package consumer.classpath;

import io.mazewall.lockdown.LockdownPreset;
import io.mazewall.lockdown.LockdownSyscall;
import io.mazewall.lockdown.Mazewall;
import io.mazewall.lockdown.MazewallInstallationException;
import io.mazewall.lockdown.ProcessInstallation;
import io.mazewall.lockdown.ProcessPolicy;
import io.mazewall.lockdown.RuntimeProfile;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

public final class JavaLockdownConsumer {
    private JavaLockdownConsumer() {}

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        switch (mode) {
            case "enforce" -> runEnforce();
            case "expect-install-failure" -> runExpectFailure();
            case "verify-encapsulation" -> verifyEncapsulation(args[1]);
            default -> throw new IllegalArgumentException("Unknown mode: " + mode);
        }
    }

    private static void runEnforce() throws Exception {
        Process pre = new ProcessBuilder("/bin/echo", "pre-lockdown-ok").start();
        if (pre.waitFor() != 0) {
            throw new AssertionError("Pre-install /bin/echo exited with " + pre.exitValue());
        }

        ProcessInstallation first = Mazewall.installOnProcess(
            ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT)
        );
        if (first.filterDepth() != 1) {
            throw new AssertionError("Expected filterDepth == 1, got " + first.filterDepth());
        }
        if (!"amd64".equals(first.architecture()) && !"aarch64".equals(first.architecture())) {
            throw new AssertionError("Unexpected architecture: " + first.architecture());
        }

        ProcessInstallation second = Mazewall.installOnProcess(
            ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT)
        );
        if (second.filterDepth() != 1) {
            throw new AssertionError("Expected idempotent filterDepth == 1, got " + second.filterDepth());
        }

        ProcessPolicy elastic = ProcessPolicy.elasticsearch();
        ProcessPolicy openSearchCustom = ProcessPolicy.fromPreset(LockdownPreset.OPENSEARCH)
            .block(LockdownSyscall.BPF)
            .unblock(LockdownSyscall.PTRACE);
        if (elastic.blockedSyscalls().size() != 4 || !openSearchCustom.blockedSyscalls().contains(LockdownSyscall.BPF)
            || openSearchCustom.blockedSyscalls().contains(LockdownSyscall.PTRACE)) {
            throw new AssertionError("Unexpected preset customization: " + openSearchCustom.blockedSyscalls());
        }
        ProcessInstallation narrowed = Mazewall.installOnProcess(openSearchCustom);
        if (narrowed.filterDepth() != 2) {
            throw new AssertionError("Expected narrowed filterDepth == 2, got " + narrowed.filterDepth());
        }

        assertProcessBlocked("main");
        try (var vtExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            vtExecutor.submit(() -> {
                assertProcessBlocked("virtual-thread");
                return null;
            }).get();
        } catch (ExecutionException e) {
            throw new AssertionError("Virtual-thread lockdown assertion failed", e.getCause());
        }
        System.out.println("JAVA_CLASSPATH_LOCKDOWN_OK");
    }

    private static void runExpectFailure() {
        try {
            Mazewall.installOnProcess(ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT));
            throw new AssertionError("Expected MazewallInstallationException");
        } catch (MazewallInstallationException expected) {
            System.out.println("JAVA_EXPECTED_FAILURE_OK:" + expected.getMessage());
        }
    }

    private static void assertProcessBlocked(String label) {
        try {
            new ProcessBuilder("/bin/echo", "should-fail-" + label).start();
            throw new AssertionError("ProcessBuilder unexpectedly succeeded on " + label);
        } catch (IOException expected) {
            String msg = String.valueOf(expected.getMessage());
            if (!msg.contains("Operation not permitted") && !msg.contains("error=1") && !msg.contains("errno=1")) {
                throw new AssertionError("Expected EPERM on " + label + ", got: " + msg, expected);
            }
        }
    }

    private static void verifyEncapsulation(String compileClasspath) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new AssertionError("JDK compiler required for encapsulation verification");
        }
        List<String> snippets = List.of(
            """
            import io.mazewall.lockdown.ProcessInstallation;
            public class Probe {
                public static void main(String[] a) { new ProcessInstallation(1, "amd64"); }
            }
            """,
            """
            import io.mazewall.lockdown.ProcessPolicy;
            import io.mazewall.lockdown.RuntimeProfile;
            public class Probe {
                public static void main(String[] a) { new ProcessPolicy(RuntimeProfile.HOTSPOT_JIT, null); }
            }
            """,
            """
            import io.mazewall.lockdown.ProcessPolicy;
            import io.mazewall.lockdown.RuntimeProfile;
            public class Probe {
                public static void main(String[] a) {
                    ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT).getRuntime$io_mazewall_mazewall_lockdown();
                }
            }
            """
        );
        Path outDir = Files.createTempDirectory("lockdown-encap-");
        try {
            for (int i = 0; i < snippets.size(); i++) {
                String code = snippets.get(i);
                var diagnostics = new DiagnosticCollector<JavaFileObject>();
                var sourceObj = new SimpleJavaFileObject(URI.create("string:///Probe.java"), JavaFileObject.Kind.SOURCE) {
                    @Override
                    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                        return code;
                    }
                };
                Path classOut = Files.createDirectories(outDir.resolve("case-" + i));
                try (var fm = compiler.getStandardFileManager(diagnostics, null, null)) {
                    boolean compiled = Boolean.TRUE.equals(
                        compiler.getTask(
                            null,
                            fm,
                            diagnostics,
                            List.of("-classpath", compileClasspath, "-d", classOut.toString(), "-proc:none"),
                            null,
                            List.of(sourceObj)
                        ).call()
                    );
                    long errors = diagnostics.getDiagnostics().stream()
                        .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                        .count();
                    if (compiled || errors == 0) {
                        throw new AssertionError("Expected internal encapsulation failure for probe " + i + ": " + diagnostics.getDiagnostics());
                    }
                }
            }
        } finally {
            try (var walk = Files.walk(outDir)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        System.out.println("JAVA_ENCAPSULATION_OK");
    }
}
