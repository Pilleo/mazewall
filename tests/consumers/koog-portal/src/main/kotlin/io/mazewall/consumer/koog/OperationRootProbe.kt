package io.mazewall.consumer.koog

import java.nio.file.Files
import java.util.Locale
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider

/** Actual external javac probe against the selected published runtime, with no friend paths. */
fun main() {
    val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "probe requires the pinned JDK compiler" }
    val directory = Files.createTempDirectory("koog-operation-constructor-")
    val negative = directory.resolve("ConstructorNegative.java")
    Files.writeString(negative, """
        package external.consumer;
        import io.mazewall.portal.PreparedPortalOperation;
        public final class ConstructorNegative {
            public static PreparedPortalOperation<String, String> forge() {
                return new PreparedPortalOperation<String, String>(value -> null);
            }
        }
    """.trimIndent())
    val diagnostics = DiagnosticCollector<JavaFileObject>()
    compiler.getStandardFileManager(diagnostics, Locale.ROOT, Charsets.UTF_8).use { manager ->
        val accepted = compiler.getTask(null, manager, diagnostics,
            listOf("-classpath", System.getProperty("java.class.path"), "-d", directory.toString()),
            null, manager.getJavaFileObjects(negative.toFile())).call()
        check(!accepted) { "external Java constructed an arbitrary-submitter PreparedPortalOperation" }
        val errors = diagnostics.diagnostics.filter { it.kind == Diagnostic.Kind.ERROR }
        check(errors.size == 1 && errors.single().code == "compiler.err.not.def.public.cant.access") {
            "expected only constructor accessibility rejection, got $errors"
        }
        check(errors.single().getMessage(Locale.ROOT).contains("PreparedPortalOperation"))
        println("EXTERNAL_JAVA_OPERATION_ROOT_OK ${errors.single().code}")
    }
    val positive = directory.resolve("RuntimePositive.java")
    Files.writeString(positive, """
        package external.consumer;
        import io.mazewall.portal.PreparedPortalOperation;
        import io.mazewall.enforcer.api.execution.InvocationHandle;
        import io.mazewall.enforcer.api.execution.ExecutionDiagnostics;
        public final class RuntimePositive {
            public static InvocationHandle<String> submit(PreparedPortalOperation<String, String> operation, String value) {
                return operation.submit(value);
            }
            public static ExecutionDiagnostics observe(PreparedPortalOperation<String, String> operation) {
                return operation.diagnostics();
            }
        }
    """.trimIndent())
    val positiveDiagnostics = DiagnosticCollector<JavaFileObject>()
    compiler.getStandardFileManager(positiveDiagnostics, Locale.ROOT, Charsets.UTF_8).use { manager ->
        check(compiler.getTask(null, manager, positiveDiagnostics,
            listOf("-classpath", System.getProperty("java.class.path"), "-d", directory.toString()),
            null, manager.getJavaFileObjects(positive.toFile())).call()) {
            "external public submit consumer failed: ${positiveDiagnostics.diagnostics}"
        }
    }
    println("EXTERNAL_JAVA_PUBLIC_SUBMIT_COMPILES")
}
