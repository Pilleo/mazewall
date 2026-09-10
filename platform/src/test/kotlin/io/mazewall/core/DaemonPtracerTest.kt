package io.mazewall.core

import io.mazewall.LinuxNative
import io.mazewall.MockNativeEngine
import io.mazewall.MockProcess
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DaemonPtracerTest {
    @Test
    fun `runtime snapshots coordinate the same native process authority`() {
        val engine = MockNativeEngine()
        val snapshot = io.mazewall.RuntimeSnapshot(engine)
        val first = MockProcess(101L)
        val second = MockProcess(202L)
        val attempts = mutableListOf<Long>()
        engine.process.onPrctl = { command ->
            attempts += (command as PrctlCommand.SetPtracer).tracerPid
            LinuxNative.SyscallResult.Success(0L)
        }
        DaemonPtracer.authorize(engine, first)
        DaemonPtracer.authorize(snapshot, second)
        DaemonPtracer.authorize(engine, first)
        assertEquals(listOf(101L, 202L, 101L), attempts)
    }

    @Test
    fun `unchanged grant does not repeat a now forbidden prctl`() {
        val engine = MockNativeEngine()
        val daemon = MockProcess(101L)
        var calls = 0
        engine.process.onPrctl = {
            calls++
            if (calls == 1) LinuxNative.SyscallResult.Success(0L) else LinuxNative.SyscallResult.Error(1, -1L)
        }
        DaemonPtracer.authorize(engine, daemon)
        DaemonPtracer.authorize(engine, daemon)
        assertEquals(1, calls)
    }

    @Test
    fun `switching daemons requires successful kernel authorization and failed attempts are retried`() {
        val engine = MockNativeEngine()
        val first = MockProcess(101L)
        val second = MockProcess(202L)
        val attempts = mutableListOf<Long>()
        engine.process.onPrctl = { command ->
            attempts += (command as PrctlCommand.SetPtracer).tracerPid
            if (attempts.size == 2) LinuxNative.SyscallResult.Error(13, -1L) else LinuxNative.SyscallResult.Success(0L)
        }
        DaemonPtracer.authorize(engine, first)
        assertThrows<IllegalStateException> { DaemonPtracer.authorize(engine, second) }
        DaemonPtracer.authorize(engine, second)
        DaemonPtracer.authorize(engine, first)
        assertEquals(listOf(101L, 202L, 202L, 101L), attempts)
    }

    @Test
    fun `retired or reused PID cannot reuse a stale grant`() {
        val engine = MockNativeEngine()
        val first = MockProcess(101L)
        var calls = 0
        engine.process.onPrctl = {
            calls++
            LinuxNative.SyscallResult.Success(0L)
        }
        DaemonPtracer.authorize(engine, first)
        val replacement = MockProcess(101L)
        DaemonPtracer.authorize(engine, replacement)
        DaemonPtracer.retire(engine, first)
        DaemonPtracer.authorize(engine, replacement)
        DaemonPtracer.retire(engine, replacement)
        DaemonPtracer.authorize(engine, replacement)
        assertEquals(3, calls)
    }
}
