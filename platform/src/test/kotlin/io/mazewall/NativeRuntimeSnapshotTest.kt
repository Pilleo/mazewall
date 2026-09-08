package io.mazewall

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class NativeRuntimeSnapshotTest {
    @AfterEach
    fun resetEngine() {
        LinuxNative.resetToDefault()
    }

    @Test
    fun `snapshot keeps one engine after a global test swap`() {
        val first = MockNativeEngine()
        val second = MockNativeEngine()
        LinuxNative.setEngine(first)
        val snapshot = LinuxNative.snapshot()

        LinuxNative.setEngine(second)

        assertSame(first, snapshot.engineIdentity)
        assertSame(first.fileSystem, snapshot.fileSystem)
    }
}
