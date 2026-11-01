package io.mazewall.spring;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.Advisor;
import org.springframework.aop.Pointcut;
import org.springframework.aop.ProxyMethodInvocation;
import org.springframework.aop.TargetSource;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.DefaultAdvisorChainFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.framework.ReflectiveMethodInvocation;
import org.springframework.aop.support.AbstractPointcutAdvisor;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.target.SingletonTargetSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.interceptor.MatchAlwaysTransactionAttributeSource;
import org.springframework.transaction.interceptor.NoRollbackRuleAttribute;
import org.springframework.transaction.interceptor.RollbackRuleAttribute;
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.core.BridgeMethodResolver;

/** Explicit S0 boundary; binding is not manifest approval or protected readiness. */
public final class BoundInvocationInterceptor implements MethodInterceptor {
    private static final ThreadLocal<ExecutionContext> ACTIVE = new ThreadLocal<>();
    private final InvocationDispatcher dispatcher;
    private final SpringTransactionGuard jdbcGuard;
    private final Advisor dispatchAdvisor = new DispatchAdvisor(this);
    private volatile Placement proof;
    private Runnable bindingGuard = () -> {};

    /** Production construction requires the final adapter backed by an actual owned runtime/domain. */
    public BoundInvocationInterceptor(InvocationDispatcher dispatcher) {
        this(dispatcher, false, null);
    }

    public BoundInvocationInterceptor(ManagedRuntimeDispatcher dispatcher, SpringTransactionGuard jdbcGuard) {
        this(dispatcher, false, Objects.requireNonNull(jdbcGuard));
    }

    private BoundInvocationInterceptor(InvocationDispatcher dispatcher, boolean trustedHostCharacterization,
                                       SpringTransactionGuard jdbcGuard) {
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.jdbcGuard = jdbcGuard;
        if (!trustedHostCharacterization && !(dispatcher instanceof ManagedRuntimeDispatcher)) {
            throw new IllegalArgumentException("Production Spring dispatch requires ManagedRuntimeDispatcher; "
                    + "arbitrary dispatch is available only through forTrustedHostCharacterization");
        }
    }

    /**
     * Explicit unprotected host characterization. The supplied dispatcher is trusted;
     * thread checks cannot prove its ownership or distinguish a shared platform executor.
     * This factory neither grants an ownership capability nor establishes native protection.
     */
    public static BoundInvocationInterceptor forTrustedHostCharacterization(InvocationDispatcher dispatcher) {
        return new BoundInvocationInterceptor(dispatcher, true, null);
    }

    public static BoundInvocationInterceptor forTrustedHostCharacterization(InvocationDispatcher dispatcher,
                                                                           SpringTransactionGuard jdbcGuard) {
        return new BoundInvocationInterceptor(dispatcher, true, Objects.requireNonNull(jdbcGuard));
    }

    /**
     * Build a fresh frozen proxy around an existing singleton bean, retaining the supplied advice.
     * This is a programmatic S0 entry, not auto-configuration or approved-manifest binding.
     * Diagnosis/authorization collaborators must be reviewed synchronous single-proceed advice.
     */
    public synchronized Object createProxy(Object existingBean, Advisor transaction,
                                           Advisor diagnosis, Advisor authorization,
                                           List<Method> methods) throws Exception {
        if (proof != null) throw rejected("Boundary already bound");
        ProxyFactory factory = new ProxyFactory(Objects.requireNonNull(existingBean));
        factory.addAdvisor(dispatchAdvisor);
        factory.addAdvisor(transaction);
        factory.addAdvisor(diagnosis);
        factory.addAdvisor(authorization);
        factory.setFrozen(true);
        Object proxy = factory.getProxy();
        bind(proxy, transaction, diagnosis, authorization, methods);
        return proxy;
    }

    /** Bind the actual registered frozen proxy; configuration installs this guard before publication. */
    synchronized void bindRegisteredProxy(Object proxy, List<Method> methods) throws Exception {
        if (!(proxy instanceof Advised advised) || !advised.isFrozen() || advised.getAdvisors().length != 4
                || advised.getAdvisors()[0] != dispatchAdvisor) {
            throw rejected("Registered frozen proxy must already have this dispatch guard and reviewed advice");
        }
        Advisor[] retained = advised.getAdvisors();
        bind(proxy, retained[1], retained[2], retained[3], methods);
    }

