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
    for ((suffix, arguments) in listOf("Legacy" to "value -> null", "Observed" to "value -> null, () -> ExecutionDiagnostics.UNAVAILABLE")) {
        val className = "ConstructorNegative$suffix"
        val negative = directory.resolve("$className.java")
        Files.writeString(negative, """
        package external.consumer;
        import io.mazewall.portal.PreparedPortalOperation;
        import io.mazewall.enforcer.api.execution.ExecutionDiagnostics;
        public final class $className {
            public static PreparedPortalOperation<String, String> forge() {
                return new PreparedPortalOperation<String, String>($arguments);
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
            check(errors.size == 1) { "expected one constructor accessibility rejection, got $errors" }
            val error = errors.single()
            val message = error.getMessage(Locale.ROOT)
            val accessFailure = error.code == "compiler.err.not.def.public.cant.access" ||
                (error.code in setOf("compiler.err.cant.apply.symbol", "compiler.err.cant.apply.symbols") &&
                    "is not public in io.mazewall.portal.PreparedPortalOperation; cannot be accessed from outside package" in message)
            check(accessFailure && "PreparedPortalOperation" in message) {
                "expected actual package-private constructor rejection, got $errors"
            }
            println("EXTERNAL_JAVA_OPERATION_ROOT_OK $suffix ${error.code}")
        }
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
