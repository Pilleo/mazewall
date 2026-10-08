package io.mazewall.spring;

import io.mazewall.enforcer.api.OperationAdapter;
import io.mazewall.enforcer.api.ReviewedOperationArtifact;
import io.mazewall.enforcer.api.RuntimeProfile;
import io.mazewall.enforcer.api.execution.PreparedOperationResources;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.target.SingletonTargetSource;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;

/** Explicit trusted configuration binds one reviewed operation to one actual registered singleton proxy. */
public final class SpringOperationBinder {
    public enum Phase { BODY, TRANSACTION_COMPLETION }

    /** Review expectations are application configuration, not derived from request or candidate metadata. */
    public record Approval(ReviewedOperationArtifact artifact, Object expectedBean, ClassLoader expectedClassLoader,
                           RuntimeProfile runtime, String resourceGeneration, Set<Phase> coveredPhases) {
        public Approval {
            Objects.requireNonNull(artifact);
            Objects.requireNonNull(expectedBean);
            Objects.requireNonNull(expectedClassLoader);
            Objects.requireNonNull(runtime);
            Objects.requireNonNull(resourceGeneration);
            coveredPhases = Set.copyOf(coveredPhases);
        }
    }

    private final ConfigurableListableBeanFactory beans;
    private final ReviewedOperationArtifact preparedArtifact;
    private final Supplier<InvocationDispatcher> readiness;
    private final BoundInvocationInterceptor boundary;
    private final PreparedOperationResources resources;
    private Object boundBean;

    public SpringOperationBinder(ConfigurableListableBeanFactory beans, DomainLifecycle lifecycle) {
        this(beans, Objects.requireNonNull(lifecycle.artifact()), lifecycle::dispatcher, false, null, lifecycle.resources());
    }

    public SpringOperationBinder(ConfigurableListableBeanFactory beans, DomainLifecycle lifecycle, SpringTransactionGuard jdbcGuard) {
        this(beans, Objects.requireNonNull(lifecycle.artifact()), lifecycle::dispatcher, false, Objects.requireNonNull(jdbcGuard), lifecycle.resources());
    }

    /** Explicit trusted grouping: independently reviewed entries share this exact lifecycle's fixed policy. */
    public static SpringOperationBinder inApprovedCommonDomain(ConfigurableListableBeanFactory beans, DomainLifecycle lifecycle,
            ReviewedOperationArtifact operation, SpringTransactionGuard jdbcGuard) {
        requireSameFixedDomain(lifecycle.artifact(), operation);
        return new SpringOperationBinder(beans, operation, lifecycle::dispatcher, false, Objects.requireNonNull(jdbcGuard), lifecycle.resources());
    }

    private SpringOperationBinder(ConfigurableListableBeanFactory beans, ReviewedOperationArtifact artifact,
                                  Supplier<InvocationDispatcher> readiness, boolean trustedHost, SpringTransactionGuard jdbcGuard,
                                  PreparedOperationResources resources) {
        this.beans = Objects.requireNonNull(beans);
        this.preparedArtifact = Objects.requireNonNull(artifact);
        this.readiness = readiness;
        this.resources = trustedHost ? null : Objects.requireNonNull(resources);
        InvocationDispatcher dispatcher = readiness.get(); // Fail required inactive protection explicitly.
        boundary = jdbcGuard == null ? (trustedHost ? BoundInvocationInterceptor.forTrustedHostCharacterization(dispatcher)
                : new BoundInvocationInterceptor(dispatcher)) : (trustedHost
                ? BoundInvocationInterceptor.forTrustedHostCharacterization(dispatcher, jdbcGuard)
                : new BoundInvocationInterceptor((ManagedRuntimeDispatcher) dispatcher, jdbcGuard));
    }

    /** Uncontained host-only proof of Spring binding; never native/protected readiness. */
    public static SpringOperationBinder forTrustedHostCharacterization(ConfigurableListableBeanFactory beans,
            InvocationDispatcher dispatcher, ReviewedOperationArtifact artifact) {
        return new SpringOperationBinder(beans, artifact, () -> dispatcher, true, null, null);
    }

    public static SpringOperationBinder forTrustedHostCharacterization(ConfigurableListableBeanFactory beans,
            InvocationDispatcher dispatcher, ReviewedOperationArtifact artifact, SpringTransactionGuard jdbcGuard) {
        return new SpringOperationBinder(beans, artifact, () -> dispatcher, true, Objects.requireNonNull(jdbcGuard), null);
    }

    public static SpringOperationBinder forTrustedHostCharacterizationInCommonDomain(ConfigurableListableBeanFactory beans,
            InvocationDispatcher dispatcher, ReviewedOperationArtifact domainArtifact,
            ReviewedOperationArtifact operation, SpringTransactionGuard jdbcGuard) {
        requireSameFixedDomain(domainArtifact, operation);
        return new SpringOperationBinder(beans, operation, () -> dispatcher, true, Objects.requireNonNull(jdbcGuard), null);
    }

