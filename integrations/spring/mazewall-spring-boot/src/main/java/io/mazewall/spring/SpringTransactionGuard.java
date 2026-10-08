package io.mazewall.spring;

import io.mazewall.enforcer.api.ContainmentViolationException;
import io.mazewall.enforcer.api.Mazewall;
import io.mazewall.enforcer.diagnostics.ContainmentViolationDetector;
import java.lang.reflect.Method;
import java.nio.file.AccessDeniedException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.NoRollbackRuleAttribute;
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** One qualified synchronous JDBC manager; diagnosis and evidence checks stay inside its advice. */
public final class SpringTransactionGuard implements MethodInterceptor {
    private final ConfigurableListableBeanFactory beans;
    private final String managerName;
    private final DataSourceTransactionManager manager;
    private final ManagerSnapshot configuration;
    private final SpringTransactionEffects effects;
    private final ContainmentViolationDetector detector = new ContainmentViolationDetector();
    private final Advisor diagnosis = new DefaultPointcutAdvisor(this);
    private final Advisor transaction;

    public SpringTransactionGuard(ConfigurableListableBeanFactory beans, String managerName, SpringTransactionEffects effects) {
        this.beans = Objects.requireNonNull(beans);
        this.managerName = Objects.requireNonNull(managerName);
        this.effects = Objects.requireNonNull(effects);
        if (managerName.isBlank() || !beans.containsSingleton(managerName)) throw rejected("One qualified existing JDBC manager required");
        Object actual = beans.getBean(managerName);
        if (actual.getClass() != DataSourceTransactionManager.class && actual.getClass() != JdbcTransactionManager.class) {
            throw rejected("Only exact synchronous DataSourceTransactionManager/JdbcTransactionManager supported");
        }
        manager = (DataSourceTransactionManager) actual;
        configuration = ManagerSnapshot.of(manager);
        if (configuration.dataSource == null || configuration.synchronization == AbstractPlatformTransactionManager.SYNCHRONIZATION_NEVER) {
            throw rejected("Actual JDBC datasource and transaction synchronization required");
        }
        transaction = new DefaultPointcutAdvisor(new QualifiedJdbcTransactionInterceptor(manager, managerName));
    }

    public Advisor diagnosisAdvisor() { return diagnosis; }
    public Advisor transactionAdvisor() { return transaction; }

    void validate(TransactionManager actual, TransactionAttributeSource attributes, Method method, Class<?> target) {
        if (actual != manager || beans.getBean(managerName) != manager || configuration.dataSource != manager.getDataSource()
                || !configuration.equals(ManagerSnapshot.of(manager))) {
            throw rejected("Qualified JDBC manager/datasource/configuration identity changed");
        }
        if (attributes.getClass() != AnnotationTransactionAttributeSource.class) {
            throw rejected("S2 requires effective annotation transaction attributes");
        }
        var attribute = attributes.getTransactionAttribute(method, target);
        if (attribute == null || attribute.getClass() != RuleBasedTransactionAttribute.class
                || !managerName.equals(attribute.getQualifier())) throw rejected("Method must select the exact qualified JDBC manager");
        int propagation = attribute.getPropagationBehavior();
        if (propagation < TransactionDefinition.PROPAGATION_REQUIRED || propagation > TransactionDefinition.PROPAGATION_NESTED) {
            throw rejected("Unsupported JDBC propagation");
        }
        if (propagation == TransactionDefinition.PROPAGATION_NESTED && !manager.isNestedTransactionAllowed()) {
            throw rejected("NESTED requires enabled JDBC savepoints");
        }
        var rules = (RuleBasedTransactionAttribute) attribute;
        var bridge = new TransactionalFailureBridge(new IllegalStateException("rollback compatibility probe"), null);
        if (!rules.rollbackOn(bridge)) throw rejected("Effective rollback rules suppress the protected-failure bridge");
        Throwable[] protectedCauses = {bridge, new ContainmentViolationException("probe", null, null, null,
                io.mazewall.enforcer.api.ContainmentViolationEvidence.UNKNOWN), new AccessDeniedException("probe"),
                new java.io.IOException("probe"), new SecurityException("probe")};
        for (var rule : rules.getRollbackRules()) {
            if (rule instanceof NoRollbackRuleAttribute) {
                for (Throwable cause : protectedCauses) {
                    if (rule.getDepth(cause) >= 0) throw rejected("Explicit no-rollback rule is incompatible with diagnosed permission failure");
                }
            }
        }
    }

