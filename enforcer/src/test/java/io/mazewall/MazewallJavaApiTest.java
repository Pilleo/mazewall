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
    void testJavaInstallationReceiptRejectsContradictionAndPreservesFailure() {
        IllegalStateException sentinel = new IllegalStateException("sentinel failure");
        assertThrows(IllegalArgumentException.class, () -> new InstallationReceipt(
                false,
                io.mazewall.enforcer.api.PolicyPresets.INSTANCE.NO_EXEC,
                12345L,
                true,
                Platform.FallbackBehavior.FAIL,
                false,
                null,
                sentinel,
                CetOutcome.NOT_REQUESTED
        ));

        InstallationProgress progress = new InstallationProgress(false, true, false, false);
        InstallationReceipt partial = new InstallationReceipt(
                false,
                io.mazewall.enforcer.api.PolicyPresets.INSTANCE.NO_EXEC,
                12345L,
                false,
                Platform.FallbackBehavior.FAIL,
                true,
                progress,
                sentinel,
                CetOutcome.NOT_REQUESTED
        );

        assertFalse(partial.getInstalled());
        assertTrue(partial.getLandlockApplied());
        assertEquals(progress, partial.getProgress());
        assertSame(sentinel, partial.getFailure());
        IllegalStateException thrown = assertThrows(IllegalStateException.class, partial::requireInstalled);
        assertSame(sentinel, thrown);
    }

    @Test
    void testJavaPolicyDefinitionSnapshotsMutableCollectionsAndExposesImmutableViews() {
        Policy<PolicyScope.ThreadLocalOnly, PolicyState.Uncompiled> seeded = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
                .allowFsRead("/tmp")
                .allowFsWrite("/var/tmp")
                .build();
        SandboxedPath readPath = seeded.getAllowedFsReadPaths().iterator().next();
        SandboxedPath writePath = seeded.getAllowedFsWritePaths().iterator().next();

        var actions = new java.util.HashMap<Syscall, SeccompAction>();
        actions.put(Syscall.GETPID, new SeccompAction.ACT_ERRNO(1));
        var reads = new java.util.HashSet<SandboxedPath>();
        reads.add(readPath);
        var writes = new java.util.HashSet<SandboxedPath>();
        writes.add(writePath);
        var phrases = new java.util.ArrayList<String>();
        phrases.add("java_phrase");
        var regexes = new java.util.ArrayList<kotlin.text.Regex>();
        regexes.add(new kotlin.text.Regex("java_.*"));

        PolicyDefinition<PolicyScope.ThreadLocalOnly> raw = new PolicyDefinition<>(
                SeccompAction.ACT_ALLOW.INSTANCE,
                actions,
                false,
                false,
                false,
                false,
                reads,
                writes,
                false,
                phrases,
                regexes
        );

        actions.put(Syscall.GETPID, SeccompAction.ACT_ALLOW.INSTANCE);
        reads.clear();
        writes.clear();
        phrases.clear();
        regexes.clear();

        assertFalse(raw.isSyscallAllowed(Syscall.GETPID));
        assertEquals(1, raw.getAllowedFsReadPaths().size());
        assertEquals(1, raw.getAllowedFsWritePaths().size());
        assertEquals(java.util.List.of("java_phrase"), raw.getCustomViolationPhrases());
        assertEquals(1, raw.getCustomViolationRegexes().size());

        var copyActions = new java.util.HashMap<Syscall, SeccompAction>();
        copyActions.put(Syscall.EXECVE, new SeccompAction.ACT_ERRNO(1));
        PolicyDefinition<PolicyScope.ThreadLocalOnly> copied = raw.copy(
                raw.getDefaultAction(),
                copyActions,
                raw.getAllowMmapExec(),
                raw.getAllowNonThreadClone(),
                raw.getAllowUnsafePrctl(),
                raw.getLockIntelCet(),
                raw.getAllowedFsReadPaths(),
                raw.getAllowedFsWritePaths(),
                true,
                raw.getCustomViolationPhrases(),
                raw.getCustomViolationRegexes()
        );
        copyActions.clear();

        assertFalse(copied.isSyscallAllowed(Syscall.EXECVE));
        assertThrows(UnsupportedOperationException.class, () -> copied.getSyscallActions().put(Syscall.READ, SeccompAction.ACT_ALLOW.INSTANCE));
        assertThrows(UnsupportedOperationException.class, () -> copied.getAllowedFsReadPaths().add(readPath));
        assertThrows(UnsupportedOperationException.class, () -> copied.getAllowedFsWritePaths().add(writePath));
        assertThrows(UnsupportedOperationException.class, () -> copied.getCustomViolationPhrases().add("mutated"));
        assertThrows(UnsupportedOperationException.class, () -> copied.getCustomViolationRegexes().add(new kotlin.text.Regex("mutated")));
    }

    @Test
    void testJavaProcessBuilderRejectsRawFilesystemIntent() {
        SandboxedPath path = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
                .allowFsRead("/tmp")
                .build()
                .getAllowedFsReadPaths()
                .iterator()
                .next();
        var reads = new java.util.HashSet<SandboxedPath>();
        reads.add(path);
        PolicyDefinition<PolicyScope.ProcessWideSafe> forged = new PolicyDefinition<>(
                SeccompAction.ACT_ALLOW.INSTANCE,
                new java.util.HashMap<Syscall, SeccompAction>(),
                false,
                false,
                false,
                false,
                reads,
                new java.util.HashSet<SandboxedPath>(),
                false,
                new java.util.ArrayList<String>(),
                new java.util.ArrayList<kotlin.text.Regex>()
        );
        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).base(forged)
        );
        assertTrue(thrown.getMessage().contains("worker filesystem intent"));

        PolicyDefinition<PolicyScope.ProcessWideSafe> process = ProcessPolicyBuilder
                .denyList(RuntimeProfile.HOTSPOT_JIT)
                .build()
                .getDefinition();
        assertThrows(
                IllegalArgumentException.class,
                () -> process.copy(
                        process.getDefaultAction(),
                        process.getSyscallActions(),
                        process.getAllowMmapExec(),
                        process.getAllowNonThreadClone(),
                        process.getAllowUnsafePrctl(),
                        process.getLockIntelCet(),
                        reads,
                        process.getAllowedFsWritePaths(),
                        false,
                        process.getCustomViolationPhrases(),
                        process.getCustomViolationRegexes()
                )
        );
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