    private static void requireSameFixedDomain(ReviewedOperationArtifact domain, ReviewedOperationArtifact operation) {
        Objects.requireNonNull(domain);
        Objects.requireNonNull(operation);
        if (!domain.getDocument().getPolicy().toJson().equals(operation.getDocument().getPolicy().toJson())
                || !domain.getBinding().getResourceGeneration().equals(operation.getBinding().getResourceGeneration())) {
            throw rejected("Approved common-domain operations must share the exact fixed policy/runtime/resource generation");
        }
    }

    /** Install in the existing application's ProxyFactory BEFORE publication and freeze that factory. */
    public Advisor dispatchAdvisor() { return boundary.dispatchAdvisor(); }

    public synchronized Object bind(Approval approval) throws Exception {
        Objects.requireNonNull(approval);
        if (boundBean != null) throw rejected("Operation already bound");
        readiness.get();
        var artifact = approval.artifact();
        var binding = artifact.getBinding();
        if (!artifact.getDocument().toJson().equals(preparedArtifact.getDocument().toJson())
                || binding.getAdapter() != OperationAdapter.SPRING_METHOD
                || artifact.getDocument().getPolicy().getRuntime() != approval.runtime()
                || !binding.getResourceGeneration().equals(approval.resourceGeneration())
                || !approval.coveredPhases().equals(Set.of(Phase.BODY, Phase.TRANSACTION_COMPLETION))) {
            throw rejected("Reviewed artifact/domain/runtime/resource generation/covered phases differ");
        }
        String beanName = binding.getAdapterIdentity().get("beanName");
        if (!beans.containsSingleton(beanName) || !beans.isSingleton(beanName)) {
            throw rejected("Required operation is not an existing singleton: " + beanName);
        }
        if (beans.containsBeanDefinition(beanName)) {
            var definition = beans.getMergedBeanDefinition(beanName);
            if (definition.isLazyInit() || !definition.isSingleton()) throw rejected("Lazy/scoped operation is unsupported");
        }
        Object bean = beans.getBean(beanName);
        if (bean != approval.expectedBean() || !(bean instanceof Advised advised)
                || advised.getTargetSource().getClass() != SingletonTargetSource.class) {
            throw rejected("Actual registered bean identity or singleton topology differs");
        }
        Object target = advised.getTargetSource().getTarget(); // Inspection only; never raw-target invocation.
        if (target == null || AopUtils.isAopProxy(target)) throw rejected("Nested proxy topology unsupported");
        Class<?> type = target.getClass();
        if (resources != null) {
            // One selected Class is the reviewed core owner contract. Another actual origin/class
            // cannot be admitted merely because a common-domain document repeats its generation.
            if (resources.getImplementationClass() != type
                    || resources.getRuntime() != approval.runtime()
                    || !resources.getResourceGeneration().equals(approval.resourceGeneration())) {
                throw rejected("Actual target is not included in the prepared implementation/resource owner");
            }
            resources.validate();
        }
        if (type.getClassLoader() != approval.expectedClassLoader()
                || !type.getName().equals(binding.getAdapterIdentity().get("className"))
                || !(resources == null ? classResourceSha256(type) : resources.getImplementationSha256())
                    .equals(binding.getImplementationSha256())) {
            throw rejected("Actual implementation class/classloader/bytes differ from trusted review");
        }
        List<Method> approved = new ArrayList<>();
        for (Class<?> contract : advised.getProxiedInterfaces()) {
            for (Method method : contract.getMethods()) {
                Method actual = BridgeMethodResolver.findBridgedMethod(AopUtils.getMostSpecificMethod(method, type));
                if (!methodDescriptor(actual).equals(binding.getAdapterIdentity().get("methodDescriptor"))) continue;
                if (resources != null && !sameOrdinaryJarOrigin(type, actual.getDeclaringClass())) {
                    throw rejected("Actual method implementation is outside the prepared ordinary JAR");
                }
                MazewallOperation marker = AnnotatedElementUtils.findMergedAnnotation(actual, MazewallOperation.class);
                if (marker == null) marker = AnnotatedElementUtils.findMergedAnnotation(method, MazewallOperation.class);
                if (marker == null || !marker.value().equals(binding.getOperationId())
                        || Modifier.isFinal(actual.getModifiers()) || !Modifier.isPublic(actual.getModifiers())) {
                    throw rejected("Missing/mismatched required operation or unproxyable method");
                }
                approved.add(method);
            }
        }
        if (approved.isEmpty()) throw rejected("No actual bridged JVM descriptor matches the review");
        validateAnnotatedMethods(bean, artifact);
        boundary.requireBindingGuard(() -> {
            readiness.get();
            if (!beans.containsSingleton(beanName) || beans.getBean(beanName) != bean) {
                throw rejected("Registered singleton identity changed after binding");
            }
        });
        boundary.bindRegisteredProxy(bean, approved);
        boundBean = bean;
        return bean;
    }

