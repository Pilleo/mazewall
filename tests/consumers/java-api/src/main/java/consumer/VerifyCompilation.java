package consumer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

/** Runs diagnostic assertions inside the selected consumer JDK, not the Gradle daemon. */
public final class VerifyCompilation {
    private VerifyCompilation() {}

    public static void main(String[] args) throws Exception {
        System.out.println("Fixture javac: " + Runtime.version() + " at " + System.getProperty("java.home"));
        if (args[0].equals("callable")) {
            verify(args, "UncastCallable", null);
        } else if (args[0].equals("scope")) {
            verify(args, "ProcessFilesystem", "compiler.err.cant.resolve.location.args");
            verify(args, "WorkerAsProcess", "compiler.err.prob.found.req");
        } else {
            throw new IllegalArgumentException("Unknown fixture: " + args[0]);
        }
    }

    private static void verify(String[] args, String name, String expectedCode) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new AssertionError("Consumer must run on a full JDK");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var output = Files.createDirectories(Path.of(args[3], name));
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            var source = manager.getJavaFileObjects(Path.of(args[2], name + ".java").toFile());
            boolean compiled = compiler.getTask(null, manager, diagnostics,
                List.of("-classpath", args[1], "-d", output.toString(), "-proc:none"), null, source).call();
            var errors = diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR).toList();
            errors.forEach(d -> System.out.println(name + ": " + d.getCode() + ": " + d.getMessage(null)));
            boolean expected = expectedCode == null ? compiled && errors.isEmpty()
                : !compiled && errors.size() == 1 && errors.getFirst().getCode().equals(expectedCode)
                    && errors.getFirst().getSource().getName().endsWith(name + ".java");
            if (!expected) throw new AssertionError("Unexpected compilation result for " + name + ": " + diagnostics.getDiagnostics());
            System.out.println(name + ": expected " + (expectedCode == null ? "success" : "scope rejection") + " passed.");
        }
    }
}
