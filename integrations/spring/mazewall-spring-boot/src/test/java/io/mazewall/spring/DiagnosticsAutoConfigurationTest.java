package io.mazewall.spring;

import io.mazewall.spring.diagnostics.MazewallDiagnosticsAutoConfiguration;
import io.mazewall.spring.diagnostics.MazewallDiagnosticsEndpoint;
import io.mazewall.enforcer.api.execution.ExecutionDiagnosticsSource;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import static org.junit.jupiter.api.Assertions.*;

/** Host conditional/lifecycle proof; no native installation or containment claim. */
class DiagnosticsAutoConfigurationTest {
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MazewallDiagnosticsAutoConfiguration.class));

    @Test void optionalApisNeverLinkWhenAbsentAndPresenceAloneDoesNotEnableProbes() {
        contexts.withClassLoader(new FilteredClassLoader("org.springframework.boot.actuate", "org.springframework.boot.health"))
                .withPropertyValues("mazewall.diagnostics.enabled=true").run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.containsBean("mazewallHealthIndicator"));
                    assertFalse(context.containsBean("mazewallDiagnosticsEndpoint"));
                });
        contexts.run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.containsBean("mazewallHealthIndicator"));
            assertFalse(context.containsBean("mazewallDiagnosticsEndpoint"));
        });
    }

    @Test void enabledPollingDoesNotInitializeLazyOwnersOrInstallAnyRuntime() {
        contexts.withPropertyValues("mazewall.diagnostics.enabled=true")
                .withUserConfiguration(LazyOwner.class).run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.getBeanFactory().containsSingleton("lazyOwner"));
                    var health = context.getBean("mazewallHealthIndicator", HealthIndicator.class).health();
                    assertEquals(Status.OUT_OF_SERVICE, health.getStatus(), "an enabled probe without prepared sources cannot be ready");
                    assertTrue(context.getBean(MazewallDiagnosticsEndpoint.class).read().isEmpty());
                    assertFalse(context.getBeanFactory().containsSingleton("lazyOwner"), "polling cannot initialize application-owned bootstrap");
                });
    }

    @Test void healthAndEndpointApisAreIndependentlyOptional() {
        contexts.withPropertyValues("mazewall.diagnostics.enabled=true")
                .withClassLoader(new FilteredClassLoader("org.springframework.boot.actuate")).run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.containsBean("mazewallHealthIndicator"));
                    assertFalse(context.containsBean("mazewallDiagnosticsEndpoint"));
                });
        contexts.withPropertyValues("mazewall.diagnostics.enabled=true")
                .withClassLoader(new FilteredClassLoader("org.springframework.boot.health")).run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.containsBean("mazewallHealthIndicator"));
                    assertTrue(context.containsBean("mazewallDiagnosticsEndpoint"));
                });
    }

    @Test void pollingAnExistingLifecycleDoesNotPrepareItOrInventProtectedReadiness() {
        var preparations = new AtomicInteger();
        var lifecycle = DomainLifecycle.forTrustedHostCharacterization(
                () -> { preparations.incrementAndGet(); return body -> body.proceed(); },
                timeout -> io.mazewall.enforcer.api.execution.DrainReport.Complete.INSTANCE,
                Duration.ofSeconds(5));
        contexts.withPropertyValues("mazewall.diagnostics.enabled=true")
                .withBean("ownedDomain", DomainLifecycle.class, () -> lifecycle).run(context -> {
                    var endpoint = context.getBean(MazewallDiagnosticsEndpoint.class);
                    var health = context.getBean("mazewallHealthIndicator", HealthIndicator.class);
                    assertEquals(1, endpoint.read().size());
                    assertEquals(0, preparations.get());
                    assertEquals(Status.OUT_OF_SERVICE, health.health().getStatus());
                    lifecycle.start();
                    assertEquals(Status.OUT_OF_SERVICE, health.health().getStatus(), "uncontained characterization is not native readiness");
                    lifecycle.stop();
                    assertTrue(endpoint.read().getFirst().execution().getRetirementComplete());
                    assertEquals(Status.OUT_OF_SERVICE, health.health().getStatus(), "retirement never becomes ready");
                    assertEquals(1, preparations.get());
                });
    }

    @Test void explicitApplicationHealthOverrideIsPreserved() {
        HealthIndicator application = () -> Health.unknown().build();
        contexts.withPropertyValues("mazewall.diagnostics.enabled=true")
                .withBean("mazewallHealthIndicator", HealthIndicator.class, () -> application).run(context -> {
                    assertNull(context.getStartupFailure());
                    assertSame(application, context.getBean("mazewallHealthIndicator"));
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class LazyOwner {
        // Spring itself eagerly discovers SmartLifecycle; isolate the probe's lazy-source contract.
        @Bean @Lazy ExecutionDiagnosticsSource lazyOwner() {
            throw new AssertionError("diagnostics initialized a lazy application owner");
        }
    }
}
