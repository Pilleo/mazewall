package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.BaseIntegrationTest
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.enforcer.api.Platform
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.PolicyScope
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.channels.Selector
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

private const val LANDLOCK_ACCESS_DENIED_ERRNO = 13

class ProcessContainmentTest : BaseIntegrationTest() {
    companion object {
        private fun hasPrimaryLandlockAccessDenial(failure: Throwable): Boolean {
            val strerror13 = io.mazewall.ffi.memory
                .getSystemStrerror(LANDLOCK_ACCESS_DENIED_ERRNO)
            val errno = Regex("""\b(?:error|errno)[=:]?\s*13\b|\bEACCES\b""", RegexOption.IGNORE_CASE)
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            var cause: Throwable? = failure
            var accessDenied = false
            // Only the installation's primary causal chain grants permission authority.
            while (cause != null && seen.add(cause)) {
                val message = cause.message
                accessDenied = accessDenied ||
                    when (cause) {
                    is ContainmentViolationException -> cause.errno == LANDLOCK_ACCESS_DENIED_ERRNO
                    is java.nio.file.AccessDeniedException -> true
                    is IOException, is IllegalStateException ->
                        message != null &&
                        (errno.containsMatchIn(message) || (strerror13 != null && message == strerror13))
                    else -> false
                }
                cause = cause.cause
            }
            return accessDenied
        }
    }

    fun testProcessWide() {
        val safeGlobalPolicy =
            ProcessPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .block(Syscall.EXECVE, Syscall.EXECVEAT, Syscall.IO_URING_SETUP, Syscall.IO_URING_ENTER)
                .allowMmapExec()
                .allowNonThreadClone()
                .build()

        ContainedExecutors.installOnProcess(safeGlobalPolicy)
        requireContainmentDenial {
            ProcessBuilder("echo", "should-fail").start()
        }
    }

    fun testInheritance() {
        val policy =
            ProcessPolicyBuilder
                .denyList(RuntimeProfile.NATIVE_IMAGE)
                .base(Policy.NO_EXEC)
                .allowMmapExec()
                .build()
        ContainedExecutors.installOnProcess(policy)

        verifyInheritedDenial {
            ProcessBuilder("echo", "should-fail").start()
        }
    }

    private fun requireContainmentDenial(operation: () -> Unit) {
        val result = runCatching { operation() }
        val failure = result.exceptionOrNull()
            ?: throw IllegalStateException("Operation should have been contained")
        val exceptional = exceptionalOutcome(failure)
        if (exceptional != null) {
            if (exceptional is InterruptedException) Thread.currentThread().interrupt()
            throw exceptional
        }
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var cause: Throwable? = failure
        while (cause != null && seen.add(cause)) {
            if (cause is io.mazewall.enforcer.api.ContainmentViolationException) {
                if (cause.errno == 1 || cause.errno == 13) return
            } else if (cause is java.nio.file.AccessDeniedException) {
                return
            } else if (cause is IOException) {
                val message = cause.message
                // ProcessImpl's native exec receipt belongs to this causal IOException,
                // not to the outer ProcessBuilder message containing the program/path.
                val execErrno = message?.let {
                    // The observed native receipt includes one terminal ASCII space.
                    Regex("""Exec failed, error: (1|13) \(([^\r\n]*)\) ?""").matchEntire(it)
                }
                if (execErrno != null &&
                    execErrno.groupValues[2] == io.mazewall.ffi.memory
                        .getSystemStrerror(execErrno.groupValues[1].toInt())
                ) {
                    return
                }
                // JVM errno diagnostics, not a permission phrase embedded in a filename.
                val errno = message?.let {
                    Regex("""(?:^|[ (,])(?:error|errno)[=:]\s*(1|13)(?:,\s*([^\r\n]*))?$""")
                        .find(it)
                }
                if (errno != null &&
                    (
                        errno.groups[2] == null ||
                        errno.groupValues[2] == io.mazewall.ffi.memory
                            .getSystemStrerror(errno.groupValues[1].toInt())
                    )
                ) {
                    return
                }
                if (message != null &&
                    listOf(1, 13).any {
                        io.mazewall.ffi.memory
                            .getSystemStrerror(it) == message
                    }
                ) {
                    return
                }
            }
            cause = cause.cause
        }
        throw failure
    }