    /** Check the explicit protected scope before admitting traffic; an annotation alone never protects. */
    public synchronized void validateRequiredBindings(List<String> beanNames) throws Exception {
        for (String name : beanNames) {
            if (!beans.containsSingleton(name)) throw rejected("Required scope contains an uninitialized/scoped bean: " + name);
            Object bean = beans.getBean(name);
            Class<?> type = AopUtils.getTargetClass(bean);
            for (Method method : operationDeclarations(bean)) {
                Method actual = BridgeMethodResolver.findBridgedMethod(AopUtils.getMostSpecificMethod(method, type));
                if (operationMarker(method, actual) != null && bean != boundBean) {
                    throw rejected("Required annotated bean is unbound: " + name + ":" + methodDescriptor(method));
                }
            }
            if (bean == boundBean) validateAnnotatedMethods(bean, preparedArtifact);
        }
    }

    private static void validateAnnotatedMethods(Object bean, ReviewedOperationArtifact artifact) {
        Class<?> type = AopUtils.getTargetClass(bean);
        Set<Method> reachable = new java.util.HashSet<>();
        if (bean instanceof Advised advised) {
            for (Class<?> contract : advised.getProxiedInterfaces()) {
                for (Method method : contract.getMethods()) {
                    if (Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers())) {
                        reachable.add(BridgeMethodResolver.findBridgedMethod(AopUtils.getMostSpecificMethod(method, type)));
                    }
                }
            }
        }
        for (Method method : operationDeclarations(bean)) {
            Method actual = BridgeMethodResolver.findBridgedMethod(AopUtils.getMostSpecificMethod(method, type));
            MazewallOperation marker = operationMarker(method, actual);
            if (marker != null && (!Modifier.isPublic(method.getModifiers()) || Modifier.isStatic(method.getModifiers())
                    || !Modifier.isPublic(actual.getModifiers()) || Modifier.isStatic(actual.getModifiers())
                    || Modifier.isFinal(actual.getModifiers()) || !reachable.contains(actual))) {
                throw rejected("Unproxyable required declaration has no admitted interface operation: " + method);
            }
            if (marker != null && (!marker.value().equals(artifact.getBinding().getOperationId())
                    || !methodDescriptor(actual).equals(artifact.getBinding().getAdapterIdentity().get("methodDescriptor")))) {
                throw rejected("Required annotated operation/overload has no exact review: " + methodDescriptor(actual));
            }
        }
    }

    private static List<Method> operationDeclarations(Object bean) {
        Class<?> type = AopUtils.getTargetClass(bean);
        List<Method> declarations = new ArrayList<>();
        // getMethods() omits precisely the non-public required declarations that
        // a JDK proxy cannot protect. Inspect them without making them accessible.
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            declarations.addAll(List.of(current.getDeclaredMethods()));
        }
        Set<Class<?>> contracts = new java.util.LinkedHashSet<>(List.of(ClassUtils.getAllInterfacesForClass(type)));
        if (bean instanceof Advised advised) {
            for (Class<?> contract : advised.getProxiedInterfaces()) {
                contracts.add(contract);
                contracts.addAll(List.of(ClassUtils.getAllInterfacesForClass(contract)));
            }
        }
        for (Class<?> contract : contracts) declarations.addAll(List.of(contract.getDeclaredMethods()));
        return declarations;
    }

    private static MazewallOperation operationMarker(Method declaration, Method actual) {
        MazewallOperation marker = AnnotatedElementUtils.findMergedAnnotation(declaration, MazewallOperation.class);
        return marker != null ? marker : AnnotatedElementUtils.findMergedAnnotation(actual, MazewallOperation.class);
    }

    public static String methodDescriptor(Method method) {
        return method.getName() + MethodType.methodType(method.getReturnType(), method.getParameterTypes()).descriptorString();
    }

    /** Host characterization only: a defining-loader class resource digest is NOT a finished-JAR identity. */
    public static String classResourceSha256(Class<?> implementation) throws IOException {
        String resource = "/" + implementation.getName().replace('.', '/') + ".class";
        try (InputStream input = implementation.getResourceAsStream(resource)) {
            if (input == null) throw rejected("Implementation bytes cannot be resolved: " + implementation.getName());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK SHA-256 unavailable", impossible);
        }
    }

    private static boolean sameOrdinaryJarOrigin(Class<?> selected, Class<?> declaring) {
        var selectedSource = selected.getProtectionDomain().getCodeSource();
        var declaringSource = declaring.getProtectionDomain().getCodeSource();
        return selected.getClassLoader() == declaring.getClassLoader() && selectedSource != null && declaringSource != null
                && selectedSource.getLocation().equals(declaringSource.getLocation());
    }

    private static IllegalStateException rejected(String message) {
        return new IllegalStateException("Unapproved Spring operation: " + message);
    }
}
