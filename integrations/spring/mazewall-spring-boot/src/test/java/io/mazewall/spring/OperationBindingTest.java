package io.mazewall.spring;

import static org.junit.jupiter.api.Assertions.*;

import io.mazewall.enforcer.api.*;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.transaction.interceptor.MatchAlwaysTransactionAttributeSource;
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionInterceptor;

class OperationBindingTest {
    interface Generic<T> { T run(); }
    interface Operation extends Generic<String> { @Override String run(); }
    static class Bean implements Operation {
        final AtomicInteger calls = new AtomicInteger();
        @MazewallOperation("write") public String run() { calls.incrementAndGet(); return "same bean"; }
    }
    interface Extra { String run(int count); }
    static class Overloaded extends Bean implements Extra {
        @MazewallOperation("write") public String run(int count) { return "overload"; }
    }
    static class FinalMethod extends Bean {
        @Override @MazewallOperation("write") public final String run() { return "final"; }
    }

    static ReviewedOperationArtifact artifact(Object target, Method implementation, String beanName, String hash) {
        var binding = new OperationBinding("write", OperationAdapter.SPRING_METHOD, hash, "resources-v1",
                Map.of("beanName", beanName, "className", target.getClass().getName(),
                        "methodDescriptor", SpringOperationBinder.methodDescriptor(implementation)));
        var policy = CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT,
                WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build());
        var document = new OperationPolicyDocument(binding, policy, Set.of(), Map.of());
        return ReviewedOperationArtifact.load(document.toJson(),
                new TrustedPolicyReview("application reviewer", document.sha256(), Set.of()), binding);
    }

    static final class Fixture implements AutoCloseable {
        final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        final Bean target;
        final AdvisorPlacementTest.HostDispatch dispatch = new AdvisorPlacementTest.HostDispatch(new java.util.ArrayList<>());
        final Object proxy;
        final ReviewedOperationArtifact artifact;
        final SpringOperationBinder binder;
        Fixture() throws Exception { this(new Bean()); }
        Fixture(Bean target) throws Exception {
            this.target = target;
            artifact = artifact(target, target.getClass().getMethod("run"), "operation", SpringOperationBinder.implementationSha256(target.getClass()));
            binder = SpringOperationBinder.forTrustedHostCharacterization(beans, dispatch, artifact);
            var attributes = new MatchAlwaysTransactionAttributeSource();
            attributes.setTransactionAttribute(new RuleBasedTransactionAttribute());
            var factory = new ProxyFactory(target);
            factory.addAdvisor(binder.dispatchAdvisor());
            factory.addAdvisor(new DefaultPointcutAdvisor(new TransactionInterceptor(
                    new AdvisorPlacementTest.Transactions(new java.util.ArrayList<>()), attributes)));
            factory.addAdvisor(new DefaultPointcutAdvisor((MethodInterceptor) invocation -> invocation.proceed()));
            factory.addAdvisor(new DefaultPointcutAdvisor((MethodInterceptor) invocation -> invocation.proceed()));
            factory.setFrozen(true);
            proxy = factory.getProxy();
            beans.registerSingleton("operation", proxy);
        }
        SpringOperationBinder binder() {
            return binder;
        }
        SpringOperationBinder.Approval approval(Object expected, ClassLoader loader, ReviewedOperationArtifact reviewed) {
            return new SpringOperationBinder.Approval(reviewed, expected, loader, RuntimeProfile.HOTSPOT_JIT,
                    "resources-v1", Set.of(SpringOperationBinder.Phase.BODY, SpringOperationBinder.Phase.TRANSACTION_COMPLETION));
        }
        @Override public void close() { dispatch.close(); }
    }

    @Test void actualSingletonAndBridgedMethodRetainRegisteredProxyAndAdvice() throws Exception {
        try (var fixture = new Fixture()) {
            var before = ((Advised) fixture.proxy).getAdvisors();
            Object bound = fixture.binder().bind(fixture.approval(fixture.proxy, Bean.class.getClassLoader(), fixture.artifact));
            assertSame(fixture.proxy, bound);
            assertSame(bound, fixture.beans.getBean("operation"));
            assertArrayEquals(before, ((Advised) bound).getAdvisors());
            assertEquals("same bean", ((Operation) bound).run());
            assertEquals("same bean", ((Generic<?>) bound).run());
            assertEquals(2, fixture.target.calls.get());
            assertEquals("run()Ljava/lang/String;", SpringOperationBinder.methodDescriptor(Bean.class.getMethod("run")));
        }
    }

    @Test void anotherSameClassBeanIsNotApproval() throws Exception {
        try (var fixture = new Fixture()) {
            assertThrows(IllegalStateException.class, () -> fixture.binder().bind(
                    fixture.approval(new Bean(), Bean.class.getClassLoader(), fixture.artifact)));
            assertEquals(0, fixture.target.calls.get());
        }
    }

    @Test void wrongLoaderAndStaleActualImplementationHashFailBeforeEntry() throws Exception {
        try (var fixture = new Fixture()) {
            assertThrows(IllegalStateException.class, () -> fixture.binder().bind(fixture.approval(
                    fixture.proxy, new ClassLoader() {}, fixture.artifact)));
            var stale = artifact(fixture.target, Bean.class.getMethod("run"), "operation", "0".repeat(64));
            assertThrows(IllegalStateException.class, () -> fixture.binder().bind(
                    fixture.approval(fixture.proxy, Bean.class.getClassLoader(), stale)));
            assertEquals(0, fixture.target.calls.get());
        }
    }

    @Test void missingReviewAndIncompleteCoveredPhaseFail() throws Exception {
        try (var fixture = new Fixture()) {
            assertThrows(NullPointerException.class, () -> fixture.binder().bind(fixture.approval(
                    fixture.proxy, Bean.class.getClassLoader(), null)));
            var approval = new SpringOperationBinder.Approval(fixture.artifact, fixture.proxy, Bean.class.getClassLoader(),
                    RuntimeProfile.HOTSPOT_JIT, "resources-v1", Set.of(SpringOperationBinder.Phase.BODY));
            assertThrows(IllegalStateException.class, () -> fixture.binder().bind(approval));
        }
    }

    @Test void independentContextsDoNotShareBindings() throws Exception {
        try (var left = new Fixture(); var right = new Fixture()) {
            assertSame(left.proxy, left.binder().bind(left.approval(left.proxy, Bean.class.getClassLoader(), left.artifact)));
            assertSame(right.proxy, right.binder().bind(right.approval(right.proxy, Bean.class.getClassLoader(), right.artifact)));
            assertEquals("same bean", ((Operation) left.proxy).run());
            assertEquals("same bean", ((Operation) right.proxy).run());
        }
    }

    @Test void missingManifestInExplicitRequiredScopeNeverSilentlyBinds() throws Exception {
        try (var fixture = new Fixture()) {
            assertThrows(IllegalStateException.class, () -> fixture.binder().validateRequiredBindings(List.of("operation")));
            fixture.binder().bind(fixture.approval(fixture.proxy, Bean.class.getClassLoader(), fixture.artifact));
            fixture.binder().validateRequiredBindings(List.of("operation"));
            fixture.beans.registerSingleton("second", new Bean());
            assertThrows(IllegalStateException.class, () -> fixture.binder().validateRequiredBindings(List.of("operation", "second")));
        }
    }

    @Test void singletonReplacementAndAdvisorDriftFailBeforeBody() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.binder().bind(fixture.approval(fixture.proxy, Bean.class.getClassLoader(), fixture.artifact));
            var advisor = (DefaultPointcutAdvisor) ((Advised) fixture.proxy).getAdvisors()[2];
            advisor.setAdvice((MethodInterceptor) invocation -> "bypass");
            assertThrows(IllegalStateException.class, () -> ((Operation) fixture.proxy).run());
            assertEquals(0, fixture.target.calls.get());
        }
        try (var fixture = new Fixture()) {
            fixture.binder().bind(fixture.approval(fixture.proxy, Bean.class.getClassLoader(), fixture.artifact));
            fixture.beans.destroySingleton("operation");
            fixture.beans.registerSingleton("operation", new Bean());
            assertThrows(IllegalStateException.class, () -> ((Operation) fixture.proxy).run());
            assertEquals(0, fixture.target.calls.get());
        }
    }

    @Test void lazyOrScopedDefinitionIsRejectedEvenWithMatchingExistingObject() throws Exception {
        try (var fixture = new Fixture()) {
            var definition = new org.springframework.beans.factory.support.RootBeanDefinition(Bean.class);
            definition.setLazyInit(true);
            fixture.beans.registerBeanDefinition("operation", definition);
            fixture.beans.registerSingleton("operation", fixture.proxy);
            assertThrows(IllegalStateException.class, () -> fixture.binder().bind(
                    fixture.approval(fixture.proxy, Bean.class.getClassLoader(), fixture.artifact)));
        }
    }

    @Test void unmatchedAnnotatedOverloadAndFinalMethodCannotBePartiallyApproved() throws Exception {
        try (var fixture = new Fixture(new Overloaded())) {
            assertThrows(IllegalStateException.class, () -> fixture.binder().bind(
                    fixture.approval(fixture.proxy, Bean.class.getClassLoader(), fixture.artifact)));
        }
        try (var fixture = new Fixture(new FinalMethod())) {
            assertThrows(IllegalStateException.class, () -> fixture.binder().bind(
                    fixture.approval(fixture.proxy, Bean.class.getClassLoader(), fixture.artifact)));
        }
    }
}
