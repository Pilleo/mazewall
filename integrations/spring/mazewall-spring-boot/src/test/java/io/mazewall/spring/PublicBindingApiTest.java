package io.mazewall.spring;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Foreign-package Java compilation against MAIN public API only; no test package privileges or native installation. */
class PublicBindingApiTest {
    @TempDir Path root;

    @Test void externalJavaAcceptsGenuineOwnerApiAndRejectsEveryUncontainedRequiredBindingFactory() throws Exception {
        String imports = """
                package external;
                import io.mazewall.spring.*;
                import io.mazewall.enforcer.api.*;
                import io.mazewall.enforcer.api.execution.*;
                import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
                import java.time.Duration;
                """;
        assertTrue(compile("ProductionApi", imports + """
                public class ProductionApi {
                    public PreparedOperationResources resolve(RuntimeProfile profile, Policy<PolicyScope.ThreadLocalOnly, PolicyState.Uncompiled> policy,
                            Class<?> actualImplementation, java.util.Set<java.nio.file.Path> paths) {
                        return PreparedOperationResources.resolve(profile, policy, actualImplementation, paths);
                    }
                    public InProcessDomain prepare(ManagedRuntime runtime, ReviewedOperationArtifact artifact, DomainLimits limits,
                            PreparedOperationResources resources) {
                        return runtime.prepare(artifact, limits, resources);
                    }
                    public void bind(ManagedRuntime runtime, ReviewedOperationArtifact artifact, PreparedOperationResources resources,
                            ConfigurableListableBeanFactory beans, SpringTransactionGuard guard, DomainLimits limits) {
                        var lifecycle = new DomainLifecycle(runtime, artifact, limits, resources, Duration.ofSeconds(1));
                        lifecycle.start();
                        new SpringOperationBinder(beans, lifecycle);
                        new SpringOperationBinder(beans, lifecycle, guard);
                        SpringOperationBinder.inApprovedCommonDomain(beans, lifecycle, artifact, guard);
                        // S0 structural host characterization remains explicitly uncontained, without required binding.
                        BoundInvocationInterceptor.forTrustedHostCharacterization(body -> body.proceed());
                    }
                }
                """).success(), "Root production API must compile from outside the adapter package");
        List<String> attempts = List.of(
                "SpringOperationBinder.forTrustedHostCharacterization(beans, dispatch, artifact)",
                "SpringOperationBinder.forTrustedHostCharacterization(beans, dispatch, artifact, guard)",
                "SpringOperationBinder.forTrustedHostCharacterizationInCommonDomain(beans, dispatch, artifact, artifact, guard)");
        for (int index = 0; index < attempts.size(); index++) {
            String name = "UncontainedRequiredBinding" + index;
            var result = compile(name, imports + "public class " + name + " {\n"
                    + " @MazewallOperation(\"required\") public String required() { return \"uncontained\"; }\n"
                    + " public Object escape(ConfigurableListableBeanFactory beans, InvocationDispatcher dispatch, ReviewedOperationArtifact artifact, SpringTransactionGuard guard) { return "
                    + attempts.get(index) + "; } }");
            assertFalse(result.success(), "Public API exposed uncontained required-annotation binding: " + attempts.get(index));
            assertTrue(result.diagnostics().contains("not public"), "Must fail specifically at package visibility: " + result.diagnostics());
        }
    }

    record Compilation(boolean success, String diagnostics) {}
    private Compilation compile(String name, String source) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "A real Java compiler is required, never skipped");
        String classpath = System.getProperty("mazewall.spring.publicCompileClasspath");
        assertNotNull(classpath, "Explicit main-only public classpath required");
        Path file = Files.writeString(root.resolve(name + ".java"), source);
        Path output = Files.createDirectories(root.resolve(name));
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, java.util.Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            boolean success = compiler.getTask(null, manager, diagnostics,
                    List.of("-classpath", classpath, "-d", output.toString(), "-proc:none"), null,
                    manager.getJavaFileObjects(file)).call();
            return new Compilation(success, diagnostics.getDiagnostics().toString());
        }
    }
}
