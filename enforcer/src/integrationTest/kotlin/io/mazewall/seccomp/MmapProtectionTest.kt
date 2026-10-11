package io.mazewall.seccomp

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.BaseIntegrationTest
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.enforcer.api.Policy
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Test
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Integration tests verifying that [BpfFilter] correctly performs argument inspection on both
 * `mmap` and `mprotect` system calls to prevent dynamic shellcode execution.
 *
 * Specifically, the BPF filter intercepts these system calls and validates the third argument
 * (`prot` at `args[2]` in `struct seccomp_data`, mapped at offset 32), returning `EPERM` if the
 * `PROT_EXEC` (0x04) bit is set. This protects worker threads from creating executable memory
 * regions without interfering with JIT compilation running on unrestricted threads.
 */
class MmapProtectionTest : BaseIntegrationTest() {
    private class ProtectionAttempt(
        val syscallNr: Int,
    ) {
        var result: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>? = null
        var nativeFailure: Throwable? = null
        var primaryFailure: Throwable? = null

        fun run(operation: () -> Unit) {
            val outcome = runCatching(operation)
            primaryFailure = outcome.exceptionOrNull()
            outcome.getOrThrow()
        }
    }

    companion object {
        init {
            val linker = Linker.nativeLinker()
            val mmap = linker.downcallHandle(
                linker.defaultLookup().find("mmap").get(),
                FunctionDescriptor.of(
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_LONG,
                ),
            )
            val mprotect = linker.downcallHandle(
                linker.defaultLookup().find("mprotect").get(),
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_LONG,
                    ValueLayout.JAVA_INT,
                ),
            )
            warmMprotect {
                mprotect.invoke(java.lang.foreign.MemorySegment.NULL, 0L, 0)
            }
        }

        private fun warmMprotect(invoke: () -> Any?): Any? {
            val invocation = runCatching { invoke() }
            return invocation.getOrThrow()
        }

        // Controls below supply modeled results, never purported kernel receipts.
        // Real assertions pass the existing native API's immediately captured result here.
        private fun strictNativeReturn(
            context: String = "native protection",
            syscallNr: Int? = null,
            attempt: ProtectionAttempt? = null,
            invoke: () -> LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>,
        ): Long {
            val result = runCatching { invoke() }.getOrThrow()
            attempt?.result = result
            val returned = runCatching { result.getOrThrow(context) }
            attempt?.nativeFailure = returned.exceptionOrNull()
            if (result is LinuxNative.SyscallResult.Error && result.errno == 1) {
                throw ContainmentViolationException(
                    "$context denied: errno=${result.errno}, raw return=${result.rawValue}",
                    cause = returned.exceptionOrNull(),
                    errno = result.errno,
                    syscallNr = syscallNr,
                    evidence = ContainmentViolationEvidence.OBSERVED_POLICY_DENIAL,
                )
            }
            return returned.getOrThrow()
        }

        private fun assertObservedProtectionDenial(
            failure: ExecutionException,
            attempt: ProtectionAttempt,
        ) {
            val exceptional = exceptionalProtectionFailure(failure)
            val primary = attempt.primaryFailure
            if (exceptional != null ||
                primary !is ContainmentViolationException ||
                primary.evidence != ContainmentViolationEvidence.OBSERVED_POLICY_DENIAL ||
                primary.suppressed.isNotEmpty()
            ) {
                throw exceptional ?: primary ?: failure
            }
            val result = attempt.result
            assertTrue(result is LinuxNative.SyscallResult.Error, "Protection must return an actual captured error: $result")
            kotlin.test.assertEquals(1, result.errno, "Protection must observe EPERM")
            kotlin.test.assertEquals(-1L, result.rawValue)
            kotlin.test.assertSame(primary, failure.cause, "Translation must preserve the actual operation primary")
            kotlin.test.assertEquals(ContainmentViolationEvidence.OBSERVED_POLICY_DENIAL, primary.evidence)
            kotlin.test.assertEquals(result.errno, primary.errno)
            kotlin.test.assertEquals(attempt.syscallNr, primary.syscallNr)
            kotlin.test.assertNotNull(attempt.nativeFailure, "Captured error must have produced getOrThrow's failure")
            kotlin.test.assertSame(attempt.nativeFailure, primary.cause, "Denial must bind to the actual native getOrThrow failure")
        }

