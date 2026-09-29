package io.mazewall.profiler.tierE.daemon

import io.mazewall.profiler.internal.attribution.AttributionStatus
import io.mazewall.profiler.internal.attribution.ExecutionContext
import io.mazewall.profiler.internal.attribution.ExecutionId
import io.mazewall.profiler.internal.attribution.ExecutionKind
import io.mazewall.profiler.internal.attribution.InvocationId
import io.mazewall.profiler.internal.attribution.ManagedFrame
import io.mazewall.profiler.internal.attribution.ManagedFrameKind
import io.mazewall.profiler.internal.attribution.ResolutionStatus
import io.mazewall.profiler.internal.attribution.ResolvedSyscall
import io.mazewall.profiler.internal.attribution.StackCaptureQuality
import io.mazewall.profiler.internal.attribution.StackDefinition
import io.mazewall.profiler.internal.attribution.StackTraceId
import io.mazewall.profiler.internal.attribution.SyscallAttribution
import io.mazewall.profiler.internal.attribution.TaskIdentity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

class TierEEventJsonlTest {
    private val target = TierERegisteredTarget("orders", 123, 0x4d5a, 99, Path.of("/agent.so"), 10, "orders.mzse")

    @Test
    fun `event keeps unsigned ring fields frames and session local execution identity`() {
        val stack = StackDefinition(
            StackTraceId(4),
            listOf(ManagedFrame("loader", "demo.Read", "readConfiguration", "()V", 23, ManagedFrameKind.JAVA)),
            StackCaptureQuality.COMPLETE,
        )
        val observation = SyscallAttribution(
            TaskIdentity(99, 12, 0),
            7,
            257,
            listOf(-1L, 0, 0, 0, 0, 0),
            InvocationId(5),
            AttributionStatus.PENDING_DICTIONARY,
            kernelTimestampNanos = ULong.MAX_VALUE,
            hostTgid = UInt.MAX_VALUE,
        )
        val resolved = ResolvedSyscall(observation, stack, ResolutionStatus.RESOLVED, ExecutionContext(ExecutionId(8), ExecutionKind.PLATFORM))

        val row = Json.parseToJsonElement(TierEEventJsonl.event(target, 1, resolved)).jsonObject
        assertEquals("event", row.getValue("type").jsonPrimitive.content)
        assertEquals("18446744073709551615", row.getValue("kernel_timestamp_ns").jsonPrimitive.content)
        assertEquals("4294967295", row.getValue("host_tgid").jsonPrimitive.content)
        assertEquals(
            "18446744073709551615",
            row
            .getValue("arguments")
            .jsonArray
            .first()
            .jsonPrimitive.content,
        )
        assertEquals(
            "readConfiguration",
            row
            .getValue("frames")
            .jsonArray
            .single()
            .jsonObject
            .getValue("method_name")
            .jsonPrimitive.content,
        )
        assertEquals("8", row.getValue("execution_id").jsonPrimitive.content)
        assertEquals("orders", row.getValue("registration_id").jsonPrimitive.content)
        assertEquals("19802", row.getValue("session_epoch").jsonPrimitive.content)
        assertEquals(
            "2",
            Json
            .parseToJsonElement(TierEEventJsonl.event(target, 2, resolved))
            .jsonObject
            .getValue("ordinal")
            .jsonPrimitive.content,
        )
    }

    @Test
    fun `unattributed row stays present with explicit failure reason and no invented stack`() {
        val observed = SyscallAttribution(
            TaskIdentity(99, 12, 0),
            2,
            257,
            List(6) { 0L },
            null,
            AttributionStatus.NO_ACTIVE_INVOCATION,
            captureFlags = 32,
            kernelTimestampNanos = 12uL,
            hostTgid = 22u,
        )
        val row = Json.parseToJsonElement(TierEEventJsonl.event(target, 2, ResolvedSyscall(observed, null, ResolutionStatus.NO_ACTIVE_INVOCATION))).jsonObject

        assertEquals("NO_ACTIVE_INVOCATION", row.getValue("resolution_status").jsonPrimitive.content)
        assertEquals("32", row.getValue("capture_flags").jsonPrimitive.content)
        assertEquals(0, row.getValue("frames").jsonArray.size)
        assertEquals("null", row.getValue("stack_id").toString())
    }
}