    synchronized void requireBindingGuard(Runnable guard) {
        if (proof != null) throw rejected("Cannot replace binding guard after binding");
        bindingGuard = Objects.requireNonNull(guard);
    }

    // Module-internal structural validator. Public entry owns fresh proxy construction,
    // so an earlier cached chain cannot disagree with the binding snapshot.
    synchronized void bind(Object proxy, Advisor transaction, Advisor diagnosis,
                           Advisor authorization, List<Method> methods) throws Exception {
        if (proof != null) throw rejected("Boundary already bound");
        if (!Proxy.isProxyClass(proxy.getClass()) || !(proxy instanceof Advised advised)) {
            throw rejected("S0 requires an inspectable Spring JDK proxy");
        }
        TargetSource source = advised.getTargetSource();
        if (source.getClass() != SingletonTargetSource.class) {
            throw rejected("S0 requires an existing static singleton target");
        }
        Object target = source.getTarget(); // Identity/topology inspection only; never invoke this object.
        if (target == null || AopUtils.isAopProxy(target) || target instanceof Advised) {
            throw rejected("Nested proxy topology has no S0 placement proof");
        }
        List<Advisor> advisors = List.of(advised.getAdvisors());
        if (advisors.size() != 4 || advisors.getFirst() != dispatchAdvisor
                || advisors.get(1) != transaction || advisors.get(2) != diagnosis
                || advisors.get(3) != authorization
                || (jdbcGuard == null ? transaction.getAdvice().getClass() != TransactionInterceptor.class
                : transaction != jdbcGuard.transactionAdvisor())) {
            throw rejected("Required order: dispatch, transaction, diagnosis, authorization");
        }
        if (jdbcGuard != null && diagnosis != jdbcGuard.diagnosisAdvisor()) {
            throw rejected("S2 diagnosis must be the exact JDBC commit/evidence guard");
        }
        List<Object> advice = advisors.stream().map(advisor -> (Object) advisor.getAdvice()).toList();
        if (advice.stream().anyMatch(item -> !(item instanceof MethodInterceptor))
                || advice.stream().distinct().count() != 4) {
            throw rejected("Four distinct synchronous interceptors are required");
        }
        Set<Method> approved = Set.copyOf(methods);
        if (approved.isEmpty()) throw rejected("No approved synchronous methods");
        for (Method method : approved) {
            Method implementation = BridgeMethodResolver.findBridgedMethod(AopUtils.getMostSpecificMethod(method, target.getClass()));
            if (!method.getDeclaringClass().isInterface()
                    || !advised.isInterfaceProxied(method.getDeclaringClass())
                    || (!materialized(implementation.getReturnType()) && (jdbcGuard == null || !implementation.getReturnType().isRecord()))
                    || (method.isSynthetic() && !method.isBridge())
                    || Arrays.stream(method.getParameterTypes()).anyMatch(
                            type -> type.getName().equals("kotlin.coroutines.Continuation"))) {
                throw rejected("Unproved method or result shape: " + method);
            }
            if (implementation.getReturnType().isRecord()) SpringRecordResults.validateShape(implementation.getReturnType(), target.getClass());
        }
        TransactionInterceptor tx = (TransactionInterceptor) transaction.getAdvice();
        TransactionManager manager = tx.getTransactionManager();
        TransactionAttributeSource attributes = tx.getTransactionAttributeSource();
        if (!(manager instanceof PlatformTransactionManager) || attributes == null
                || (jdbcGuard == null && attributes.getClass() != MatchAlwaysTransactionAttributeSource.class)) {
            throw rejected("S0 requires one explicit synchronous transaction manager and attributes");
        }
        Map<Method, TransactionSnapshot> definitions = approved.stream().collect(Collectors.toUnmodifiableMap(
                method -> method, method -> transactionDefinition(attributes, method, target.getClass(), jdbcGuard, manager, advice.get(3))));
        Placement candidate = new Placement(proxy, advised, source, target, advisors, advice,
                approved, tx, manager, attributes, definitions, bindingGuard, jdbcGuard);
        candidate.validate();
        proof = candidate;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        try {
            return invokeProved(invocation);
        } catch (TransactionalFailureBridge bridge) {
            throw bridge.original(); // Rollback has already completed; preserve original caller identity.
        }
    }