    Object execute(InvocationDispatcher.Body body, Long expectedGeneration) throws Throwable {
        return effects.execute(() -> {
            if (expectedGeneration != null) {
                var evidence = Mazewall.currentInvocationEvidence();
                if (evidence == null || evidence.getDomainGeneration() != expectedGeneration) {
                    throw rejected("Missing exact owned runtime/domain evidence before transaction entry");
                }
            }
            Object result = body.proceed();
            gate(); // Any failure recorded during completion still makes the call unsuccessful.
            return result;
        });
    }

    @Override public Object invoke(MethodInvocation invocation) throws Throwable {
        SpringTransactionEffects.Scope scope = SpringTransactionEffects.current();
        trackTransaction(scope);
        gate();
        try {
            Object value = invocation.proceed();
            gate();
            return value;
        } catch (TransactionalFailureBridge recorded) {
            throw recorded;
        } catch (Throwable failure) {
            var diagnostic = detector.diagnose(failure);
            if (diagnostic != null) {
                scope.record(failure, diagnostic);
                throw new TransactionalFailureBridge(failure, diagnostic);
            }
            throw failure; // Ordinary business/checked/fatal semantics remain Spring's responsibility.
        }
    }

    private void gate() {
        SpringTransactionEffects.Scope scope = SpringTransactionEffects.current();
        var evidence = Mazewall.currentInvocationEvidence();
        if (evidence != null && evidence.getObservedFailure() != null) {
            scope.record(evidence.getObservedFailure(), detector.diagnose(evidence.getObservedFailure()));
        }
        if (scope.recordedFailure != null) throw new TransactionalFailureBridge(scope.recordedFailure, scope.diagnostic);
    }

