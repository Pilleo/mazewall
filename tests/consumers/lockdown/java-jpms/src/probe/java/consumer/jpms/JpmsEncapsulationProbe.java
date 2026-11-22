package consumer.jpms;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

public final class JpmsEncapsulationProbe {
    private JpmsEncapsulationProbe() {}

    public static void main(String[] args) throws Exception {
        String modulePath = args[0];
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new AssertionError("JDK compiler required for JPMS encapsulation verification");
        }
        Path workDir = Files.createTempDirectory("lockdown-jpms-probe-");
        try {
            Path srcDir = Files.createDirectories(workDir.resolve("src/probe"));
            Path outDir = Files.createDirectories(workDir.resolve("out"));
            Path moduleInfo = Files.writeString(
                workDir.resolve("src/module-info.java"),
                """
                module probe.neg {
                    requires io.mazewall.lockdown;
                }
                """
            );
            Path negClass = Files.writeString(
                srcDir.resolve("Neg.java"),
                """
                package probe;
                import io.mazewall.lockdown.internal.seccomp.ProcessFilterInstaller;
                public class Neg {
                    public static Class<?> ref() { return ProcessFilterInstaller.class; }
                }
                """
            );
            var diagnostics = new DiagnosticCollector<JavaFileObject>();
            try (var fm = compiler.getStandardFileManager(diagnostics, null, null)) {
                var units = fm.getJavaFileObjects(moduleInfo.toFile(), negClass.toFile());
                boolean compiled = Boolean.TRUE.equals(
                    compiler.getTask(
                        null,
                        fm,
                        diagnostics,
                        List.of("--module-path", modulePath, "-d", outDir.toString(), "-proc:none"),
                        null,
                        units
                    ).call()
                );
                long errorCount = diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .count();
                if (compiled || errorCount == 0) {
                    throw new AssertionError("Expected JPMS unexported-package compile failure: " + diagnostics.getDiagnostics());
                }
            }
        } finally {
            try (var walk = Files.walk(workDir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
        System.out.println("JPMS_ENCAPSULATION_OK");
    }
}