    private Object invokeProved(MethodInvocation invocation) throws Throwable {
        Placement current = proof;
        if (current == null) throw rejected("Boundary has no placement proof");
        if (invocation.getClass() != ReflectiveMethodInvocation.class
                || !(invocation instanceof ProxyMethodInvocation spring)
                || spring.getProxy() != current.proxy || invocation.getThis() != current.target
                || !current.methods.contains(invocation.getMethod())) {
            throw rejected("Invocation does not match the proved singleton/method/proxy");
        }
        current.validate();
        ExecutionContext active = ACTIVE.get();
        if (active != null && (active.dispatcher != dispatcher || active.manager != current.manager)) {
            throw rejected("Nested dispatch to a different execution binding or transaction manager");
        }
        if (active == null && (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getResourceMap().isEmpty())) {
            throw rejected("Cannot dispatch an external thread-bound transaction/resource");
        }
        MethodInvocation clone = spring.invocableClone();
        AtomicBoolean entered = new AtomicBoolean();
        InvocationDispatcher.Body proceedOnce = () -> {
            if (!entered.compareAndSet(false, true)) throw rejected("Dispatcher attempted invocation replay");
            return preserveFatalIdentity(clone::proceed);
        };
        // A Spring clone resumes AFTER this interceptor, preserving the remaining chain.
        if (active != null) {
            // Keep the canonical runtime's inline transition and observed-failure classification.
            return dispatcher.call(() -> {
                if (Thread.currentThread() != active.worker || Thread.currentThread().isVirtual()
                        || ACTIVE.get() != active) {
                    throw rejected("Nested dispatch must retain the exact active physical worker/context");
                }
                current.validate();
                return proceedOnce.proceed();
            });
        }
        Thread caller = Thread.currentThread();
        return dispatcher.call(() -> {
            if (Thread.currentThread() == caller || Thread.currentThread().isVirtual() || ACTIVE.get() != null) {
                throw rejected("Dispatch must leave the caller and enter a clean platform thread");
            }
            if (TransactionSynchronizationManager.isActualTransactionActive()
                    || TransactionSynchronizationManager.isSynchronizationActive()
                    || !TransactionSynchronizationManager.getResourceMap().isEmpty()) {
                throw rejected("Worker has unrelated thread-bound transaction/resources");
            }
            current.validate(); // Recheck after queueing, before any transaction/body entry.
            ACTIVE.set(new ExecutionContext(dispatcher, current.manager, Thread.currentThread()));
            try {
                return jdbcGuard == null ? proceedOnce.proceed() : preserveFatalIdentity(() -> jdbcGuard.execute(proceedOnce,
                        dispatcher instanceof ManagedRuntimeDispatcher owned ? owned.generation() : null));
            } finally {
                ACTIVE.remove();
            }
        });
    }

    private static Object preserveFatalIdentity(InvocationDispatcher.Body body) throws Throwable {
        try {
            return body.proceed();
        } catch (TransactionalFailureBridge bridge) {
            // The transaction has seen the unchecked rollback bridge. The owned runtime
            // must now see the original Error to fail/retire its generation, even if
            // application code catches a nested fatal failure on this same worker.
            if (bridge.original() instanceof Error fatal) throw fatal;
            throw bridge;
        }
    }

    private static boolean materialized(Class<?> type) {
        return type.isPrimitive() || type == String.class || type == Boolean.class
                || type == Byte.class || type == Short.class || type == Integer.class
                || type == Long.class || type == Float.class || type == Double.class
                || type == Character.class || type == byte[].class;
    }

    private record ExecutionContext(InvocationDispatcher dispatcher, TransactionManager manager, Thread worker) {}

    private static IllegalStateException rejected(String reason) {
        return new IllegalStateException("Unproved Spring invocation: " + reason);
    }

    private static TransactionSnapshot transactionDefinition(TransactionAttributeSource source, Method method, Class<?> target,
                                                            SpringTransactionGuard jdbcGuard, TransactionManager manager, Object authorizationAdvice) {
        if (jdbcGuard != null) jdbcGuard.validate(manager, source, method, target);
        TransactionAttribute attribute = source.getTransactionAttribute(method, target);
        if (attribute == null || attribute.getClass() != RuleBasedTransactionAttribute.class
                || (jdbcGuard == null && (attribute.getPropagationBehavior() != TransactionDefinition.PROPAGATION_REQUIRED
                || attribute.getQualifier() != null))) {
            throw rejected("S0 requires explicit unqualified REQUIRED transaction metadata: " + method);
        }
        RuleBasedTransactionAttribute ruleBased = (RuleBasedTransactionAttribute) attribute;
        if (authorizationAdvice instanceof AuthorizationRollbackContract contract) contract.validateRollback(attribute, method, target);
        return new TransactionSnapshot(attribute.getPropagationBehavior(), attribute.getIsolationLevel(),
                attribute.getTimeout(), attribute.isReadOnly(), attribute.getName(), attribute.getQualifier(),
                ruleBased.getDescriptor(), ruleBased.getTimeoutString(), List.copyOf(attribute.getLabels()),
                ruleBased.getRollbackRules().stream().map(RollbackRuleIdentity::new).toList());
    }

