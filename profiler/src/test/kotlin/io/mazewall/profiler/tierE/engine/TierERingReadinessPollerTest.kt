package io.mazewall.profiler.tierE.engine

import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.PollFdSegment
import io.mazewall.platform.native.LinuxNative.SyscallResult
import io.mazewall.platform.native.RawSyscallOperations
import io.mazewall.profiler.internal.attribution.TierEEventReadiness
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TierERingReadinessPollerTest {
    @Test
    fun `POLLIN reports readiness`() {
        val poller = TierERingReadinessPoller(nativeReturning(success(1L), NativeConstants.POLLIN))

        assertEquals(TierEEventReadiness.READY, poller.await(17, 10))
    }

    @Test
    fun `zero poll result reports timeout`() {
        val poller = TierERingReadinessPoller(nativeReturning(success(0L), 0))

        assertEquals(TierEEventReadiness.TIMEOUT, poller.await(17, 10))
    }

    @Test
    fun `descriptor failure is fail closed`() {
        val poller = TierERingReadinessPoller(nativeReturning(success(1L), NativeConstants.POLLERR))

        assertFailsWith<IllegalStateException> { poller.await(17, 10) }
    }

    private fun nativeReturning(
        result: SyscallResult<Long, *>,
        revents: Short,
    ): RawSyscallOperations =
        Proxy.newProxyInstance(
        RawSyscallOperations::class.java.classLoader,
        arrayOf(RawSyscallOperations::class.java),
    ) { _, method, args ->
        check(method.name == "poll") { "unexpected native call ${method.name}" }
        PollFdSegment.of(args[0] as ManagedSegment).setRevents(revents)
        result
    } as RawSyscallOperations

    private fun success(value: Long): SyscallResult<Long, io.mazewall.platform.native.LinuxNative.SyscallHandledState.Unhandled> = SyscallResult.Success(value)
}
