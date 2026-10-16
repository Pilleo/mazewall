package io.mazewall;

import io.mazewall.enforcer.api.*;
import io.mazewall.enforcer.api.execution.*;
import io.mazewall.platform.lifecycle.SandboxedPath;
import io.mazewall.platform.syscall.SeccompAction;
import io.mazewall.platform.syscall.Syscall;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class MazewallJavaApiTest {

    @Test
    void testPresetsAccess() {
        assertNotNull(Policy.PURE_COMPUTE);
        assertNotNull(Policy.PURE_COMPUTE_UNSAFE);
        assertNotNull(Policy.NO_NETWORK);
        assertNotNull(Policy.NO_EXEC_HOTSPOT);
    }

    @Test
    void testJavaPolicyBuilderThreadLocal() {
        Policy<PolicyScope.ThreadLocalOnly, PolicyState.Uncompiled> policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
                .base(Policy.PURE_COMPUTE)
                .defaultAction(SeccompAction.ACT_ALLOW.INSTANCE)
                .allow(Syscall.READ, Syscall.WRITE)
                .unblock(Syscall.OPENAT)
                .block(Syscall.CONNECT)
                .addAction(SeccompAction.ACT_KILL_PROCESS.INSTANCE, Syscall.IOCTL)
                .allowFsRead("/tmp")
                .allowFsRead(Path.of("/var/tmp"))
                .allowFsRead(new File("/etc"))
                .allowFsRead(SandboxedPath.Companion.of("/usr", false))
                .allowFsWrite("/tmp")
                .allowFsWrite(Path.of("/var/tmp"))
                .allowFsWrite(new File("/tmp/test.txt"))
                .allowFsWrite(SandboxedPath.Companion.of("/tmp/scratch", true))
                .allowMmapExec(false)
                .customViolationPhrase("denied_access")
                .customViolationRegex("denied_.*")
                .customViolationRegex(Pattern.compile("secret_.*"))
                .build();

        assertNotNull(policy);
        assertTrue(policy.isSyscallAllowed(Syscall.READ));
        assertTrue(policy.isSyscallAllowed(Syscall.WRITE));
        assertFalse(policy.getAllowedFsReadPaths().isEmpty());
    }

    @Test
    void testJavaPolicyBuilderProcessWide() {
        Policy<PolicyScope.ProcessWideSafe, PolicyState.Uncompiled> processPolicy = ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
                .base(Policy.PURE_COMPUTE_UNSAFE)
                .defaultAction(SeccompAction.ACT_ALLOW.INSTANCE)
                .allow(Syscall.GETPID)
                .build();

        assertNotNull(processPolicy);
        assertTrue(processPolicy.isSyscallAllowed(Syscall.GETPID));
        assertTrue(processPolicy.getAllowedFsReadPaths().isEmpty());
    }

    @Test
    void testWorkerFilesystemMutationRetainsIgnoredResult() {
        WorkerPolicyBuilder builder = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT);
        builder.allowFsRead("/tmp");
        assertEquals(1, builder.build().getAllowedFsReadPaths().size());
    }

    @Test
    void testCombinePolicies() {
        Policy<PolicyScope.ThreadLocalOnly, PolicyState.Uncompiled> p1 = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
                .allow(Syscall.READ)
                .build();
        Policy<PolicyScope.ThreadLocalOnly, PolicyState.Uncompiled> p2 = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
                .allow(Syscall.WRITE)
                .build();

        Policy<PolicyScope.ThreadLocalOnly, PolicyState.Uncompiled> combined =
                Policy.restrictFurtherWith(p1, p2);
        assertNotNull(combined);
    }

    @Test
    void testCanonicalCallableEntryIsUnambiguous() {
        try (ManagedRuntime runtime = OwnedRuntimeJavaTestSupport.open()) {
            InProcessDomain domain = runtime.prepare(WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build(), new DomainLimits(1, 1));
            String callableResult = Mazewall.callContained(runtime, domain, () -> "successCallable");
            assertEquals("successCallable", callableResult);
        } finally {
            OwnedRuntimeJavaTestSupport.reset();
        }
    }

    @Test
    void testExplicitCustomPreparationKeepsUncastCallableEntry() {
        try (ManagedRuntime runtime = OwnedRuntimeJavaTestSupport.open()) {
            AtomicInteger captures = new AtomicInteger();
            Thread caller = Thread.currentThread();
            StacktraceScopingPolicy scoping = () -> {
                assertSame(caller, Thread.currentThread());
                captures.incrementAndGet();
                return java.util.Map.of();
            };
            InProcessDomain domain = runtime.prepare(
                    WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build(),
                    new DomainLimits(1, 1), scoping);
            assertEquals("custom-owned", Mazewall.callContained(runtime, domain, () -> "custom-owned"));
            assertEquals(1, captures.get());
        } finally {
            OwnedRuntimeJavaTestSupport.reset();
        }
    }

    @Test
    void testContainedExecutors() throws Exception {
        try (ManagedRuntime runtime = OwnedRuntimeJavaTestSupport.open()) {
            InProcessDomain domain = runtime.prepare(WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build(), new DomainLimits(2, 2));
            InvocationHandle<String> invocation = runtime.submit(domain, () -> "owned");
            assertEquals("owned", invocation.result().toCompletableFuture().get());
            assertInstanceOf(ExecutionEnd.Finished.class, invocation.executionEnd().toCompletableFuture().get());
        } finally {
            OwnedRuntimeJavaTestSupport.reset();
        }
    }

    @Test
    void applicationDeclaredMetadataUsesExistingUncastCallableEntry() {
        var context = new ThreadLocal<java.util.Map<String, String>>();
        try (ManagedRuntime runtime = OwnedRuntimeJavaTestSupport.open()) {
            // Synchronous result delivery may precede executionEnd; keep one worker with two bounded admissions.
            InProcessDomain domain = runtime.prepare(WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build(), new DomainLimits(1, 2));
            var mutable = new java.util.HashMap<String, String>();
            mutable.put("request", "captured");
            context.set(mutable);
            InvocationContextBridge bridge = () -> new InvocationContextSnapshot(context.get(), metadata -> {
                var prior = context.get();
                context.set(metadata);
                return () -> { if (prior == null) context.remove(); else context.set(prior); };
            });
            Callable<String> captured = runtime.captureInvocation(domain, bridge, () -> context.get().get("request"));
            mutable.put("request", "changed");
            assertEquals("captured", Mazewall.callContained(runtime, domain, captured));
            assertEquals("changed", context.get().get("request"));
            assertNull(Mazewall.callContained(runtime, domain, () -> context.get()));
        } finally {
            context.remove();
            OwnedRuntimeJavaTestSupport.reset();
        }
    }

    @Test
    void testInstallationReceiptAccessors() {
        InstallationReceipt receipt = new InstallationReceipt(
                true,
                io.mazewall.enforcer.api.PolicyPresets.INSTANCE.NO_EXEC,
                12345L,
                true,
                Platform.configuredFallback(),
                false,
                null,
                null,
                CetOutcome.NOT_REQUESTED
        );

        assertTrue(receipt.getInstalled());
        assertTrue(receipt.getProcessWide());
        assertFalse(receipt.getLandlockApplied());
        assertEquals(12345L, receipt.getTimestampMillis());
    }

    @Test
    void testJavaInstallationApiReturnsReceiptOnly() {
        assertTrue(
                java.util.Arrays.stream(ContainedExecutors.class.getDeclaredMethods())
                        .filter(method -> method.getName().startsWith("install"))
                        .allMatch(method -> method.getReturnType().equals(InstallationReceipt.class))
        );
    }
}
