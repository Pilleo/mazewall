package io.mazewall.spring;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.security.authorization.method.AuthorizationManagerAfterMethodInterceptor;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.TransactionDefinition;

/** Narrow optional upstream PreAuthorize/PostAuthorize advice occupying the reviewed inside-transaction authorization slot. */
public final class SpringSecurityMethodAdvice implements MethodInterceptor, AuthorizationRollbackContract {
    private static final String[] UNSUPPORTED = {
        "org.springframework.security.access.prepost.PreFilter", "org.springframework.security.access.prepost.PostFilter",
        "org.springframework.security.access.annotation.Secured", "jakarta.annotation.security.RolesAllowed",
        "javax.annotation.security.RolesAllowed", "org.springframework.security.authorization.method.HandleAuthorizationDenied"
    };
    private final AuthorizationManagerBeforeMethodInterceptor before = AuthorizationManagerBeforeMethodInterceptor.preAuthorize();
    private final AuthorizationManagerAfterMethodInterceptor after = AuthorizationManagerAfterMethodInterceptor.postAuthorize();

    private SpringSecurityMethodAdvice() {}

    /** Explicit application opt-in. No authentication, URL security or holder strategy is configured. */
    public static Advisor authorizationAdvisor() {
        return new DefaultPointcutAdvisor(new SpringSecurityMethodAdvice());
    }

    @Override public void validateRollback(TransactionAttribute attributes, Method method, Class<?> target) {
        Method actual = BridgeMethodResolver.findBridgedMethod(AopUtils.getMostSpecificMethod(method, target));
        requireSupported(method, actual, target);
        if (!attributes.rollbackOn(new AccessDeniedException("authorization rollback compatibility probe"))
                || !attributes.rollbackOn(new AuthorizationDeniedException("authorization rollback compatibility probe"))) {
            throw new IllegalStateException("Effective rollback rules suppress supported method-authorization denial");
        }
        if (postAuthorized(method, actual, target)) {
            switch (attributes.getPropagationBehavior()) {
                case TransactionDefinition.PROPAGATION_REQUIRED, TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                     TransactionDefinition.PROPAGATION_NESTED, TransactionDefinition.PROPAGATION_MANDATORY -> { }
                default -> throw new IllegalStateException("Supported post-authorization requires a physical JDBC transaction before target entry");
            }
        }
    }

    @Override public Object invoke(MethodInvocation invocation) throws Throwable {
        Class<?> target = invocation.getThis().getClass();
        Method actual = BridgeMethodResolver.findBridgedMethod(AopUtils.getMostSpecificMethod(invocation.getMethod(), target));
        requireSupported(invocation.getMethod(), actual, target);
        return before.invoke(new MethodInvocation() {
            @Override public Method getMethod() { return invocation.getMethod(); }
            @Override public Object[] getArguments() { return invocation.getArguments(); }
            @Override public Object getThis() { return invocation.getThis(); }
            @Override public AccessibleObject getStaticPart() { return invocation.getStaticPart(); }
            @Override public Object proceed() throws Throwable { return after.invoke(invocation); }
        });
    }

    private static void requireSupported(Method contract, Method actual, Class<?> target) {
        for (AnnotatedElement element : new AnnotatedElement[]{contract, contract.getDeclaringClass(), actual, target}) {
            var annotations = MergedAnnotations.from(element, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY);
            for (String name : UNSUPPORTED) {
                if (annotations.isPresent(name)) {
                    throw new IllegalStateException("Unsupported method-security annotation for synchronous reviewed advice: " + name);
                }
            }
        }
    }

    private static boolean postAuthorized(Method contract, Method actual, Class<?> target) {
        for (AnnotatedElement element : new AnnotatedElement[]{contract, contract.getDeclaringClass(), actual, target}) {
            if (MergedAnnotations.from(element, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY)
                    .isPresent("org.springframework.security.access.prepost.PostAuthorize")) return true;
        }
        return false;
    }
}