    private fun exceptionalOutcome(failure: Throwable): Throwable? {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        val pending = java.util.ArrayDeque<Throwable>()
        pending.add(failure)
        var fatal: Error? = null
        var interruption: InterruptedException? = null
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!seen.add(current)) continue
            if (current is Error && fatal == null) fatal = current
            if (current is InterruptedException && interruption == null) interruption = current
            current.cause?.let(pending::addLast)
            current.suppressed.forEach(pending::addLast)
        }
        if (interruption != null) Thread.currentThread().interrupt()
        return (fatal ?: interruption)?.let { CleanupFailures.merge(it, failure) }
    }

    private fun verifyInheritedDenial(operation: () -> Unit) {
        val outcome = AtomicReference<Result<Unit>>()
        val thread = Thread {
            val result = runCatching { requireContainmentDenial(operation) }
            outcome.set(result)
        }
        thread.start()
        var interruption: InterruptedException? = null
        var restoreInterrupt = Thread.interrupted()
        try {
            // Native callers are bounded by the ORIGINAL IsolatedProcessTester process deadline.
            // Direct host callers have no disposable-process bound: their operation must retire
            // cooperatively (as the held-child control does). A join timeout alone cannot safely
            // retire an arbitrary live JVM thread, so do not escape it or invent another budget.
            while (thread.isAlive) {
                try {
                    thread.join()
                } catch (caught: InterruptedException) {
                    restoreInterrupt = true
                    if (interruption == null) interruption = caught
                }
            }
            val result = requireNotNull(outcome.get())
            val failure = result.exceptionOrNull()
            val exceptional = failure?.let(::exceptionalOutcome)
            if (exceptional is Error) throw exceptional
            if (exceptional is InterruptedException) {
                restoreInterrupt = true
                throw exceptional
            }
            interruption?.let { throw it }
            result.getOrThrow()
        } finally {
            if (restoreInterrupt) Thread.currentThread().interrupt()
        }
    }

    private fun assertRejectedDenial(original: Throwable) {
        val observed = kotlin.test.assertFails { requireContainmentDenial { throw original } }
        assertSame(original, observed)
    }

    @Test
    fun `quality F09 denial rejects EINVAL`() {
        assertRejectedDenial(IOException("operation failed error=22"))
    }

    @Test
    fun `quality F09 denial rejects unrelated failure`() {
        assertRejectedDenial(IllegalArgumentException("unrelated operation failure"))
    }

    @Test
    fun `quality F09 denial rejects null diagnostic`() {
        assertRejectedDenial(IllegalArgumentException(null as String?))
    }

    @Test
    fun `quality F09 denial rejects suppressed permission`() {
        assertRejectedDenial(
            IllegalArgumentException("unrelated operation failure").apply {
            addSuppressed(IOException("operation failed error=1"))
        },
        )
    }

    @Test
    fun `quality F09 denial accepts concrete primary causal permission`() {
        val permission = IOException("operation failed error=1")
        requireContainmentDenial { throw permission }
        requireContainmentDenial { throw IllegalStateException("operation transport", permission) }
    }

    @Test
    fun `quality F09 denial accepts modeled ProcessBuilder primary native permission`() {
        // Classifier model of the observed native failure, not another kernel-denial fixture.
        val nativeReceipt = IOException("Exec failed, error: 1 (Operation not permitted)")
        val wrapper = IOException("Cannot run program \"echo\": ${nativeReceipt.message}", nativeReceipt)
        requireContainmentDenial { throw wrapper }
        for (message in listOf(
            "Exec failed, error: 1 (Operation not permitted) ",
            "Exec failed, error: 13 (Permission denied) ",
        )) {
            val observedFormat = IOException(message)
            requireContainmentDenial {
                throw IOException("Cannot run program \"echo\": $message", observedFormat)
            }
        }
    }

    @Test
    fun `quality F09 denial rejects modeled ProcessBuilder filename permission spoof`() {
        val filename = "Exec failed, error: 1 (Operation not permitted)"
        assertRejectedDenial(IOException("Cannot run program \"$filename\": error=2, No such file or directory"))
        assertRejectedDenial(IOException("Cannot run program \"echo\": $filename"))
        assertRejectedDenial(
            IOException("Cannot run program \"echo\": error=2, No such file or directory").apply {
                addSuppressed(IOException(filename))
            },
        )
    }

    @Test
    fun `quality F09 denial rejects modeled ProcessBuilder primary native EINVAL`() {
        val nativeReceipt = IOException("Exec failed, error: 22 (Invalid argument)")
        assertRejectedDenial(IOException("Cannot run program \"echo\": ${nativeReceipt.message}", nativeReceipt))
        assertRejectedDenial(IllegalArgumentException("Exec failed, error: 1 (Operation not permitted)"))
        for (message in listOf(
            "Exec failed, error: 1 (Operation not permitted) business text",
            "Exec failed, error: 1 (Operation not permitted) \n",
            "Exec failed, error: 1 (Permission denied) ",
            "Exec failed, error: 13 (Operation not permitted) ",
            "Exec failed, error: 22 (Invalid argument) ",
        )) {
            assertRejectedDenial(IOException(message))
        }
    }

    @Test
    fun `quality F09 denial prioritizes fatal across cause suppressed cycles`() {
        val fatal = LinkageError("fatal operation failure")
        val permission = IOException("operation failed error=1")
        val transport = IllegalStateException("operation transport", permission)
        permission.addSuppressed(transport)
        permission.addSuppressed(fatal)
        permission.addSuppressed(InterruptedException("concurrent operation interrupt"))
        fatal.addSuppressed(permission)
        val observed = kotlin.test.assertFails {
            requireContainmentDenial { throw transport }
        }
        assertSame(fatal, observed)
    }

    @Test
    fun `quality F09 denial preserves interruption across cause suppressed cycles`() {
        val interruption = InterruptedException("operation interrupted")
        val permission = IOException("operation failed error=1")
        val transport = IllegalStateException("operation transport", permission)
        permission.addSuppressed(interruption)
        interruption.addSuppressed(transport)
        try {
            val observed = kotlin.test.assertFails {
                requireContainmentDenial { throw transport }
            }
            assertSame(interruption, observed)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `quality F09 inherited held child fatal outranks owner interrupt after retirement`() {
        // Real owned threads and a held operation, not a completed Future receipt.
        // The legacy production join has no local bound; these bounds only retire this fixture.
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val ownerEnded = java.util.concurrent.CountDownLatch(1)
        val child = AtomicReference<Thread>()
        val ownerOutcome = AtomicReference<Result<Unit>>()
        val escapedLiveChild = java.util.concurrent.atomic
            .AtomicBoolean()
        val restoredAtEnd = java.util.concurrent.atomic
            .AtomicBoolean()
        val fatal = LinkageError("held child fatal outcome")
        val owner = Thread {
            ownerOutcome.set(
                runCatching {
                verifyInheritedDenial {
                    child.set(Thread.currentThread())
                    entered.countDown()
                    release.await()
                    throw fatal
                }
            },
            )
            escapedLiveChild.set(release.count != 0L && child.get()?.isAlive == true)
            restoredAtEnd.set(Thread.currentThread().isInterrupted)
            ownerEnded.countDown()
        }
        owner.start()
        try {
            kotlin.test.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS), "Child did not enter")
            owner.interrupt()
            // A correct boundary stays with its owned held child despite owner interruption.
            ownerEnded.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        } finally {
            release.countDown()
            owner.join(5000)
            child.get()?.join(5000)
        }
        assertFalse(owner.isAlive, "Fixture owner must physically end")
        assertFalse(requireNotNull(child.get()).isAlive, "Owned child must physically end")
        assertFalse(escapedLiveChild.get(), "Boundary returned while its owned child was still held")
        assertSame(fatal, requireNotNull(ownerOutcome.get()).exceptionOrNull())
        kotlin.test.assertTrue(restoredAtEnd.get(), "Owner interrupt must be restored after retirement and outcome merge")
    }

    @Test
    fun `inherited denial transports unexpected failure after physical retirement`() {
        val original = IllegalArgumentException("unexpected worker failure")
        val worker = AtomicReference<Thread>()
        val observed = assertFailsWith<IllegalArgumentException> {
            verifyInheritedDenial {
                worker.set(Thread.currentThread())
                throw original
            }
        }
        assertSame(original, observed)
        assertFalse(requireNotNull(worker.get()).isAlive)
    }

    @Test
    fun `inherited denial transports fatal failure after physical retirement`() {
        val original = LinkageError("fatal worker failure")
        val worker = AtomicReference<Thread>()
        val observed = assertFailsWith<LinkageError> {
            verifyInheritedDenial {
                worker.set(Thread.currentThread())
                throw original
            }
        }
        assertSame(original, observed)
        assertFalse(requireNotNull(worker.get()).isAlive)
    }

    @Test
    fun `inherited denial rejects successful operation after physical retirement`() {
        val worker = AtomicReference<Thread>()
        assertFailsWith<IllegalStateException> {
            verifyInheritedDenial { worker.set(Thread.currentThread()) }
        }
        assertFalse(requireNotNull(worker.get()).isAlive)
    }

    @Test
    fun `inherited denial accepts genuine filesystem denial after physical retirement`() {
        val worker = AtomicReference<Thread>()
        verifyInheritedDenial {
            worker.set(Thread.currentThread())
            throw java.nio.file.AccessDeniedException("actual denied operation control")
        }
        assertFalse(requireNotNull(worker.get()).isAlive)
    }

    fun testNioStability() {
        // Warm up NIO and Networking before installing the filter.
        Selector.open().close()
        try {
            java.net.Socket().connect(java.net.InetSocketAddress("127.0.0.1", 1), 1)
        } catch (e: IOException) {
            System.out.println("Warmup connection failed as expected: ${e.message}")
        }

        val noNetworkAllowJit = ProcessPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .base(Policy.NO_NETWORK)
            .allowMmapExec()
            .build()
        ContainedExecutors.installOnProcess(noNetworkAllowJit)

        val selector = Selector.open()
        selector.close()

        requireContainmentDenial {
            java.net.Socket().connect(java.net.InetSocketAddress("127.0.0.1", 80))
        }
    }

    fun testThreadDepth() {
        val safeSyscalls =
            listOf(
                Syscall.EXECVE,
                Syscall.EXECVEAT,
                Syscall.FORK,
                Syscall.VFORK,
                Syscall.CONNECT,
                Syscall.SOCKET,
                Syscall.BIND,
                Syscall.LISTEN,
                Syscall.ACCEPT,
                Syscall.ACCEPT4,
                Syscall.SENDTO,
                Syscall.SENDMSG,
                Syscall.MEMFD_CREATE,
                Syscall.IO_URING_SETUP,
                Syscall.BPF,
                Syscall.PTRACE,
                Syscall.PROCESS_VM_WRITEV,
                Syscall.PROCESS_VM_READV,
                Syscall.USERFAULTFD,
                Syscall.UNSHARE,
                Syscall.SETNS,
                Syscall.MOUNT,
                Syscall.UMOUNT2,
                Syscall.PIVOT_ROOT,
                Syscall.CHROOT,
                Syscall.INIT_MODULE,
                Syscall.FINIT_MODULE,
                Syscall.GETPID,
                Syscall.GETPPID,
                Syscall.GETUID,
                Syscall.GETEUID,
                Syscall.GETGID,
                Syscall.GETEGID,
                Syscall.GETTID,
                Syscall.GETCWD,
                Syscall.UMASK,
            )

        for (i in 0 until 32) {
            ContainedExecutors.installOnCurrentThread(
                ProcessPolicyBuilder
                    .denyList(RuntimeProfile.NATIVE_IMAGE)
                    .block(safeSyscalls[i])
                    .allowMmapExec()
                    .allowNonThreadClone()
                    .build(),
            )
        }

        val installation = runCatching {
            ContainedExecutors.installOnCurrentThread(
                ProcessPolicyBuilder
                    .denyList(RuntimeProfile.NATIVE_IMAGE)
                    .block(safeSyscalls[32])
                    .allowMmapExec()
                    .allowNonThreadClone()
                    .build(),
            )
        }
        val failure = installation.exceptionOrNull() ?: throw IllegalStateException("Should have failed")
        val depthFailure = failure as? IllegalStateException ?: throw failure
        check(requireNotNull(depthFailure.message).contains("32 seccomp filters"))
    }

    fun testHotspotJitSurvivesNoExecHotspot() {
        ContainedExecutors.installOnProcess(Policy.NO_EXEC_HOTSPOT)
        var acc = 0L
        repeat(80_000) { i ->
            acc += (i * 31L) xor i.toLong()
        }
        if (acc == Long.MIN_VALUE) {
            throw IllegalStateException("unreachable")
        }
        requireContainmentDenial {
            ProcessBuilder("echo", "should-fail").start()
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `process-wide NO_EXEC_HOTSPOT survives JIT work and still blocks exec`() {
        IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testHotspotJitSurvivesNoExecHotspot")
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `installOnProcess applies containment globally`() {
        IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testProcessWide")
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `subsequent threads inherit process-wide filter`() {
        IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testInheritance")
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `process-wide NO_NETWORK does not break NIO selector`() {
        IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testNioStability")
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `thread-local installation respects filter depth`() {
        IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testThreadDepth")
    }

    fun testLandlockSupportLimits() {
        val policyWithFs = WorkerPolicyBuilder
            .denyList(RuntimeProfile.NATIVE_IMAGE)
            .allowMmapExec()
            .allowFsRead("/etc")
            .build()
        val features = Platform.featureMatrix

        println("LANDLOCK_SUPPORT_LIMITS nativeCaller=true landlockTsyncSupported=${features.landlockTsyncSupported}")

        if (features.landlockTsyncSupported) {
            // On modern kernels, this should at least pass our internal guard
            // (might still fail with EACCES if sibling threads aren't ready, but that's a different error)
            verifySupportedLandlockInstallation {
                ContainedExecutors.installOnProcess(policyWithFs as Policy<PolicyScope.ProcessWideSafe, io.mazewall.enforcer.api.Uncompiled>)
            }
        } else {
            org.junit.jupiter.api.assertThrows<io.mazewall.enforcer.api.UnsupportedKernelFeatureException> {
                ContainedExecutors.installOnProcess(policyWithFs as Policy<PolicyScope.ProcessWideSafe, io.mazewall.enforcer.api.Uncompiled>)
            }
        }
    }

    private fun verifySupportedLandlockInstallation(install: () -> Unit) {
        val installation = runCatching { install() }
        val failure = installation.exceptionOrNull() ?: return
        exceptionalOutcome(failure)?.let { throw it }
        if (!hasPrimaryLandlockAccessDenial(failure)) throw failure
    }

    @Test
    fun `supported Landlock installation rejects absent diagnostic identity`() {
        val original = IllegalArgumentException(null as String?)
        val observed = assertFailsWith<IllegalArgumentException> {
            verifySupportedLandlockInstallation { throw original }
        }
        assertSame(original, observed)
    }

    @Test
    fun `supported Landlock installation rejects unrelated numeric diagnostic identity`() {
        val original = IllegalArgumentException("unrelated business value 13")
        val observed = assertFailsWith<IllegalArgumentException> {
            verifySupportedLandlockInstallation { throw original }
        }
        assertSame(original, observed)
    }

    @Test
    fun `supported Landlock installation preserves fatal identity`() {
        val original = LinkageError("fatal install failure")
        val observed = assertFailsWith<LinkageError> {
            verifySupportedLandlockInstallation { throw original }
        }
        assertSame(original, observed)
    }

    @Test
    fun `supported Landlock installation accepts genuine access denial evidence`() {
        verifySupportedLandlockInstallation { throw IOException("install failed (error=13)") }
    }

    @org.junit.jupiter.api.Nested
    inner class SupportedLandlockBoundaryControls {
        @Test
        fun supportedLandlockRejectsSuppressedCleanupPermission() {
            val primary = IllegalStateException("bad installation").apply {
                addSuppressed(IOException("cleanup failed (error=13)"))
            }
            assertSame(
                primary,
                assertFailsWith<IllegalStateException> {
                verifySupportedLandlockInstallation { throw primary }
            },
            )
        }

        @Test
        fun supportedLandlockPromotesNestedFatalBeforePermission() {
            val fatal = LinkageError("actual fatal installation cleanup")
            val primary = IOException("install failed (error=13)").apply { addSuppressed(fatal) }
            assertSame(
                fatal,
                assertFailsWith<LinkageError> {
                verifySupportedLandlockInstallation { throw primary }
            },
            )
        }

        @Test
        fun supportedLandlockRestoresNestedInterruptionBeforePermission() {
            val interruption = InterruptedException("actual transported installation interruption")
            val primary = IOException("install failed (error=13)").apply { addSuppressed(interruption) }
            try {
                assertSame(
                    interruption,
                    assertFailsWith<InterruptedException> {
                    verifySupportedLandlockInstallation { throw primary }
                },
                )
                kotlin.test.assertTrue(Thread.currentThread().isInterrupted)
            } finally {
                Thread.interrupted()
            }
        }

        @Test
        fun supportedLandlockFatalRetainsReachableInterruptionStatus() {
            val fatal = LinkageError("actual fatal installation")
            val interruption = InterruptedException("actual nested installation interruption")
            val primary = IOException("install failed (error=13)").apply {
                addSuppressed(fatal)
                addSuppressed(interruption)
            }
            try {
                assertSame(
                    fatal,
                    assertFailsWith<LinkageError> {
                    verifySupportedLandlockInstallation { throw primary }
                },
                )
                kotlin.test.assertTrue(Thread.currentThread().isInterrupted)
                kotlin.test.assertTrue(fatal.suppressed.any { it === primary }, "Promoted fatal must retain the actual operation and interruption graph")
            } finally {
                Thread.interrupted()
            }
        }
    }

    @Test
    fun `supported Landlock installation accepts actual successful install`() {
        verifySupportedLandlockInstallation { }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun `installOnProcess respects Landlock support limits`() {
        IsolatedProcessTester.runIsolatedMethod(this::class.java.name, "testLandlockSupportLimits")
    }
}
