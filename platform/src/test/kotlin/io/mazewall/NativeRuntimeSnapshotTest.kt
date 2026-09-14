package io.mazewall

import io.mazewall.*
import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.RuntimeSnapshot
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
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
