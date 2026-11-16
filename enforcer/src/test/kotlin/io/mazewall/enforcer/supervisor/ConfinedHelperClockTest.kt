package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.MockNativeEngine
import io.mazewall.ffi.internal.HelperSecurityObservation
import io.mazewall.ffi.memory.writeLong
import io.mazewall.ffi.networking.ConfinedDeadline
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.NativeArg
import org.junit.jupiter.api.Test
import java.nio.file.AccessDeniedException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConfinedHelperClockTest {
    private val key = "(dev=4,ino=4026531834)"
    private val offsets = "monotonic           0         0\nboottime            0         0\n"

    @Test
    fun `observes actual task namespace objects and bounded raw offsets`() {
        val paths = mutableListOf<Path>()
        val observation = HelperSecurityObservation(
            namespaceKey = {
                paths.add(it)
                key
            },
            readBounded = { path, limit ->
                assertEquals(Path.of("/proc/321/timens_offsets"), path)
                assertEquals(256, limit)
                offsets.toByteArray()
            },
        ).observeTimeDomain(Pid(123), Tid(321))
        assertEquals(listOf(Path.of("/proc/123/task/321/ns/time"), Path.of("/proc/123/task/321/ns/time_for_children")), paths)
        assertEquals(key, observation.current)
        assertEquals(key, observation.forChildren)
        assertEquals(0L, observation.monotonicOffsetSeconds)
        assertEquals(0, observation.monotonicOffsetNanos)
        assertEquals(offsets, observation.rawOffsets)
        observation.requireCommon(observation)
    }

    @Test
    fun `pending child switch peer mismatch and nonzero offsets refuse common origin`() {
        fun observe(
            current: String = key,
            child: String = current,
            text: String = offsets,
        ) = HelperSecurityObservation(
                namespaceKey = { if (it.fileName.toString() == "time") current else child },
                readBounded = { _, _ -> text.toByteArray() },
            ).observeTimeDomain(Pid(123), Tid(123))
        val expected = observe()
        val different = "(dev=4,ino=4026531835)"
        val bad = listOf(
            observe(child = different),
            observe(current = different),
            observe(text = "monotonic -1 0\nboottime 0 0\n"),
            observe(text = "monotonic 0 1\nboottime 0 0\n"),
        )
        bad.forEach { actual ->
            assertFailsWith<IllegalArgumentException> { expected.requireCommon(actual) }
            assertFailsWith<IllegalArgumentException> { actual.requireCommon(expected) }
        }
    }

    @Test
    fun `unknown missing duplicate malformed and oversized observations refuse`() {
        val bad = listOf(
            "",
            "monotonic 0 0\n",
            "monotonic 0 0\nmonotonic 0 0\n",
            "monotonic 0 0\nunknown 0 0\n",
            "monotonic 0 -1\nboottime 0 0\n",
            "monotonic 0 1000000000\nboottime 0 0\n",
            "monotonic 9223372036854775808 0\nboottime 0 0\n",
            "monotonic 0 0 extra\nboottime 0 0\n",
            "monotonic 0 0\nboottime 0 0",
            offsets + " ".repeat(257),
            offsets + "\u0000",
            "monotonic 0 0\nboottime 0 -1\n",
        )
        bad.forEach { text ->
            assertFails(text) {
                HelperSecurityObservation({ key }, { _, _ -> text.toByteArray() }).observeTimeDomain(Pid(123), Tid(123))
            }
        }
        for (invalid in listOf("time:[4026531834]", "", "(dev=4,ino=0)\n", "unknown")) {
            assertFails { HelperSecurityObservation({ invalid }, { _, _ -> offsets.toByteArray() }).observeTimeDomain(Pid(123), Tid(123)) }
        }
        for (failure in listOf(AccessDeniedException("time"), NoSuchFileException("time"))) {
            val caught = assertFails { HelperSecurityObservation({ throw failure }, { _, _ -> error("must not read") }).observeTimeDomain(Pid(123), Tid(123)) }
            assertSame(failure, caught)
        }
    }

    @Test
    fun `mint uses raw monotonic clock and smaller remaining duration after observation`() {
        val native = clock(10, 7)
        val remaining = ArrayDeque(listOf(9_000_000_000L, 40L))
        val deadline = ConfinedDeadline.mint(native) { remaining.removeFirst() }
        assertEquals(10_000_000_047L, deadline.nanos)
        assertTrue(remaining.isEmpty())
        assertEquals(15_000_000_007L, ConfinedDeadline.mint(native) { Long.MAX_VALUE }.nanos)
    }

    @Test
    fun `invalid clock budget and overflow never mint permission`() {
        for ((seconds, nanos) in listOf(-1L to 0L, 0L to -1L, 0L to 1_000_000_000L, Long.MAX_VALUE to 0L, 9_223_372_036L to 854_775_807L)) {
            val failure = assertIs<ConfinedHelperRejection>(assertFails { ConfinedDeadline.mint(clock(seconds, nanos)) { 1L } })
            assertEquals(ConfinedRejectReason.RuntimeClosure, failure.reason)
            assertEquals(null, failure.errno)
        }
        var reads = 0
        val native = MockNativeEngine().also {
            it.onSyscall = { _, _, _, _, _, _, _ ->
            reads++
            error("clock must not run")
        }
        }
        assertFailsWith<java.util.concurrent.TimeoutException> { ConfinedDeadline.mint(native) { 0L } }
        assertEquals(0, reads)
        val remaining = ArrayDeque(listOf(1L, 0L))
        assertFailsWith<java.util.concurrent.TimeoutException> { ConfinedDeadline.mint(clock(10, 0)) { remaining.removeFirst() } }
        for (errno in listOf(1, 13, 22)) {
            val denied = MockNativeEngine().also { it.syscallResult = LinuxNative.SyscallResult.Error(errno, -1L) }
            val failure = assertIs<ConfinedHelperRejection>(assertFails { ConfinedDeadline.mint(denied) { 100L } })
            assertEquals(if (errno == 22) ConfinedRejectReason.RuntimeClosure else ConfinedRejectReason.Permission, failure.reason)
            assertEquals(errno, failure.errno)
        }
    }

    @Test
    fun `offered deadline cannot extend first byte cap or renew with progress`() {
        val cap = ConfinedDeadline(2_000_000_000L)
        assertEquals(2_000_000_000L, cap.accept(ConfinedDeadline(4_000_000_000L), 1_000_000_000L).nanos)
        val shortened = cap.accept(ConfinedDeadline(1_500_000_000L), 1_000_000_000L)
        assertEquals(1_500_000_000L, shortened.accept(cap, 1_400_000_000L).nanos)
        assertFails { shortened.remainingNanos(1_500_000_000L) }
        assertFails { cap.accept(ConfinedDeadline(6_000_000_001L), 1_000_000_000L) }
        assertFails { cap.accept(ConfinedDeadline(1_000_000_000L), 1_000_000_000L) }
        assertFails { ConfinedDeadline(0) }
        assertEquals(25, cap.pollMillis(1_000_000_000L))
        assertEquals(1, cap.pollMillis(1_999_999_999L))
    }

    private fun clock(
        seconds: Long,
        nanos: Long,
    ): MockNativeEngine =
        MockNativeEngine().also { native ->
        native.onSyscall = { nr, id, buffer, _, _, _, _ ->
            assertEquals(Arch.current().clock_gettime.toLong(), nr)
            assertEquals(1L, id.asLong)
            val timespec = (buffer as NativeArg.MemoryArg).value
            assertEquals(16L, timespec.byteSize())
            assertEquals(0L, timespec.address() % 8)
            timespec.writeLong(0, seconds)
            timespec.writeLong(8, nanos)
            LinuxNative.SyscallResult.Success(0L)
        }
    }
}
