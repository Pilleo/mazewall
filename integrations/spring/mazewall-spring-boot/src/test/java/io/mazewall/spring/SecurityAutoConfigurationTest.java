package io.mazewall.spring;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Conditional configuration is host evidence, not an executable Boot/native launch. */
class SecurityAutoConfigurationTest {
    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MazewallSecurityAutoConfiguration.class));

    @Test void absentSecurityNeverLinksEvenWhenExplicitPropertyIsEnabled() {
        contexts.withClassLoader(new FilteredClassLoader("org.springframework.security"))
                .withPropertyValues("mazewall.security.delegate-context=true").run(context -> {
                    assertNull(context.getStartupFailure());
                    assertTrue(context.getBeansOfType(InvocationContextBridge.class).isEmpty());
                });
    }

    @Test void presenceAloneDoesNotChangeAuthenticationDefaults() {
        contexts.run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(InvocationContextBridge.class).isEmpty());
        });
    }

    @Test void explicitPropertyCreatesOnlyContextBridgeAndPreservesApplicationOverride() {
        contexts.withPropertyValues("mazewall.security.delegate-context=true").run(context -> {
            assertNull(context.getStartupFailure());
            assertInstanceOf(SpringSecurityContextBridge.class, context.getBean(InvocationContextBridge.class));
            assertTrue(context.getBeansOfType(DomainLifecycle.class).isEmpty(), "No implicit runtime/domain/baseline creation");
        });
        var application = InvocationContextBridge.none();
        contexts.withPropertyValues("mazewall.security.delegate-context=true")
                .withBean(InvocationContextBridge.class, () -> application).run(context -> {
                    assertNull(context.getStartupFailure());
                    assertSame(application, context.getBean(InvocationContextBridge.class));
                });
    }
}
