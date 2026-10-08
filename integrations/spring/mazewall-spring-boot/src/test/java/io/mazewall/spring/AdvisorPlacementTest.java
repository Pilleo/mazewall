package io.mazewall.spring;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.MatchAlwaysTransactionAttributeSource;
import org.springframework.transaction.interceptor.RuleBasedTransactionAttribute;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AdvisorPlacementTest {
    interface Operation { String run(); }
    record Event(String phase, long thread) {}

    /** Dedicated uncontained platform worker: deliberately not a kernel fixture. */
    static final class HostDispatch implements InvocationDispatcher, AutoCloseable {
        final List<Event> events;
        final ThreadLocal<Boolean> active = new ThreadLocal<>();
        Runnable beforeBody = () -> {};
        final ExecutorService worker = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("spring-host-proof").inheritInheritableThreadLocals(false).factory());
        HostDispatch(List<Event> events) { this.events = events; }
        public Object call(Body body) throws Throwable {
            if (active.get() != null) return body.proceed();
            events.add(new Event("dispatch", Thread.currentThread().threadId()));
            try {
                return worker.submit(() -> {
                    active.set(true);
                    try {
                        beforeBody.run();
                        return body.proceed();
                    }
                    catch (Throwable failure) { return AdvisorPlacementTest.<Object, RuntimeException>raise(failure); }
                    finally { active.remove(); }
                }).get();
            } catch (ExecutionException failed) { throw failed.getCause(); }
        }
        public void close() { worker.close(); }
    }

    static final class Transactions extends AbstractPlatformTransactionManager {
        final List<Event> events;
        final ThreadLocal<Boolean> running = new ThreadLocal<>();
        Transactions(List<Event> events) { this.events = events; }
        protected Object doGetTransaction() { return running.get() == null ? Boolean.FALSE : Boolean.TRUE; }
        protected boolean isExistingTransaction(Object transaction) { return Boolean.TRUE.equals(transaction); }
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            running.set(true);
            record("begin");
        }
        protected void doCommit(DefaultTransactionStatus status) { record("commit"); }
        protected void doRollback(DefaultTransactionStatus status) { record("rollback"); }
        protected void doCleanupAfterCompletion(Object transaction) { running.remove(); }
        void record(String phase) { events.add(new Event(phase, Thread.currentThread().threadId())); }
    }

    static final class Fixture {
        final List<Event> events;
        final AtomicInteger calls = new AtomicInteger();
        final RuntimeException business = new IllegalArgumentException("business");
        final Operation target;
        final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        final BoundInvocationInterceptor boundary;
        final Advisor transaction;
        final Advisor diagnosis;
        final Advisor authorization;
        final ProxyFactory factory;
        final Operation proxy;
        Runnable nested = () -> {};
        Fixture(InvocationDispatcher dispatcher, List<Event> events, boolean fail, boolean deny) {
            this(dispatcher, events, fail, deny, new Transactions(events), factory -> {});
        }
        Fixture(InvocationDispatcher dispatcher, List<Event> events, boolean fail, boolean deny,
                Transactions manager, java.util.function.Consumer<ProxyFactory> configure) {
            this.events = events;
            target = () -> {
                calls.incrementAndGet();
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                record("body");
                nested.run();
                if (fail) throw business;
                return "existing-singleton";
            };
            boundary = new BoundInvocationInterceptor(dispatcher);
            var attributes = new MatchAlwaysTransactionAttributeSource();
            attributes.setTransactionAttribute(new RuleBasedTransactionAttribute());
            transaction = new DefaultPointcutAdvisor(new TransactionInterceptor(manager, attributes));
            diagnosis = new DefaultPointcutAdvisor((MethodInterceptor) invocation -> {
                try { return invocation.proceed(); }
                finally { record("diagnosis"); }
            });
            authorization = new DefaultPointcutAdvisor((MethodInterceptor) invocation -> {
                Object value = invocation.proceed();
                record("authorization");
                if (deny) throw business;
                return value;
            });
            beans.registerSingleton("operation", target);
            factory = new ProxyFactory(beans.getBean("operation", Operation.class));
            factory.addAdvisor(boundary.dispatchAdvisor());
            factory.addAdvisor(transaction);
            factory.addAdvisor(diagnosis);
            factory.addAdvisor(authorization);
            configure.accept(factory);
            factory.setFrozen(true);
            proxy = (Operation) factory.getProxy();
        }
        void bind() throws Exception {
            boundary.bind(proxy, transaction, diagnosis, authorization, List.of(Operation.class.getMethod("run")));
        }
        void record(String phase) { events.add(new Event(phase, Thread.currentThread().threadId())); }
    }

    @Test void cloneKeepsExistingBeanAndFullChainOnWorkerExactlyOnce() throws Throwable {
        List<Event> events = new ArrayList<>();
        try (var dispatch = new HostDispatch(events)) {
            Fixture fixture = new Fixture(dispatch, events, false, false);
            fixture.bind();
            assertEquals("existing-singleton", fixture.proxy.run());
            assertEquals(1, fixture.calls.get());
            assertEquals(List.of("dispatch", "begin", "body", "authorization", "diagnosis", "commit"),
                    events.stream().map(Event::phase).toList());
            long worker = events.get(1).thread();
            assertNotEquals(Thread.currentThread().threadId(), worker);
            assertTrue(events.subList(1, events.size()).stream().allMatch(event -> event.thread() == worker));
            assertSame(fixture.target, ((org.springframework.aop.framework.Advised) fixture.proxy).getTargetSource().getTarget());
            assertTrue(fixture.beans.isSingleton("operation"));
            assertSame(fixture.target, fixture.beans.getBean("operation"));
            System.out.println("S0 HOST ONLY placement: " + events);
        }
    }

    @Test void businessFailureAndPostAuthorizationDenialRollbackInsideWorker() throws Exception {
        for (boolean fail : List.of(true, false)) {
            List<Event> events = new ArrayList<>();
            try (var dispatch = new HostDispatch(events)) {
                Fixture fixture = new Fixture(dispatch, events, fail, !fail);
                fixture.bind();
                assertSame(fixture.business, assertThrows(IllegalArgumentException.class, fixture.proxy::run));
                assertEquals(1, fixture.calls.get());
                assertEquals("rollback", events.getLast().phase());
                assertTrue(events.subList(1, events.size()).stream().allMatch(event -> event.thread() == events.get(1).thread()));
            }
        }
    }

    @Test void unboundInvocationIsRejectedBeforeBody() {
        List<Event> events = new ArrayList<>();
        try (var dispatch = new HostDispatch(events)) {
            Fixture fixture = new Fixture(dispatch, events, false, false);
            assertThrows(IllegalStateException.class, fixture.proxy::run);
            assertEquals(0, fixture.calls.get());
        }
    }

    @Test void rejectsWrongOrderUnknownAdviceAndMutableProxyBeforeEntry() {
        List<Event> events = new ArrayList<>();
        try (var dispatch = new HostDispatch(events)) {
            Fixture reordered = new Fixture(dispatch, events, false, false, new Transactions(events), factory -> {
                Advisor tx = factory.getAdvisors()[1];
                factory.removeAdvisor(tx);
                factory.addAdvisor(0, tx);
            });
            assertThrows(IllegalStateException.class, reordered::bind);
            Fixture unknown = new Fixture(dispatch, events, false, false, new Transactions(events), factory ->
                    factory.addAdvice((MethodInterceptor) invocation -> invocation.proceed()));
            assertThrows(IllegalStateException.class, unknown::bind);
            Fixture mutable = new Fixture(dispatch, events, false, false);
            mutable.factory.setFrozen(false);
            assertThrows(IllegalStateException.class, mutable::bind);
        }
    }

    @Test void changedAdviceFailsBeforeTransactionOrTargetIncludingAfterQueueing() throws Exception {
        for (boolean afterQueue : List.of(false, true)) {
            List<Event> events = new ArrayList<>();
            try (var dispatch = new HostDispatch(events)) {
                Fixture fixture = new Fixture(dispatch, events, false, false);
                fixture.bind();
                Runnable drift = () -> ((DefaultPointcutAdvisor) fixture.diagnosis).setAdvice(
                        (MethodInterceptor) invocation -> invocation.proceed());
                if (afterQueue) dispatch.beforeBody = drift;
                else drift.run();
                assertThrows(IllegalStateException.class, fixture.proxy::run);
                assertEquals(0, fixture.calls.get());
                assertTrue(events.stream().noneMatch(event -> event.phase().equals("begin")));
            }
        }
    }

    @Test void nestedProxyTopologyAndLazyResultShapeHaveNoProof() throws Exception {
        List<Event> events = new ArrayList<>();
        try (var dispatch = new HostDispatch(events)) {
            Fixture fixture = new Fixture(dispatch, events, false, false);
            ProxyFactory nested = new ProxyFactory(fixture.proxy);
            for (Advisor advisor : fixture.factory.getAdvisors()) nested.addAdvisor(advisor);
            nested.setFrozen(true);
            Object outer = nested.getProxy();
            assertThrows(IllegalStateException.class, () -> fixture.boundary.bind(outer,
                    fixture.transaction, fixture.diagnosis, fixture.authorization,
                    List.of(Operation.class.getMethod("run"))));
            assertEquals(0, fixture.calls.get());
            assertThrows(IllegalStateException.class, () -> fixture.boundary.bind(fixture.proxy,
                    fixture.transaction, fixture.diagnosis, fixture.authorization,
                    List.of(LazyOperation.class.getMethod("run"))));
        }
    }

    interface LazyOperation { java.util.stream.Stream<String> run(); }

    @Test void provedNestedBeanCallsUseOneWorkerAndOneDispatch() throws Exception {
        List<Event> events = new ArrayList<>();
        try (var dispatch = new HostDispatch(events)) {
            Transactions manager = new Transactions(events);
            Fixture inner = new Fixture(dispatch, events, false, false, manager, factory -> {});
            Fixture outer = new Fixture(dispatch, events, false, false, manager, factory -> {});
            inner.bind();
            outer.bind();
            outer.nested = () -> assertEquals("existing-singleton", inner.proxy.run());
            assertEquals("existing-singleton", outer.proxy.run());
            assertEquals(1, inner.calls.get());
            assertEquals(1, outer.calls.get());
            assertEquals(1, events.stream().filter(event -> event.phase().equals("dispatch")).count());
            assertEquals(1, events.stream().filter(event -> event.phase().equals("begin")).count());
            assertEquals(1, events.stream().filter(event -> event.phase().equals("commit")).count());
            long worker = events.get(1).thread();
            assertTrue(events.subList(1, events.size()).stream().allMatch(event -> event.thread() == worker));
            System.out.println("S0 HOST ONLY nested chain: " + events);
        }
    }

    @Test void differentExecutionBindingRejectsNestedBodyWithoutDeadlock() throws Exception {
        List<Event> events = new ArrayList<>();
        try (var first = new HostDispatch(events); var second = new HostDispatch(events)) {
            Fixture inner = new Fixture(second, events, false, false);
            Fixture outer = new Fixture(first, events, false, false);
            inner.bind();
            outer.bind();
            outer.nested = inner.proxy::run;
            assertThrows(IllegalStateException.class, outer.proxy::run);
            assertEquals(0, inner.calls.get());
            assertEquals("rollback", events.getLast().phase());
        }
    }

    @Test void rejectsExternalThreadBoundTransactionAndResources() throws Exception {
        List<Event> events = new ArrayList<>();
        try (var dispatch = new HostDispatch(events)) {
            Fixture fixture = new Fixture(dispatch, events, false, false);
            fixture.bind();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            try { assertThrows(IllegalStateException.class, fixture.proxy::run); }
            finally { TransactionSynchronizationManager.clear(); }
            Object key = new Object();
            TransactionSynchronizationManager.bindResource(key, new Object());
            try { assertThrows(IllegalStateException.class, fixture.proxy::run); }
            finally { TransactionSynchronizationManager.unbindResource(key); }
            assertEquals(0, fixture.calls.get());
            assertTrue(events.isEmpty());
        }
    }

    @Test void callerRunsDispatcherMustNotInvokeProtectedChain() throws Exception {
        List<Event> events = new ArrayList<>();
        Fixture fixture = new Fixture(InvocationDispatcher.Body::proceed, events, false, false);
        fixture.bind();
        assertThrows(IllegalStateException.class, fixture.proxy::run);
        assertEquals(0, fixture.calls.get());
    }

    @Test void publicEntryOwnsFreshFrozenProxyAroundTheRegisteredBean() throws Exception {
        List<Event> events = new ArrayList<>();
        try (var dispatch = new HostDispatch(events)) {
            Fixture fixture = new Fixture(dispatch, events, false, false);
            Operation proxy = (Operation) fixture.boundary.createProxy(fixture.beans.getBean("operation"),
                    fixture.transaction, fixture.diagnosis, fixture.authorization,
                    List.of(Operation.class.getMethod("run")));
            assertEquals("existing-singleton", proxy.run());
            assertEquals(1, fixture.calls.get());
            assertTrue(((org.springframework.aop.framework.Advised) proxy).isFrozen());
            assertThrows(IllegalStateException.class, () -> fixture.boundary.createProxy(fixture.target,
                    fixture.transaction, fixture.diagnosis, fixture.authorization,
                    List.of(Operation.class.getMethod("run"))));
            assertEquals(List.of("dispatch", "begin", "body", "authorization", "diagnosis", "commit"),
                    events.stream().map(Event::phase).toList());
        }
    }

    @Test void changedTransactionDefinitionOrPointcutFailsBeforeEntry() throws Exception {
        for (boolean metadata : List.of(false, true)) {
            List<Event> events = new ArrayList<>();
            try (var dispatch = new HostDispatch(events)) {
                Fixture fixture = new Fixture(dispatch, events, false, false);
                fixture.bind();
                if (metadata) {
                    var tx = (TransactionInterceptor) fixture.transaction.getAdvice();
                    var source = (MatchAlwaysTransactionAttributeSource) tx.getTransactionAttributeSource();
                    var changed = new RuleBasedTransactionAttribute();
                    changed.setPropagationBehavior(TransactionDefinition.PROPAGATION_SUPPORTS);
                    source.setTransactionAttribute(changed);
                } else {
                    ((DefaultPointcutAdvisor) fixture.authorization).setPointcut(
                            new org.springframework.aop.support.NameMatchMethodPointcut().addMethodName("other"));
                }
                assertThrows(IllegalStateException.class, fixture.proxy::run);
                assertEquals(0, fixture.calls.get());
                assertTrue(events.isEmpty());
            }
        }
    }

    @Test void virtualCallerOffloadsToPlatformWorker() throws Exception {
        List<Event> events = new ArrayList<>();
        try (var dispatch = new HostDispatch(events); var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            Fixture fixture = new Fixture(dispatch, events, false, false);
            fixture.bind();
            assertEquals("existing-singleton", callers.submit(fixture.proxy::run).get());
            assertNotEquals(events.getFirst().thread(), events.get(1).thread());
            assertEquals(1, fixture.calls.get());
        }
    }

    @Test void dispatcherCannotReplayTheSpringClone() throws Exception {
        List<Event> events = new ArrayList<>();
        try (var dispatch = new HostDispatch(events)) {
            InvocationDispatcher replaying = body -> dispatch.call(() -> {
                body.proceed();
                return body.proceed();
            });
            Fixture fixture = new Fixture(replaying, events, false, false);
            fixture.bind();
            IllegalStateException rejected = assertThrows(IllegalStateException.class, fixture.proxy::run);
            assertTrue(rejected.getMessage().contains("replay"));
            assertEquals(1, fixture.calls.get());
            assertEquals(1, events.stream().filter(event -> event.phase().equals("begin")).count());
        }
    }

    @Test void failedCallClearsBoundaryStateBeforeTheWorkerIsReused() throws Exception {
        List<Event> events = new ArrayList<>();
        try (var dispatch = new HostDispatch(events)) {
            Fixture failing = new Fixture(dispatch, events, true, false);
            Fixture next = new Fixture(body -> dispatch.call(body), events, false, false);
            failing.bind();
            next.bind();
            assertSame(failing.business, assertThrows(IllegalArgumentException.class, failing.proxy::run));
            assertEquals("existing-singleton", next.proxy.run());
            assertEquals(1, next.calls.get());
            List<Long> bodyThreads = events.stream().filter(event -> event.phase().equals("body"))
                    .map(Event::thread).toList();
            assertEquals(bodyThreads.getFirst(), bodyThreads.getLast());
        }
    }

    @Test void nestedDifferentTransactionManagerHasNoPlacementProof() throws Exception {
        List<Event> events = new ArrayList<>();
        try (var dispatch = new HostDispatch(events)) {
            Fixture inner = new Fixture(dispatch, events, false, false);
            Fixture outer = new Fixture(dispatch, events, false, false);
            inner.bind();
            outer.bind();
            outer.nested = inner.proxy::run;
            assertThrows(IllegalStateException.class, outer.proxy::run);
            assertEquals(0, inner.calls.get());
            assertEquals("rollback", events.getLast().phase());
        }
    }

    @SuppressWarnings("unchecked")
    static <T, E extends Throwable> T raise(Throwable failure) throws E { throw (E) failure; }
}
