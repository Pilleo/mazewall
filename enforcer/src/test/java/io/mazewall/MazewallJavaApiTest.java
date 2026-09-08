package io.mazewall;

import io.mazewall.core.SandboxedPath;
import io.mazewall.core.SeccompAction;
import io.mazewall.core.Syscall;
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
        assertNotNull(Mazewall.PURE_COMPUTE);
        assertNotNull(Mazewall.PURE_COMPUTE_UNSAFE);
        assertNotNull(Mazewall.NO_NETWORK);
        assertNotNull(Mazewall.NO_EXEC);
        assertNotNull(Mazewall.NO_EXEC_NO_FS_WRITE);
        assertNotNull(Mazewall.DEFAULT_SAFE);

        // Also test functional preset accessors
        assertNotNull(Mazewall.pureCompute());
        assertNotNull(Mazewall.pureComputeUnsafe());
        assertNotNull(Mazewall.noNetwork());
        assertNotNull(Mazewall.noExec());
        assertNotNull(Mazewall.noExecNoFsWrite());
        assertNotNull(Mazewall.defaultSafe());
    }

    @Test
    void testJavaPolicyBuilderThreadLocal() {
        Policy<PolicyScope.ThreadLocalOnly, PolicyState.Uncompiled> policy = Mazewall.threadLocalBuilder()
                .base(Mazewall.pureCompute())
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
        Policy<PolicyScope.ProcessWideSafe, PolicyState.Uncompiled> processPolicy = Mazewall.builder()
                .base(Mazewall.pureComputeUnsafe())
                .defaultAction(SeccompAction.ACT_ALLOW.INSTANCE)
                .allow(Syscall.GETPID)
                .buildProcessWide();

        assertNotNull(processPolicy);
        assertTrue(processPolicy.isSyscallAllowed(Syscall.GETPID));
        assertTrue(processPolicy.getAllowedFsReadPaths().isEmpty());
    }

    @Test
    void testProcessWideDisallowsFsRules() {
        JavaPolicyBuilder builder = Mazewall.builder().allowFsRead("/tmp");
        assertThrows(IllegalStateException.class, builder::buildProcessWide);
    }

    @Test
    void testCombinePolicies() {
        Policy<PolicyScope.ThreadLocalOnly, PolicyState.Uncompiled> p1 = Mazewall.threadLocalBuilder()
                .allow(Syscall.READ)
                .build();
        Policy<PolicyScope.ThreadLocalOnly, PolicyState.Uncompiled> p2 = Mazewall.threadLocalBuilder()
                .allow(Syscall.WRITE)
                .build();

        Policy<PolicyScope.ThreadLocalOnly, PolicyState.Uncompiled> combined =
                Mazewall.combineThreadLocal(p1, p2);
        assertNotNull(combined);
    }

    @Test
    void testRunContainedWithCallableAndSupplier() {
        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS");
        try {
            // We use PURE_COMPUTE_UNSAFE as mock/dry-run baseline
            Policy<PolicyScope.ProcessWideSafe, PolicyState.Uncompiled> policy = Mazewall.pureComputeUnsafe();

            String callableResult = Mazewall.runContained(policy, (Callable<String>) () -> "successCallable");
            assertEquals("successCallable", callableResult);

            String supplierResult =
                    Mazewall.runContained(policy, (java.util.function.Supplier<String>) () -> "successSupplier");
            assertEquals("successSupplier", supplierResult);

            AtomicInteger runCount = new AtomicInteger(0);
            Mazewall.runContained(policy, (Runnable) runCount::incrementAndGet);
            assertEquals(1, runCount.get());
        } finally {
            System.clearProperty("io.mazewall.fallback");
        }
    }

    @Test
    void testContainedExecutors() throws Exception {
        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS");
        try {
            Policy<PolicyScope.ProcessWideSafe, PolicyState.Uncompiled> policy = Mazewall.pureComputeUnsafe();

            ExecutorService singleThreadExecutor = Mazewall.newContainedSingleThreadExecutor(policy);
            try {
                Future<Integer> future = singleThreadExecutor.submit(() -> 42);
                assertEquals(42, future.get());
            } finally {
                singleThreadExecutor.shutdown();
            }

            ExecutorService fixedPool = Mazewall.newContainedFixedThreadPool(2, policy);
            try {
                Future<String> future = fixedPool.submit(() -> "fixed");
                assertEquals("fixed", future.get());
            } finally {
                fixedPool.shutdown();
            }

            ExecutorService cachedPool = Mazewall.newContainedCachedThreadPool(policy);
            try {
                Future<String> future = cachedPool.submit(() -> "cached");
                assertEquals("cached", future.get());
            } finally {
                cachedPool.shutdown();
            }

            ExecutorService custom = Executors.newSingleThreadExecutor();
            try {
                ExecutorService wrapped = Mazewall.wrapContainedExecutor(custom, policy);
                Future<String> future = wrapped.submit(() -> "wrapped");
                assertEquals("wrapped", future.get());
            } finally {
                custom.shutdown();
            }
        } finally {
            System.clearProperty("io.mazewall.fallback");
        }
    }

    @Test
    void testInstallationReceiptAccessors() {
        InstallationReceipt receipt = new InstallationReceipt(
                true,
                io.mazewall.PolicyPresets.INSTANCE.NO_EXEC,
                null,
                12345L,
                true,
                Platform.configuredFallback(),
                false
        );

        assertTrue(receipt.getInstalled());
        assertTrue(receipt.getProcessWide());
        assertFalse(receipt.getLandlockApplied());
        assertEquals(12345L, receipt.getTimestampMillis());
    }
}