        private fun exceptionalProtectionFailure(failure: Throwable): Throwable? {
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

        private fun anonymousMapping(prot: Int): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
            LinuxNative.fileSystem.mmap(0L, 4096L, MmapProt(prot), MmapFlags(0x22), FileDescriptor.ANON, 0L)

        private fun protect(
            address: Long,
            prot: Int,
            pkey: Boolean = false,
        ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> =
            LinuxNative.raw.syscall(
                (if (pkey) Arch.current().pkeyMprotect else Arch.current().mprotect).toLong(),
                NativeArg.LongArg(address),
                NativeArg.LongArg(4096L),
                NativeArg.MmapProtArg(MmapProt(prot)),
                NativeArg.LongArg(0L),
            )

        private fun <T> withOwnedMapping(
            result: LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled>,
            body: (Long) -> T,
        ): T {
            val address = result.getOrThrow("mmap allocation")
            // Ownership begins before any assertion or protection attempt, including exec success.
            val primary = runCatching { body(address) }
            val cleanup = runCatching {
                LinuxNative.raw
                    .syscall(
                    Arch.current().munmap.toLong(),
                    NativeArg.LongArg(address),
                    NativeArg.LongArg(4096L),
                ).getOrThrow("munmap owned allocation")
            }
            val failure = primary.exceptionOrNull()
            val cleanupFailure = cleanup.exceptionOrNull()
            if (cleanupFailure != null) {
                if (failure == null) throw cleanupFailure
                if (cleanupFailure is Error && failure !is Error) {
                    cleanupFailure.addSuppressed(failure)
                    throw cleanupFailure
                }
                if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            }
            return primary.getOrThrow()
        }
    }

    @Test
    fun `quality warmup preserves unexpected invocation failure`() {
        val failure = IllegalArgumentException("invalid invocation")
        val actual = kotlin.test.assertFails { warmMprotect { throw failure } }
        kotlin.test.assertSame(failure, actual)
    }

    @Test
    fun `quality warmup preserves fatal invocation failure`() {
        val failure = AssertionError("fatal invocation")
        val actual = kotlin.test.assertFails { warmMprotect { throw failure } }
        kotlin.test.assertSame(failure, actual)
    }

    @Test
    fun `quality warmup retains raw native return semantics`() {
        kotlin.test.assertEquals(0, warmMprotect { 0 })
        kotlin.test.assertEquals(-1, warmMprotect { -1 })
    }

    @Test
    fun `quality denial boundary rejects EINVAL as permission evidence`() {
        val failure = assertFailsWith<IllegalStateException> {
            strictNativeReturn { LinuxNative.SyscallResult.Error(22, -1L) }
        }
        assertTrue(failure.message.orEmpty().contains("22"))
        assertTrue(failure.message.orEmpty().contains("-1"))
    }

    @Test
    fun `quality denial boundary rejects unknown errno as permission evidence`() {
        val failure = assertFailsWith<IllegalStateException> {
            strictNativeReturn { LinuxNative.SyscallResult.Error(0, -1L) }
        }
        assertTrue(failure.message.orEmpty().contains("-1"))
    }

    @Test
    fun `quality denial boundary preserves successful raw return`() {
        kotlin.test.assertEquals(0L, strictNativeReturn { LinuxNative.SyscallResult.Success(0L) })
        kotlin.test.assertEquals(4096L, strictNativeReturn { LinuxNative.SyscallResult.Success(4096L) })
    }

    @Test
    fun `quality denial boundary preserves unexpected invocation failure identity`() {
        val failure = IllegalArgumentException("unrelated native invocation failure")
        val actual = kotlin.test.assertFails { strictNativeReturn { throw failure } }
        kotlin.test.assertSame(failure, actual)
    }

    @Test
    fun `quality denial boundary preserves fatal invocation failure identity`() {
        val failure = AssertionError("fatal native invocation failure")
        val actual = kotlin.test.assertFails { strictNativeReturn { throw failure } }
        kotlin.test.assertSame(failure, actual)
    }

    private fun translatedProtectionFailure(
        attempt: ProtectionAttempt,
        operation: () -> Unit,
    ): ExecutionException {
        val executor = Executors.newSingleThreadExecutor()
        val contained = ContainedExecutors.wrap(executor, ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build())
        try {
            return assertFailsWith<ExecutionException> { contained.submit { attempt.run(operation) }.get() }
        } finally {
            executor.close()
        }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun finalProtectionAssertionRejectsTranslatedModeledEinval() {
        val attempt = ProtectionAttempt(Arch.current().mprotect)
        val transported = translatedProtectionFailure(attempt) {
            strictNativeReturn("modeled mprotect", attempt.syscallNr, attempt) { LinuxNative.SyscallResult.Error(22, -1L) }
        }
        assertTrue(transported.cause is ContainmentViolationException, "Control must traverse the actual executor translator")
        kotlin.test.assertSame(attempt.primaryFailure, kotlin.test.assertFails { assertObservedProtectionDenial(transported, attempt) })
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun finalProtectionAssertionRejectsTranslatedSuppressedCleanup() {
        val attempt = ProtectionAttempt(Arch.current().mmap)
        val primary = IllegalStateException("modeled executable operation unexpectedly succeeded").apply {
            addSuppressed(java.io.IOException("modeled cleanup failed errno=1"))
        }
        val transported = translatedProtectionFailure(attempt) {
            strictNativeReturn("modeled mmap", attempt.syscallNr, attempt) { LinuxNative.SyscallResult.Success(0L) }
            throw primary
        }
        assertTrue(transported.cause is ContainmentViolationException, "Control must traverse the actual executor translator")
        kotlin.test.assertSame(primary, kotlin.test.assertFails { assertObservedProtectionDenial(transported, attempt) })
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun finalProtectionAssertionRejectsWrongObservedSyscall() {
        val attempt = ProtectionAttempt(Arch.current().mmap)
        val transported = translatedProtectionFailure(attempt) {
            strictNativeReturn("modeled protection", Arch.current().mprotect, attempt) { LinuxNative.SyscallResult.Error(1, -1L) }
        }
        assertFailsWith<AssertionError> { assertObservedProtectionDenial(transported, attempt) }
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun finalProtectionAssertionRejectsObservedSuppressedViolation() {
        val attempt = ProtectionAttempt(Arch.current().mmap)
        val modeled = ContainmentViolationException(
            "modeled suppressed protection receipt",
            errno = 1,
            syscallNr = attempt.syscallNr,
            evidence = ContainmentViolationEvidence.OBSERVED_POLICY_DENIAL,
        )
        val primary = IllegalStateException("actual unrelated primary").apply { addSuppressed(modeled) }
        val transported = translatedProtectionFailure(attempt) { throw primary }
        kotlin.test.assertSame(primary, kotlin.test.assertFails { assertObservedProtectionDenial(transported, attempt) })
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun finalProtectionAssertionPromotesActualNestedFatal() {
        val attempt = ProtectionAttempt(Arch.current().mprotect)
        val fatal = LinkageError("actual fatal protection cleanup")
        val transported = translatedProtectionFailure(attempt) {
            val denial = kotlin.test.assertFails {
                strictNativeReturn("modeled protection", attempt.syscallNr, attempt) { LinuxNative.SyscallResult.Error(1, -1L) }
            }
            denial.addSuppressed(fatal)
            throw denial
        }
        kotlin.test.assertSame(fatal, assertFailsWith<LinkageError> { assertObservedProtectionDenial(transported, attempt) })
        assertTrue(fatal.suppressed.any { it === transported }, "Promoted fatal must retain the actual transport and operation graph")
    }

    @Test
    @EnabledIfLinuxAndSupported
    fun finalProtectionAssertionAcceptsBoundModeledObservedDenial() {
        val attempt = ProtectionAttempt(Arch.current().mprotect)
        val transported = translatedProtectionFailure(attempt) {
            strictNativeReturn("modeled protection", attempt.syscallNr, attempt) { LinuxNative.SyscallResult.Error(1, -1L) }
        }
        assertObservedProtectionDenial(transported, attempt)
    }

    /**
     * Verifies that attempting to allocate executable memory directly using `mmap` with the
     * `PROT_EXEC` flag set causes the kernel to block the call with `EPERM`, which propagates
     * as a [io.mazewall.enforcer.ContainmentViolationException].
     *
     * The BPF filter performs argument inspection on `mmap` by loading the lower 32 bits of
     * `args[2]` (offset 32) and checking if `PROT_EXEC` (0x04) is present.
     */
    @Test
    @EnabledIfLinuxAndSupported
    fun `mmap with PROT_EXEC is blocked even if mmap syscall is allowed`() {
        val attempt = ProtectionAttempt(Arch.current().mmap)
        val executor = Executors.newSingleThreadExecutor()
        val safeExecutor = ContainedExecutors.wrap(executor, ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).block(Syscall.PTRACE).build())

        try {
            assertFailsWith<ExecutionException> {
                safeExecutor
                    .submit {
                        attempt.run {
                        val result = anonymousMapping(0x1 or 0x2 or 0x4)
                        if (result is LinuxNative.SyscallResult.Success) {
                            withOwnedMapping(result) {
                                throw IllegalStateException("CRITICAL SECURITY BYPASS: executable mmap succeeded")
                            }
                        }
                        strictNativeReturn("mmap(PROT_EXEC)", attempt.syscallNr, attempt) { result }
                        }
                    }.get()
            }.let { e ->
                assertObservedProtectionDenial(e, attempt)
            }
        } finally {
            executor.shutdown()
        }
    }

    /**
     * Verifies that attempting to mark an existing non-executable memory region as executable
     * using `mprotect` with `PROT_EXEC` causes the kernel to block the call with `EPERM`, which
     * propagates as a [ContainmentViolationException].
     *
     * The BPF filter performs argument inspection on `mprotect` by loading the lower 32 bits of
     * `args[2]` (offset 32) and checking if `PROT_EXEC` (0x04) is present.
     */
    @Test
    @EnabledIfLinuxAndSupported
    fun `mprotect with PROT_EXEC is blocked even if mprotect syscall is allowed`() {
        val attempt = ProtectionAttempt(Arch.current().mprotect)
        val executor = Executors.newSingleThreadExecutor()
        val safeExecutor =
            ContainedExecutors.wrap(
                executor,
                ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build(),
            ) // Policy that allows mprotect by default but BPF blocks PROT_EXEC

        try {
            assertFailsWith<ExecutionException> {
                safeExecutor
                    .submit {
                        attempt.run {
                        withOwnedMapping(anonymousMapping(0x1 or 0x2)) { address ->
                            val result = protect(address, 0x1 or 0x4)
                            strictNativeReturn("mprotect(PROT_EXEC)", attempt.syscallNr, attempt) { result }
                            throw IllegalStateException("CRITICAL SECURITY BYPASS: executable mprotect succeeded")
                        }
                        }
                    }.get()
            }.let { e ->
                assertObservedProtectionDenial(e, attempt)
            }
        } finally {
            executor.shutdown()
        }
    }

    /**
     * Verifies that attempting to mark memory as executable using modern `pkey_mprotect`
     * causes the kernel to block the call with `EPERM`.
     */
    @Test
    @EnabledIfLinuxAndSupported
        fun `pkey_mprotect with PROT_EXEC is blocked`() {
        val attempt = ProtectionAttempt(Arch.current().pkeyMprotect)
        val executor = Executors.newSingleThreadExecutor()
        val safeExecutor = ContainedExecutors.wrap(executor, ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build())

        try {
            assertFailsWith<ExecutionException> {
                safeExecutor
                    .submit {
                        attempt.run {
                        withOwnedMapping(anonymousMapping(0x1 or 0x2)) { address ->
                            val result = protect(address, 0x1 or 0x4, pkey = true)
                            strictNativeReturn("pkey_mprotect(PROT_EXEC)", attempt.syscallNr, attempt) { result }
                            throw IllegalStateException("CRITICAL SECURITY BYPASS: executable pkey_mprotect succeeded")
                        }
                        }
                    }.get()
            }.let { e ->
                assertObservedProtectionDenial(e, attempt)
            }
        } finally {
            executor.shutdown()
        }
    }
}
