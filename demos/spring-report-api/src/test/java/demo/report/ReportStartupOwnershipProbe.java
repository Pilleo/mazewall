package demo.report;

import io.mazewall.enforcer.api.*;
import io.mazewall.enforcer.api.execution.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Disposable JVM using actual resources and test-only native delegation, never an operator review. */
public final class ReportStartupOwnershipProbe {
    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static final class NoSuppressionError extends Error {
        private static final long serialVersionUID = 1L;
        NoSuppressionError() { super("actual-start-fatal-without-suppression", null, false, true); }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1) {
            try {
                ReportApplication.main(new String[] {"measure", args[0]});
                throw new AssertionError("missing path accepted");
            } catch (java.nio.file.NoSuchFileException expected) {
                check(ReportApplication.failedStartup().isEmpty(), "unacquired root retained");
            }
            try {
                ReportApplication.main(new String[] {"measure", args[0]});
                throw new AssertionError("second main accepted");
            } catch (IllegalStateException expected) { System.out.println("STARTUP_PRE_EFFECT_OK"); }
            return;
        }
        if (args.length == 2 && args[0].equals("io-read-failure")) {
            try {
                ReportApplication.main(new String[] {"serve", args[1], "0"});
                throw new AssertionError("directory review input was read");
            } catch (java.io.IOException failure) {
                check(ReportApplication.failedStartup().isEmpty(), "unacquired IO failure retained root");
                System.out.println("STARTUP_REAL_IO_FAILURE_OK input=directory no-owner=verified");
            }
            return;
        }
        check(args.length == 2 && (args[0].equals("post-acquisition") || args[0].equals("refresh-failure") || args[0].equals("cleanup-fatal") || args[0].equals("reentrant-unclaimed") || args[0].equals("suppression-disabled")), "invalid probe arguments");
        postAcquisition(Path.of(args[1]).toRealPath(), args[0].equals("refresh-failure"), args[0].equals("cleanup-fatal"), args[0].equals("reentrant-unclaimed"), args[0].equals("suppression-disabled"));
    }

    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void holdRealValidator(CountDownLatch release, AtomicReference<Thread> holderRef,
                                          AtomicReference<Thread> validatorRef) throws Exception {
        var retained = ReportApplication.class.getDeclaredField("retained");
        retained.setAccessible(true);
        Object handle = retained.get(null);
        var resources = (PreparedOperationResources) field(handle, "resources");
        Object capture = field(resources, "directOwner");
        Object captured = field(capture, "captured");
        Object resourceSet = field(captured, "resources");
        Object gate = field(capture, "gate");
        var validators = capture.getClass().getDeclaredField("validators");
        validators.setAccessible(true);
        var locked = new CountDownLatch(1);
        Thread holder = Thread.ofPlatform().start(() -> {
            synchronized (resourceSet) {
                locked.countDown();
                try { release.await(); } catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
            }
        });
        holderRef.set(holder);
        check(locked.await(5, TimeUnit.SECONDS), "resource monitor was not held");
        Thread validator = Thread.ofPlatform().start(resources::validate);
        validatorRef.set(validator);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean active = false;
        while (System.nanoTime() < deadline) {
            synchronized (gate) { active = validators.getInt(capture) > 0; }
            if (active) break;
            Thread.onSpinWait();
        }
        check(active && validator.isAlive(), "real validator did not acquire its permit");
    }

    private static void postAcquisition(Path root, boolean refreshFailure, boolean cleanupFatal, boolean reentrantUnclaimed, boolean suppressionDisabled) throws Exception {
        var linuxType = Class.forName("io.mazewall.platform.native.LinuxNative");
        var linux = linuxType.getField("INSTANCE").get(null);
        linuxType.getMethod("resetToDefault").invoke(linux);
        var real = linuxType.getMethod("snapshot").invoke(linux);
        var engineType = Class.forName("io.mazewall.platform.native.NativeEngine");
        var rawType = Class.forName("io.mazewall.platform.native.RawSyscallOperations");
        var processType = Class.forName("io.mazewall.platform.native.NativeProcess");
        var fsType = Class.forName("io.mazewall.platform.native.NativeFileSystem");
        var success = Class.forName("io.mazewall.platform.native.LinuxNative$SyscallResult$Success")
                .getConstructor(Object.class, List.class, int.class);
        var error = Class.forName("io.mazewall.platform.native.LinuxNative$SyscallResult$Error")
                .getConstructor(int.class, long.class);
        var realRaw = engineType.getMethod("getRaw").invoke(real);
        var realProcess = engineType.getMethod("getProcess").invoke(real);
        var realFs = engineType.getMethod("getFileSystem").invoke(real);
        var archType = Class.forName("io.mazewall.platform.syscall.Arch");
        var companion = archType.getField("Companion").get(null);
        var arch = companion.getClass().getMethod("current", String.class).invoke(companion, System.getProperty("os.arch"));
        long seccomp = ((Number) archType.getMethod("getSeccompSyscallNumber").invoke(arch)).longValue();
        var constants = Class.forName("io.mazewall.ffi.NativeConstants");
        long restrict = ((Number) constants.getField("LANDLOCK_RESTRICT_SELF_NR").get(null)).longValue();
        var closeEntered = new CountDownLatch(1);
        var closeRelease = new CountDownLatch(1);
        var closes = new java.util.concurrent.atomic.AtomicInteger();
        var closeActor = new AtomicReference<Thread>();
        var duplicateDuringCleanup = new AtomicReference<Throwable>();
        var getterDuringCleanup = new AtomicReference<Throwable>();
        var injectedFatal = new AssertionError("real-close-then-fatal");
        var suppressedPrimary = new NoSuppressionError();
        var armDisabledClose = new java.util.concurrent.atomic.AtomicBoolean();
        var originalStarter = Thread.currentThread();
        var armFailedStart = new java.util.concurrent.atomic.AtomicBoolean();
        var armReentry = new java.util.concurrent.atomic.AtomicBoolean();
        var reentryFailure = new AtomicReference<Throwable>();
        var holderThread = new AtomicReference<Thread>();
        var validatorThread = new AtomicReference<Thread>();
        var validationRelease = new CountDownLatch(1);
        var raw = Proxy.newProxyInstance(ReportStartupOwnershipProbe.class.getClassLoader(), new Class<?>[] {rawType},
                (proxy, method, args) -> {
                    if (method.getName().startsWith("syscall") && args != null && args.length > 0) {
                        long nr = ((Number) args[0]).longValue();
                        if (nr == seccomp || nr == restrict) return success.newInstance(0L, List.of(), 0);
                    }
                    return invoke(realRaw, method, args);
                });
        var process = Proxy.newProxyInstance(ReportStartupOwnershipProbe.class.getClassLoader(), new Class<?>[] {processType},
                (proxy, method, args) -> {
                    if (method.getName().equals("prctl")) {
                        if (reentrantUnclaimed && armFailedStart.compareAndSet(true, false)) {
                            holdRealValidator(validationRelease, holderThread, validatorThread);
                            return error.newInstance(13, -1L);
                        }
                        if (suppressionDisabled && armFailedStart.compareAndSet(true, false)) {
                            armDisabledClose.set(true);
                            throw suppressedPrimary;
                        }
                        boolean get = args[0].getClass().getName().endsWith("PrctlCommand$GetSeccomp");
                        return success.newInstance(get ? 2L : 0L, List.of(), 0);
                    }
                    return invoke(realProcess, method, args);
                });
        var fs = Proxy.newProxyInstance(ReportStartupOwnershipProbe.class.getClassLoader(), new Class<?>[] {fsType},
                (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                        closes.incrementAndGet();
                        if (suppressionDisabled && Thread.currentThread() == originalStarter && armDisabledClose.compareAndSet(true, false)) {
                            invoke(realFs, method, args);
                            throw injectedFatal;
                        }
                        if (reentrantUnclaimed && Thread.currentThread() == originalStarter && armReentry.compareAndSet(true, false)) {
                            try { ReportApplication.failedStartup().orElseThrow().retryRetirement(Duration.ofSeconds(1)); }
                            catch (Throwable failure) { reentryFailure.set(failure); }
                        }
                        if (Thread.currentThread().getName().contains("resource-retirement") && closeEntered.getCount() != 0) {
                            closeActor.set(Thread.currentThread());
                            try { ReportApplication.main(new String[] {"measure", root.resolve("never").toString()}); }
                            catch (Throwable failure) { duplicateDuringCleanup.set(failure); }
                            try { ReportApplication.failedStartup(); }
                            catch (Throwable failure) { getterDuringCleanup.set(failure); }
                            closeEntered.countDown();
                            if (cleanupFatal) {
                                invoke(realFs, method, args);
                                throw injectedFatal;
                            }
                            if (!closeRelease.await(20, TimeUnit.SECONDS)) throw new AssertionError("held cleanup timeout");
                        }
                    }
                    return invoke(realFs, method, args);
                });
        var engine = Proxy.newProxyInstance(ReportStartupOwnershipProbe.class.getClassLoader(), new Class<?>[] {engineType},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getRaw" -> raw;
                    case "getProcess" -> process;
                    case "getFileSystem" -> fs;
                    default -> invoke(real, method, args);
                });
        var providerType = Class.forName("io.mazewall.enforcer.api.PlatformProvider");
        var realProviderType = Class.forName("io.mazewall.enforcer.internal.ffi.RealPlatformProvider");
        var realProvider = realProviderType.getField("INSTANCE").get(null);
        var capability = realProviderType.getMethod("getLandlockCapability").invoke(realProvider);
        check(capability.getClass().getName().endsWith("KernelCapability$Available"), "real Landlock unavailable");
        var provider = Proxy.newProxyInstance(ReportStartupOwnershipProbe.class.getClassLoader(), new Class<?>[] {providerType},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getOsName" -> "Linux";
                    case "hasKernelSeccompSupport", "probeSeccompTsync", "probeSeccompUserNotif" -> true;
                    case "probeCetSupported" -> false;
                    case "checkSeccompSanity" -> error.newInstance(22, -1L);
                    case "getLandlockCapability" -> capability;
                    default -> invoke(realProvider, method, args);
                });
        linuxType.getMethod("setEngine", engineType).invoke(linux, engine);
        var platform = Class.forName("io.mazewall.enforcer.api.Platform");
        platform.getMethod("setProvider$io_mazewall_enforcer", providerType).invoke(null, provider);
        try {
            var profile = RuntimeProfile.HOTSPOT_JIT;
            var policy = WorkerPolicyBuilder.denyList(profile).allowFsRead(root.resolve("application.jar"), root.resolve("templates/approved.txt")).build();
            try (var owner = PreparedResolutionOwner.create()) {
                var resources = owner.resolve(profile, policy, ReportService.class,
                        Set.of(root.resolve("application.jar"), root.resolve("templates/approved.txt")));
                var binding = new OperationBinding("render-report", OperationAdapter.SPRING_METHOD,
                        resources.getImplementationSha256(), resources.getResourceGeneration(),
                        Map.of("beanName", "reportService", "className", ReportService.class.getName(),
                                "methodDescriptor", "generate(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"));
                var document = new OperationPolicyDocument(binding, CanonicalPolicyArtifact.Companion.worker(profile, policy), Set.of(), Map.of());
                Files.writeString(root.resolve("candidate.json"), document.toJson());
                var review = new Properties();
                review.setProperty("reviewer", "synthetic-test-only");
                review.setProperty("documentSha256", document.sha256());
                review.setProperty("jarSha256", resources.getImplementationSha256());
                review.setProperty("resourceGeneration", resources.getResourceGeneration());
                try (var out = Files.newOutputStream(root.resolve("trusted-review.properties"))) { review.store(out, "synthetic test authority only"); }
            }
            if (refreshFailure) System.setProperty("server.address", "0.0.0.0");
            if (reentrantUnclaimed || suppressionDisabled) armFailedStart.set(true);
            Throwable startFailure;
            var outerClose = new AssertionError("test-outer-close-fatal");
            try {
                if (cleanupFatal) {
                    try (AutoCloseable ignored = () -> { throw outerClose; }) {
                        ReportApplication.main(new String[] {"serve", root.toString(), "0"});
                    }
                } else ReportApplication.main(new String[] {"serve", root.toString(), "0"});
                throw new AssertionError("EOF accepted");
            } catch (Throwable failure) { startFailure = failure; }
            if (suppressionDisabled) {
                check(startFailure == suppressedPrimary, "suppression-disabled primary identity changed");
                check(suppressedPrimary.getSuppressed().length == 0, "suppression-disabled primary unexpectedly attached failure");
                check(!armDisabledClose.get(), "actual unclaimed resource close did not fail");
                var retained = ReportApplication.failedStartup().orElseThrow();
                check(retained.status().phase() == ReportApplication.StartupPhase.UNKNOWN, "missing attachment did not latch Unknown");
                int noReplay = closes.get();
                try { retained.retryRetirement(Duration.ofSeconds(10)); throw new AssertionError("undelivered close Error lost"); }
                catch (AssertionError failure) { check(failure == injectedFatal, "wrong close Error delivered"); }
                check(retained.retryRetirement(Duration.ofSeconds(10)).phase() == ReportApplication.StartupPhase.UNKNOWN,
                        "unknown attachment became complete");
                check(closes.get() == noReplay, "unknown close was replayed");
                System.out.println("STARTUP_SUPPRESSION_DISABLED_OK actual-close-error=delivered-once");
                return;
            }
            if (reentrantUnclaimed) {
                var retained = ReportApplication.failedStartup().orElseThrow();
                check(validatorThread.get() != null && validatorThread.get().isAlive(), "actual validation did not remain active");
                validationRelease.countDown();
                holderThread.get().join(5000);
                validatorThread.get().join(5000);
                check(!validatorThread.get().isAlive(), "actual validator did not exit");
                armReentry.set(true);
                retained.retryRetirement(Duration.ofSeconds(10));
                check(!armReentry.get(), "starter did not reach an actual resource close");
                check(reentryFailure.get() instanceof IllegalStateException, "same-starter nested retry was not refused");
                check(ReportApplication.failedStartup().orElseThrow() == retained, "ambiguous acquisition root was lost");
                System.out.println("STARTUP_REENTRANT_RETRY_OK actual-validator=held actual-close=starter");
                return;
            }
            check(closeEntered.await(10, TimeUnit.SECONDS), "actual cleanup was not held");
            if (!cleanupFatal) check(closeActor.get() != null && closeActor.get().isAlive(), "actual retirement actor is not alive");
            var handle = ReportApplication.failedStartup().orElseThrow();
            check(duplicateDuringCleanup.get() instanceof IllegalStateException, "reentrant duplicate main was accepted");
            check(getterDuringCleanup.get() instanceof IllegalStateException, "reentrant foreign getter was accepted");
            check(handle.status().phase() == (refreshFailure || cleanupFatal ? ReportApplication.StartupPhase.UNKNOWN : ReportApplication.StartupPhase.PENDING),
                    "actual retained owner had unexpected status");
            var foreign = new AtomicReference<Throwable>();
            Thread other = Thread.ofPlatform().start(() -> {
                try { handle.retryRetirement(Duration.ofSeconds(1)); }
                catch (Throwable failure) { foreign.set(failure); }
            });
            other.join(5000);
            check(foreign.get() instanceof IllegalStateException, "foreign retry accepted");
            var foreignGetter = new AtomicReference<Throwable>();
            Thread getter = Thread.ofPlatform().start(() -> {
                try { ReportApplication.failedStartup(); } catch (Throwable failure) { foreignGetter.set(failure); }
            });
            getter.join(5000);
            check(foreignGetter.get() instanceof IllegalStateException, "foreign getter accepted");
            try { ReportApplication.main(new String[] {"measure", root.toString()}); throw new AssertionError("duplicate accepted"); }
            catch (IllegalStateException expected) { /* one-shot admission */ }
            int beforeRelease = closes.get();
            closeRelease.countDown();
            var observation = handle.retryRetirement(Duration.ofSeconds(10));
            if (cleanupFatal) {
                check(startFailure == injectedFatal, "cleanup Error identity replaced");
                check(java.util.Arrays.stream(injectedFatal.getSuppressed()).anyMatch(t -> t instanceof IllegalArgumentException),
                        "original EOF failure not retained on cleanup Error");
                check(java.util.Arrays.stream(injectedFatal.getSuppressed()).anyMatch(t -> t == outerClose),
                        "Java try-with-resources did not preserve distinct outer Error identity");
                check(observation.phase() == ReportApplication.StartupPhase.UNKNOWN, "unknown close became complete");
                check(closes.get() == beforeRelease, "unknown close was replayed");
                check(ReportApplication.failedStartup().orElseThrow() == handle, "fatal root not retained");
                System.out.println("STARTUP_FATAL_UNKNOWN_OK synthetic-review-only no-replay=verified");
            } else if (refreshFailure) {
                check(observation.phase() == ReportApplication.StartupPhase.UNKNOWN, "unproved context closure became complete");
                check(ReportApplication.failedStartup().orElseThrow() == handle, "unknown original root not retained");
                var attempt = handle.getClass().getDeclaredField("contextCloseAttempted");
                attempt.setAccessible(true);
                check(attempt.getBoolean(handle), "opaque framework close was not reserved");
                System.out.println("STARTUP_REFRESH_UNKNOWN_OK synthetic-review-only original-root=retained");
            } else {
                check(observation.phase() == ReportApplication.StartupPhase.COMPLETE, "actual retirement failed: " + observation);
                closeActor.get().join(5000);
                check(!closeActor.get().isAlive(), "actual retirement actor still alive after Complete");
                check(ReportApplication.failedStartup().isEmpty(), "completed root not cleared");
                check(handle.status().phase() == ReportApplication.StartupPhase.COMPLETE, "terminal snapshot lost");
                System.out.println("STARTUP_RETAINED_OK synthetic-review-only actual-close=held original-starter=verified");
            }
            check(closes.get() >= beforeRelease, "invalid close count");
            check(startFailure != null, "original startup failure lost");
        } finally {
            closeRelease.countDown();
            validationRelease.countDown();
            if (holderThread.get() != null) holderThread.get().join(5000);
            if (validatorThread.get() != null) validatorThread.get().join(5000);
            platform.getMethod("resetToDefault").invoke(null);
            linuxType.getMethod("resetToDefault").invoke(linux);
            System.clearProperty("server.address");
        }
    }
}
