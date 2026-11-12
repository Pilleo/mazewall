package io.mazewall.spring;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.mazewall.enforcer.api.*;
import io.mazewall.enforcer.api.execution.*;
import io.mazewall.enforcer.internal.ffi.RealPlatformProvider;
import io.mazewall.ffi.NativeConstants;
import io.mazewall.platform.syscall.Arch;
import io.mazewall.platform.syscall.PrctlCommand;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;

class OwnerBoundDomainLifecycleTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Test void rejectsMissingRuntimeBeforePreparation() {
        var owner = ManagedRuntimeAcquisitionOwner.create();
        try {
            assertThrows(NullPointerException.class, () -> new DomainLifecycle(
                    null, owner, null, null, null, null, null));
        } finally { owner.close(); }
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    @Test void stopWinsWhileGenuinePrepareIsHeldThenOriginalCallerHandsOff(@TempDir Path dir) throws Exception {
        var capability = RealPlatformProvider.INSTANCE.getLandlockCapability();
        assumeTrue(capability instanceof KernelCapability.Available<?> available && ((Number) available.getValue()).intValue() >= 3);
        var linuxType = Class.forName("io.mazewall.platform.native.LinuxNative");
        var linux = linuxType.getField("INSTANCE").get(null);
        linuxType.getMethod("resetToDefault").invoke(linux);
        var real = linuxType.getMethod("snapshot").invoke(linux);
        var nativeEngine = Class.forName("io.mazewall.platform.native.NativeEngine");
        var rawType = Class.forName("io.mazewall.platform.native.RawSyscallOperations");
        var processType = Class.forName("io.mazewall.platform.native.NativeProcess");
        var fileSystemType = Class.forName("io.mazewall.platform.native.NativeFileSystem");
        var success = Class.forName("io.mazewall.platform.native.LinuxNative$SyscallResult$Success")
                .getConstructor(Object.class, List.class, int.class);
        var nativeError = Class.forName("io.mazewall.platform.native.LinuxNative$SyscallResult$Error")
                .getConstructor(int.class, long.class);
        var realRaw = nativeEngine.getMethod("getRaw").invoke(real);
        var realProcess = nativeEngine.getMethod("getProcess").invoke(real);
        var realFileSystem = nativeEngine.getMethod("getFileSystem").invoke(real);
        var closes = new AtomicInteger();
        var closeFailure = new AtomicReference<Error>();
        var fileSystem = Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { fileSystemType },
                (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        closes.incrementAndGet();
                        Object result = invoke(realFileSystem, method, args);
                        Error failure = closeFailure.getAndSet(null);
                        if (failure != null) throw failure;
                        return result;
                    }
                    return invoke(realFileSystem, method, args);
                });
        var reached = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var block = new AtomicBoolean();
        var activeReached = new AtomicReference<>(reached);
        var activeRelease = new AtomicReference<>(release);
        var raw = Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { rawType }, (proxy, method, args) -> {
                    if (method.getName().startsWith("syscall") && args != null && args.length > 0) {
                        long nr = ((Number) args[0]).longValue();
                        if (nr == NativeConstants.LANDLOCK_CREATE_RULESET_NR && block.compareAndSet(true, false)) {
                            activeReached.get().countDown();
                            boolean interrupted = false;
                            try {
                                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                                for (;;) {
                                    try {
                                        long remaining = deadline - System.nanoTime();
                                        if (remaining <= 0 || !activeRelease.get().await(remaining, TimeUnit.NANOSECONDS))
                                            throw new AssertionError("prepare barrier timed out");
                                        break;
                                    } catch (InterruptedException ignored) { interrupted = true; }
                                }
                            } finally {
                                if (interrupted) Thread.currentThread().interrupt();
                            }
                        }
                        if (nr == Arch.Companion.current(System.getProperty("os.arch")).getSeccompSyscallNumber()
                                || nr == NativeConstants.LANDLOCK_RESTRICT_SELF_NR)
                            return success.newInstance(0L, List.of(), 0);
                    }
                    return invoke(realRaw, method, args);
                });
        var process = Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { processType }, (proxy, method, args) -> {
                    if (method.getName().equals("prctl"))
                        return success.newInstance(args[0] instanceof PrctlCommand.GetSeccomp ? 2L : 0L, List.of(), 0);
                    return invoke(realProcess, method, args);
                });
        var engine = Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { nativeEngine }, (proxy, method, args) -> switch (method.getName()) {
                    case "getRaw" -> raw;
                    case "getProcess" -> process;
                    case "getFileSystem" -> fileSystem;
                    default -> invoke(real, method, args);
                });
        var provider = (PlatformProvider) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { PlatformProvider.class }, (proxy, method, args) -> switch (method.getName()) {
                    case "getOsName" -> "Linux";
                    case "hasKernelSeccompSupport", "probeSeccompTsync", "probeSeccompUserNotif" -> true;
                    case "probeCetSupported" -> false;
                    case "checkSeccompSanity" -> nativeError.newInstance(22, -1L);
                    case "getLandlockCapability" -> capability;
                    default -> invoke(RealPlatformProvider.INSTANCE, method, args);
                });
        linuxType.getMethod("setEngine", nativeEngine).invoke(linux, engine);
        Platform.class.getMethod("setProvider$io_mazewall_enforcer", PlatformProvider.class).invoke(null, provider);
        var source = dir.resolve("DirectWorker.java");
        Files.writeString(source, "package fixture; public class DirectWorker { public int call() { return 7; } }");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", dir.toString(), source.toString()));
        var jar = dir.resolve("worker.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("fixture/DirectWorker.class"));
            out.write(Files.readAllBytes(dir.resolve("fixture/DirectWorker.class")));
            out.closeEntry();
        }
        var owner = ManagedRuntimeAcquisitionOwner.create();
        var resolution = PreparedResolutionOwner.create();
        var returned = new CountDownLatch(1);
        var callerRelease = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        Thread caller = null;
        try (var loader = new URLClassLoader(new java.net.URL[] { jar.toUri().toURL() }, ClassLoader.getPlatformClassLoader())) {
            var policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(jar).build();
            var resources = resolution.resolve(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("fixture.DirectWorker"), Set.of());
            var binding = new OperationBinding("direct", OperationAdapter.CALLABLE, resources.getImplementationSha256(),
                    resources.getResourceGeneration(), Map.of("entryPoint", "call()I"));
            var document = new OperationPolicyDocument(binding,
                    CanonicalPolicyArtifact.Companion.worker(RuntimeProfile.HOTSPOT_JIT, policy), Set.of(), Map.of());
            var reviewed = ReviewedOperationArtifact.load(document.toJson(),
                    new TrustedPolicyReview("test-only", document.sha256(), Set.of()), binding);
            var runtime = owner.start(TIMEOUT, TIMEOUT);
            var unrelated = ManagedRuntimeAcquisitionOwner.create();
            try {
                int beforeMismatch = closes.get();
                assertThrows(IllegalArgumentException.class, () -> new DomainLifecycle(runtime, unrelated, reviewed,
                        new DomainLimits(1, 1), resources, TIMEOUT, InvocationContextBridge.none()));
                assertEquals(beforeMismatch, closes.get());
            } finally { unrelated.close(); }
            var lifecycle = new DomainLifecycle(runtime, owner, reviewed, new DomainLimits(1, 1), resources,
                    TIMEOUT, InvocationContextBridge.none());
            int beforeFailedRefresh = closes.get();
            var failedContext = new GenericApplicationContext();
            failedContext.getBeanFactory().registerSingleton("lifecycle", lifecycle);
            var refreshFailure = new IllegalStateException("synthetic bean creation failure");
            failedContext.registerBean("failingBean", Object.class, () -> { throw refreshFailure; });
            var observedRefresh = assertThrows(org.springframework.beans.BeansException.class, failedContext::refresh);
            assertSame(refreshFailure, observedRefresh.getMostSpecificCause());
            failedContext.close();
            assertFalse(lifecycle.isRunning());
            assertInstanceOf(ManagedRuntimeAcquisitionStatus.Active.class, owner.status());
            assertEquals(beforeFailedRefresh, closes.get());
            var context = new GenericApplicationContext();
            context.getBeanFactory().registerSingleton("lifecycle", lifecycle);
            context.refresh();
            block.set(true);
            caller = Thread.ofPlatform().unstarted(() -> {
                try { lifecycle.start(); returned.countDown(); callerRelease.await(); }
                catch (Throwable thrown) { failure.set(thrown); returned.countDown(); }
            });
            caller.start();
            assertTrue(reached.await(10, TimeUnit.SECONDS));
            int beforeStopCloses = closes.get();
            var callbacks = new AtomicInteger();
            lifecycle.stop(callbacks::incrementAndGet);
            assertEquals(1, callbacks.get());
            assertEquals(beforeStopCloses, closes.get());
            context.close();
            assertEquals(beforeStopCloses, closes.get());
            assertFalse(lifecycle.isRunning());
            assertThrows(DomainLifecycle.InactiveDomainException.class, lifecycle::dispatcher);
            assertEquals(DomainLifecycle.OwnerRetirementObservation.BeforeResult.IN_FLIGHT,
                    ((DomainLifecycle.OwnerRetirementObservation.Unobserved) lifecycle.retirement()).phase());
            assertInstanceOf(ManagedRuntimeAcquisitionStatus.Active.class, owner.status());
            assertThrows(IllegalStateException.class, () -> lifecycle.retryRetirement(TIMEOUT));
            release.countDown();
            assertTrue(returned.await(10, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertTrue(caller.isAlive());
            assertFalse(lifecycle.isRunning());
            assertInstanceOf(ManagedRuntimeAcquisitionStatus.Complete.class,
                    ((DomainLifecycle.OwnerRetirementObservation.OwnerResult) lifecycle.retryRetirement(TIMEOUT)).status());
            assertTrue(caller.isAlive());

            var secondOwner = ManagedRuntimeAcquisitionOwner.create();
            var secondResolution = PreparedResolutionOwner.create();
            var workerEntered = new CountDownLatch(1);
            var workerRelease = new CountDownLatch(1);
            var workerThread = new AtomicReference<Thread>();
            var workerFailure = new AtomicReference<Throwable>();
            Thread invocation = null;
            Thread retryCaller = null;
            try {
                var secondResources = secondResolution.resolve(RuntimeProfile.HOTSPOT_JIT, policy,
                        loader.loadClass("fixture.DirectWorker"), Set.of());
                var secondBinding = new OperationBinding("second", OperationAdapter.CALLABLE,
                        secondResources.getImplementationSha256(), secondResources.getResourceGeneration(),
                        Map.of("entryPoint", "call()I"));
                var secondDocument = new OperationPolicyDocument(secondBinding,
                        CanonicalPolicyArtifact.Companion.worker(RuntimeProfile.HOTSPOT_JIT, policy), Set.of(), Map.of());
                var secondReviewed = ReviewedOperationArtifact.load(secondDocument.toJson(),
                        new TrustedPolicyReview("test-only", secondDocument.sha256(), Set.of()), secondBinding);
                var secondRuntime = secondOwner.start(TIMEOUT, TIMEOUT);
                var ready = new DomainLifecycle(secondRuntime, secondOwner, secondReviewed, new DomainLimits(1, 1),
                        secondResources, Duration.ofMillis(20), InvocationContextBridge.none());
                var activeContext = new GenericApplicationContext();
                activeContext.getBeanFactory().registerSingleton("lifecycle", ready);
                activeContext.refresh();
                ready.start();
                assertTrue(ready.isRunning());
                invocation = Thread.ofPlatform().unstarted(() -> {
                    try {
                        ready.dispatcher().call(() -> {
                            workerThread.set(Thread.currentThread());
                            workerEntered.countDown();
                            assertTrue(workerRelease.await(10, TimeUnit.SECONDS));
                            return null;
                        });
                    } catch (Throwable thrown) { workerFailure.set(thrown); }
                });
                invocation.start();
                assertTrue(workerEntered.await(10, TimeUnit.SECONDS));
                activeContext.close();
                assertFalse(activeContext.isActive());
                assertFalse(ready.isRunning());
                assertNull(ready.lastDrain());
                assertInstanceOf(ManagedRuntimeAcquisitionStatus.Pending.class,
                        ((DomainLifecycle.OwnerRetirementObservation.OwnerResult) ready.retirement()).status());
                assertTrue(workerThread.get().isAlive());
                var retryResult = new AtomicReference<DomainLifecycle.OwnerRetirementObservation>();
                var retryFailure = new AtomicReference<Throwable>();
                retryCaller = Thread.ofPlatform().unstarted(() -> {
                    try { retryResult.set(ready.retryRetirement(TIMEOUT)); }
                    catch (Throwable thrown) { retryFailure.set(thrown); }
                });
                retryCaller.start();
                var inFlight = DomainLifecycle.class.getDeclaredField("retirementInFlight");
                inFlight.setAccessible(true);
                long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean observedInFlight = false;
                while (System.nanoTime() < waitDeadline) {
                    synchronized (ready) { observedInFlight = inFlight.getBoolean(ready); }
                    if (observedInFlight) break;
                    Thread.onSpinWait();
                }
                assertTrue(observedInFlight, "original retry was not reserved");
                assertEquals(DomainLifecycle.OwnerRetirementObservation.BeforeResult.IN_FLIGHT,
                        ((DomainLifecycle.OwnerRetirementObservation.Unobserved) ready.retirement()).phase());
                assertThrows(IllegalStateException.class, () -> ready.retryRetirement(TIMEOUT));
                assertTrue(workerThread.get().isAlive());
                workerRelease.countDown();
                invocation.join(10000);
                retryCaller.join(10000);
                assertFalse(invocation.isAlive());
                assertFalse(retryCaller.isAlive());
                assertNull(workerFailure.get());
                assertNull(retryFailure.get());
                assertInstanceOf(ManagedRuntimeAcquisitionStatus.Complete.class,
                        ((DomainLifecycle.OwnerRetirementObservation.OwnerResult) retryResult.get()).status());
                assertFalse(workerThread.get().isAlive());
                assertTrue(ready.diagnostics().getRetirementComplete());
            } finally {
                workerRelease.countDown();
                if (invocation != null) invocation.join(15000);
                if (retryCaller != null) retryCaller.join(15000);
                try { secondOwner.retire(TIMEOUT); } finally { secondResolution.close(); }
            }

            var thirdOwner = ManagedRuntimeAcquisitionOwner.create();
            var thirdResolution = PreparedResolutionOwner.create();
            try {
                var thirdResources = thirdResolution.resolve(RuntimeProfile.HOTSPOT_JIT, policy,
                        loader.loadClass("fixture.DirectWorker"), Set.of());
                var thirdBinding = new OperationBinding("third", OperationAdapter.CALLABLE,
                        thirdResources.getImplementationSha256(), thirdResources.getResourceGeneration(),
                        Map.of("entryPoint", "call()I"));
                var thirdDocument = new OperationPolicyDocument(thirdBinding,
                        CanonicalPolicyArtifact.Companion.worker(RuntimeProfile.HOTSPOT_JIT, policy), Set.of(), Map.of());
                var thirdReviewed = ReviewedOperationArtifact.load(thirdDocument.toJson(),
                        new TrustedPolicyReview("test-only", thirdDocument.sha256(), Set.of()), thirdBinding);
                var thirdRuntime = thirdOwner.start(TIMEOUT, TIMEOUT);
                var failing = new DomainLifecycle(thirdRuntime, thirdOwner, thirdReviewed, new DomainLimits(1, 1),
                        thirdResources, TIMEOUT, InvocationContextBridge.none());
                failing.start();
                var fatal = new AssertionError("actual-close-then-error");
                closeFailure.set(fatal);
                assertSame(fatal, assertThrows(AssertionError.class, failing::stop));
                assertNull(closeFailure.get());
                assertFalse(failing.diagnostics().getRetirementComplete());
                int afterFailure = closes.get();
                assertThrows(IllegalStateException.class, failing::stop);
                assertEquals(afterFailure, closes.get());
            } finally {
                closeFailure.set(null);
                try { thirdOwner.retire(TIMEOUT); } finally { thirdResolution.close(); }
            }

            var fourthOwner = ManagedRuntimeAcquisitionOwner.create();
            var fourthResolution = PreparedResolutionOwner.create();
            var timedEntered = new CountDownLatch(1);
            var timedRelease = new CountDownLatch(1);
            Thread timedCaller = null;
            try {
                var fourthResources = fourthResolution.resolve(RuntimeProfile.HOTSPOT_JIT, policy,
                        loader.loadClass("fixture.DirectWorker"), Set.of());
                var fourthBinding = new OperationBinding("fourth", OperationAdapter.CALLABLE,
                        fourthResources.getImplementationSha256(), fourthResources.getResourceGeneration(),
                        Map.of("entryPoint", "call()I"));
                var fourthDocument = new OperationPolicyDocument(fourthBinding,
                        CanonicalPolicyArtifact.Companion.worker(RuntimeProfile.HOTSPOT_JIT, policy), Set.of(), Map.of());
                var fourthReviewed = ReviewedOperationArtifact.load(fourthDocument.toJson(),
                        new TrustedPolicyReview("test-only", fourthDocument.sha256(), Set.of()), fourthBinding);
                var fourthRuntime = fourthOwner.start(Duration.ofMillis(500), TIMEOUT);
                var timed = new DomainLifecycle(fourthRuntime, fourthOwner, fourthReviewed, new DomainLimits(1, 1),
                        fourthResources, Duration.ofMillis(20), InvocationContextBridge.none());
                activeReached.set(timedEntered);
                activeRelease.set(timedRelease);
                block.set(true);
                var timedFailure = new AtomicReference<Throwable>();
                timedCaller = Thread.ofPlatform().unstarted(() -> {
                    try { timed.start(); } catch (Throwable thrown) { timedFailure.set(thrown); }
                });
                timedCaller.start();
                assertTrue(timedEntered.await(10, TimeUnit.SECONDS));
                timedCaller.join(5000);
                assertFalse(timedCaller.isAlive());
                assertInstanceOf(java.util.concurrent.TimeoutException.class, timedFailure.get());
                assertFalse(timed.isRunning());
                assertInstanceOf(ManagedRuntimeAcquisitionStatus.Pending.class,
                        ((DomainLifecycle.OwnerRetirementObservation.OwnerResult) timed.retirement()).status(),
                        "owner result=" + timed.retirement() + "; startup=" + timedFailure.get());
                var cleanupFatal = new AssertionError("timed-cleanup-close-error");
                closeFailure.set(cleanupFatal);
                timedRelease.countDown();
                assertSame(cleanupFatal, assertThrows(AssertionError.class, () -> timed.retryRetirement(TIMEOUT)));
                var timedAfter = ((DomainLifecycle.OwnerRetirementObservation.OwnerResult) timed.retryRetirement(TIMEOUT)).status();
                var unknown = assertInstanceOf(ManagedRuntimeAcquisitionStatus.Unknown.class, timedAfter);
                assertSame(cleanupFatal, unknown.getHistory());
                assertNotSame(cleanupFatal, timedFailure.get());
                var startupSlot = DomainLifecycle.class.getDeclaredField("startupFailure");
                startupSlot.setAccessible(true);
                assertSame(timedFailure.get(), startupSlot.get(timed));
                int afterUnknown = closes.get();
                var repeated = (ManagedRuntimeAcquisitionStatus.Unknown)
                        ((DomainLifecycle.OwnerRetirementObservation.OwnerResult) timed.retryRetirement(TIMEOUT)).status();
                assertSame(cleanupFatal, repeated.getHistory());
                var unknownCallback = new AtomicInteger();
                var statusException = assertThrows(RuntimeAcquisitionUnknownException.class,
                        () -> timed.stop(unknownCallback::incrementAndGet));
                assertSame(cleanupFatal, statusException.getStatus().getHistory());
                assertEquals(0, unknownCallback.get());
                assertEquals(afterUnknown, closes.get());
            } finally {
                timedRelease.countDown();
                closeFailure.set(null);
                if (timedCaller != null) timedCaller.join(15000);
                try { fourthOwner.retire(TIMEOUT); } finally { fourthResolution.close(); }
            }

            var fifthOwner = ManagedRuntimeAcquisitionOwner.create();
            var fifthResolution = PreparedResolutionOwner.create();
            var externalEntered = new CountDownLatch(1);
            var externalRelease = new CountDownLatch(1);
            Thread externalCaller = null;
            try {
                var fifthResources = fifthResolution.resolve(RuntimeProfile.HOTSPOT_JIT, policy,
                        loader.loadClass("fixture.DirectWorker"), Set.of());
                var fifthBinding = new OperationBinding("fifth", OperationAdapter.CALLABLE,
                        fifthResources.getImplementationSha256(), fifthResources.getResourceGeneration(),
                        Map.of("entryPoint", "call()I"));
                var fifthDocument = new OperationPolicyDocument(fifthBinding,
                        CanonicalPolicyArtifact.Companion.worker(RuntimeProfile.HOTSPOT_JIT, policy), Set.of(), Map.of());
                var fifthReviewed = ReviewedOperationArtifact.load(fifthDocument.toJson(),
                        new TrustedPolicyReview("test-only", fifthDocument.sha256(), Set.of()), fifthBinding);
                var fifthRuntime = fifthOwner.start(TIMEOUT, TIMEOUT);
                var externallyRetired = new DomainLifecycle(fifthRuntime, fifthOwner, fifthReviewed,
                        new DomainLimits(1, 1), fifthResources, Duration.ofMillis(20), InvocationContextBridge.none());
                activeReached.set(externalEntered);
                activeRelease.set(externalRelease);
                block.set(true);
                var externalFailure = new AtomicReference<Throwable>();
                var externalReturned = new CountDownLatch(1);
                externalCaller = Thread.ofPlatform().unstarted(() -> {
                    try { externallyRetired.start(); }
                    catch (Throwable thrown) { externalFailure.set(thrown); }
                    finally { externalReturned.countDown(); }
                });
                externalCaller.start();
                assertTrue(externalEntered.await(10, TimeUnit.SECONDS));
                assertInstanceOf(ManagedRuntimeAcquisitionStatus.Pending.class, fifthOwner.retire(Duration.ofMillis(20)));
                externalRelease.countDown();
                assertTrue(externalReturned.await(10, TimeUnit.SECONDS));
                assertInstanceOf(java.util.concurrent.RejectedExecutionException.class, externalFailure.get(),
                        "independent retirement must not produce normal READY");
                assertFalse(externallyRetired.isRunning());
                assertThrows(DomainLifecycle.InactiveDomainException.class, externallyRetired::dispatcher);
            } finally {
                externalRelease.countDown();
                if (externalCaller != null) externalCaller.join(15000);
                try { fifthOwner.retire(TIMEOUT); } finally { fifthResolution.close(); }
            }
        } finally {
            release.countDown(); callerRelease.countDown();
            if (caller != null) caller.join(15000);
            try { owner.retire(TIMEOUT); } finally {
                try { resolution.close(); } finally { Platform.resetToDefault(); linuxType.getMethod("resetToDefault").invoke(linux); }
            }
        }
    }
}