    private record TransactionSnapshot(int propagation, int isolation, int timeout, boolean readOnly,
                                       String name, String qualifier, String descriptor, String timeoutString,
                                       List<String> labels, List<RollbackRuleIdentity> rollbackRules) {}

    // Spring's exact matcher classes have immutable final matching fields. Pin identities,
    // including list order, because equals/getExceptionName do not distinguish type vs pattern.
    private static final class RollbackRuleIdentity {
        private final RollbackRuleAttribute rule;
        RollbackRuleIdentity(RollbackRuleAttribute rule) {
            if (rule.getClass() != RollbackRuleAttribute.class && rule.getClass() != NoRollbackRuleAttribute.class) {
                throw rejected("Unsupported rollback-rule implementation");
            }
            this.rule = rule;
        }
        @Override public boolean equals(Object other) {
            return other instanceof RollbackRuleIdentity identity && rule == identity.rule;
        }
        @Override public int hashCode() { return System.identityHashCode(rule); }
    }

    private record Placement(Object proxy, Advised advised, TargetSource source, Object target,
                             List<Advisor> advisors, List<Object> advice, Set<Method> methods,
                             TransactionInterceptor transaction, TransactionManager manager,
                             TransactionAttributeSource attributes, Map<Method, TransactionSnapshot> definitions, Runnable bindingGuard,
                             SpringTransactionGuard jdbcGuard) {
        void validate() {
            bindingGuard.run();
            if (!advised.isFrozen() || advised.getTargetSource() != source
                    || advised.isExposeProxy() || advised.isPreFiltered()) {
                throw rejected("Proxy is mutable, replaced, exposed or prefiltered");
            }
            if (transaction.getTransactionManager() != manager
                    || transaction.getTransactionAttributeSource() != attributes) {
                throw rejected("Transaction manager or attribute source changed");
            }
            Advisor[] actual = advised.getAdvisors();
            if (actual.length != advisors.size()) throw rejected("Advisor count changed");
            for (int index = 0; index < actual.length; index++) {
                if (actual[index] != advisors.get(index)
                        || (index == 0 ? actual[index].getClass() != DispatchAdvisor.class
                        : actual[index].getClass() != DefaultPointcutAdvisor.class
                        || ((DefaultPointcutAdvisor) actual[index]).getPointcut() != Pointcut.TRUE)
                        || actual[index].getAdvice() != advice.get(index)) {
                    throw rejected("Advisor identity, pointcut or advice changed at " + index);
                }
            }
            for (Method method : methods) {
                if (!transactionDefinition(attributes, method, target.getClass(), jdbcGuard, manager, advice.get(3)).equals(definitions.get(method))) {
                    throw rejected("Effective transaction definition changed: " + method);
                }
                List<Object> resolved = DefaultAdvisorChainFactory.INSTANCE
                        .getInterceptorsAndDynamicInterceptionAdvice(advised, method, target.getClass());
                if (resolved.size() != advice.size()) throw rejected("Unproved effective chain: " + method);
                for (int index = 0; index < resolved.size(); index++) {
                    if (resolved.get(index) != advice.get(index)) {
                        throw rejected("Unproved effective advice at " + index + " for " + method);
                    }
                }
            }
        }
    }

    // Frozen Advised alone does not freeze mutable DefaultPointcutAdvisor objects.
    // The dispatch slot must therefore be immutable: drift can never remove the guard.
    private static final class DispatchAdvisor extends AbstractPointcutAdvisor {
        private final Advice advice;
        DispatchAdvisor(Advice advice) { this.advice = advice; }
        @Override public Advice getAdvice() { return advice; }
        @Override public Pointcut getPointcut() { return Pointcut.TRUE; }
    }

    Advisor dispatchAdvisor() { return dispatchAdvisor; }
}