    private void trackTransaction(SpringTransactionEffects.Scope scope) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) throw rejected("Active transaction lacks completion synchronization");
        for (var synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            if (synchronization instanceof EffectSynchronization existing && existing.scope == scope) return;
        }
        TransactionSynchronizationManager.registerSynchronization(new EffectSynchronization(scope));
    }

    private final class EffectSynchronization implements TransactionSynchronization {
        private final SpringTransactionEffects.Scope scope;
        private final SpringTransactionEffects.Effect transaction;
        private final Map<Object, SpringTransactionEffects.Effect> savepoints = new IdentityHashMap<>();
        EffectSynchronization(SpringTransactionEffects.Scope scope) {
            this.scope = scope;
            transaction = scope.add(SpringTransactionEffects.Kind.TRANSACTION);
        }
        @Override public int getOrder() { return Integer.MAX_VALUE; }
        @Override public void beforeCommit(boolean readOnly) { gate(); }
        @Override public void savepoint(Object savepoint) { savepoints.put(savepoint, scope.add(SpringTransactionEffects.Kind.SAVEPOINT)); }
        @Override public void savepointRollback(Object savepoint) {
            var effect = savepoints.get(savepoint);
            if (effect != null) scope.requestSavepointRollback(effect,
                    // Savepoints created after this still-held savepoint belong to
                    // its nested work in this SAME physical transaction. Re-read
                    // after completion to include callback-created descendants;
                    // independent REQUIRES_NEW transactions have another map.
                    () -> savepoints.values().stream().filter(descendant -> descendant.id >= effect.id).toList());
        }
        @Override public void afterCompletion(int status) {
            SpringTransactionEffects.Outcome outcome = switch (status) {
                case STATUS_COMMITTED -> SpringTransactionEffects.Outcome.COMMITTED;
                case STATUS_ROLLED_BACK -> SpringTransactionEffects.Outcome.ROLLED_BACK;
                default -> SpringTransactionEffects.Outcome.UNKNOWN;
            };
            transaction.complete(outcome);
            for (var effect : savepoints.values()) {
                if (effect.outcome != SpringTransactionEffects.Outcome.ROLLED_BACK) {
                    // A confirmed whole-transaction rollback also undoes savepoint
                    // work. Commit/uncertainty cannot confirm a failed or unobserved
                    // savepoint rollback, even if Spring notified its intent.
                    effect.complete(effect.rollbackRequested && outcome != SpringTransactionEffects.Outcome.ROLLED_BACK
                            ? SpringTransactionEffects.Outcome.UNKNOWN : outcome);
                }
            }
        }
    }

    private record ManagerSnapshot(DataSource dataSource, boolean nested, boolean validateExisting,
            boolean globalRollback, boolean failEarly, boolean rollbackOnCommitFailure, int synchronization,
            int defaultTimeout, boolean enforceReadOnly) {
        static ManagerSnapshot of(DataSourceTransactionManager manager) {
            return new ManagerSnapshot(manager.getDataSource(), manager.isNestedTransactionAllowed(),
                    manager.isValidateExistingTransaction(), manager.isGlobalRollbackOnParticipationFailure(),
                    manager.isFailEarlyOnGlobalRollbackOnly(), manager.isRollbackOnCommitFailure(),
                    manager.getTransactionSynchronization(), manager.getDefaultTimeout(), manager.isEnforceReadOnly());
        }
    }

    // Spring's standard propagation/commit implementation remains authoritative. Its
    // qualified BeanFactory lookup is not observable through getTransactionManager(),
    // so the supported advisor owns this final exact-manager selection point.
    private static final class QualifiedJdbcTransactionInterceptor extends TransactionInterceptor {
        private static final long serialVersionUID = 1L;
        private final DataSourceTransactionManager qualifiedManager;
        private final String qualifier;
        QualifiedJdbcTransactionInterceptor(DataSourceTransactionManager manager, String qualifier) {
            super(manager, new AnnotationTransactionAttributeSource());
            qualifiedManager = manager;
            this.qualifier = qualifier;
        }
        @Override protected TransactionManager determineTransactionManager(TransactionAttribute attribute, Class<?> targetClass) {
            if (attribute == null || !qualifier.equals(attribute.getQualifier())) {
                throw rejected("Effective transaction does not select the reviewed qualified manager");
            }
            return qualifiedManager;
        }
        @Override protected void completeTransactionAfterThrowing(TransactionInfo info, InvocationCallback invocation, Throwable failure) {
            SpringTransactionEffects.Scope scope = SpringTransactionEffects.currentIfPresent();
            boolean heldSavepointRollback = info != null && info.getTransactionManager() == qualifiedManager
                    && info.getTransactionStatus() != null && info.getTransactionStatus().hasSavepoint()
                    && info.getTransactionAttribute() != null && info.getTransactionAttribute().rollbackOn(failure);
            var attempt = heldSavepointRollback && scope != null ? scope.beginSavepointRollback() : null;
            boolean succeeded = false;
            try {
                // Keep Spring's actual rollback/rules/callback/exception semantics.
                // Normal return from this exact manager's held-savepoint rollback
                // path confirms JDBC success; a callback by itself does not.
                super.completeTransactionAfterThrowing(info, invocation, failure);
                succeeded = true;
            } finally {
                if (attempt != null) scope.finishSavepointRollback(attempt, succeeded);
            }
        }
    }

    private static IllegalStateException rejected(String reason) { return new IllegalStateException("Unsupported Spring JDBC binding: " + reason); }
}
