package io.mazewall.spring;

import static org.junit.jupiter.api.Assertions.*;

import io.mazewall.enforcer.api.RuntimeProfile;
import io.mazewall.spring.fixture.SharedOperation;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.aopalliance.intercept.MethodInterceptor;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.Transactional;

/** Ordinary parent/child loaders and real Spring/H2 binding; no native protection is installed. */
class JdbcProxyClassLoaderTest {
    @TempDir Path root;

    @Test void sharedParentContractUsesApplicationProxyLoaderAndStillRequiresExactBinding() throws Exception {
        var urls = new ArrayList<URL>();
        urls.add(jar(Service.class));
        urls.add(getClass().getProtectionDomain().getCodeSource().getLocation());
        String classpath = System.getProperty("mazewall.spring.publicCompileClasspath");
        assertNotNull(classpath);
        for (String entry : classpath.split(File.pathSeparator)) urls.add(Path.of(entry).toUri().toURL());
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (var shared = new URLClassLoader(new URL[] {jar(SharedOperation.class)}, ClassLoader.getPlatformClassLoader());
             var application = new URLClassLoader(urls.toArray(URL[]::new), shared)) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName("org.springframework.aop.SpringProxy", false, shared));
            assertSame(shared, Class.forName(SharedOperation.class.getName(), false, application).getClassLoader());
            assertSame(application, Class.forName(SpringOperationBinder.class.getName(), false, application).getClassLoader());
            Thread.currentThread().setContextClassLoader(application);
            Class<?> probe = Class.forName(ApplicationProbe.class.getName(), true, application);
            try { probe.getMethod("run").invoke(null); }
            catch (InvocationTargetException failure) { throw new AssertionError("Application-loader binding failed", failure.getCause()); }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private URL jar(Class<?> template) throws Exception {
        Path file = root.resolve(template.getSimpleName() + ".jar");
        String entry = template.getName().replace('.', '/') + ".class";
        try (var input = template.getResourceAsStream("/" + entry);
             var output = new JarOutputStream(Files.newOutputStream(file))) {
            assertNotNull(input);
            output.putNextEntry(new JarEntry(entry));
            input.transferTo(output);
            output.closeEntry();
        }
        return file.toUri().toURL();
    }

    public static class Service implements SharedOperation {
        private int calls;
        @MazewallOperation("write") @Transactional(transactionManager = "jdbc")
        public String run() { calls++; return "shared API result"; }
    }

    /** Public only for invocation across the isolated application-loader boundary. */
    public static class ApplicationProbe {
        public static void run() throws Exception {
            var target = new Service();
            var beans = new DefaultListableBeanFactory();
            var data = new JdbcDataSource();
            data.setURL("jdbc:h2:mem:" + UUID.randomUUID());
            var manager = new DataSourceTransactionManager(data);
            beans.registerSingleton("jdbc", manager);
            var guard = new SpringTransactionGuard(beans, "jdbc", new SpringTransactionEffects());
            var artifact = OperationBindingTest.artifact(target, Service.class.getMethod("run"), "operation",
                    SpringOperationBinder.classResourceSha256(Service.class));
            var authorization = new DefaultPointcutAdvisor((MethodInterceptor) invocation -> invocation.proceed());
            try (var dispatch = new AdvisorPlacementTest.HostDispatch(new ArrayList<>())) {
                var binder = SpringOperationBinder.forTrustedHostCharacterization(beans, dispatch, artifact, guard);
                SharedOperation proxy = binder.createJdbcProxy(SharedOperation.class, target, authorization);
                Advised advised = (Advised) proxy;
                assertTrue(advised.isFrozen());
                assertSame(Thread.currentThread().getContextClassLoader(), proxy.getClass().getClassLoader());
                assertSame(target, advised.getTargetSource().getTarget());
                assertArrayEquals(new Advisor[] {binder.dispatchAdvisor(), guard.transactionAdvisor(),
                        guard.diagnosisAdvisor(), authorization}, advised.getAdvisors());
                assertThrows(IllegalStateException.class, proxy::run);
                assertEquals(0, target.calls);
                beans.registerSingleton("operation", proxy);
                assertThrows(IllegalStateException.class, () -> binder.validateRequiredBindings(List.of("operation")));
                binder.bind(new SpringOperationBinder.Approval(artifact, proxy, Service.class.getClassLoader(),
                        RuntimeProfile.HOTSPOT_JIT, "resources-v1", Set.of(SpringOperationBinder.Phase.BODY,
                        SpringOperationBinder.Phase.TRANSACTION_COMPLETION)));
                binder.validateRequiredBindings(List.of("operation"));
                assertSame(proxy, beans.getBean("operation"));
                assertSame(manager, beans.getBean("jdbc"));
                assertEquals("shared API result", proxy.run());
                assertEquals(1, target.calls);
            }
        }
    }
}
