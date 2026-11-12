package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.ffi.internal.HelperSecurityObservation
import io.mazewall.platform.daemon.UnixListenDaemonState
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

class SupervisorDaemonSharedStateTest {
    @Test
    fun `private daemon independently observes parent current and child time domains before helper startup`() {
        val native = LinuxNative.snapshot()
        val pid = Pid(ProcessHandle.current().pid().toInt())
        val parentPath = Path.of("/proc/${pid.value}/task/${pid.value}/ns/time_for_children")
        val selfPath = Path.of("/proc/${pid.value}/task/${native.process.gettid().value}/ns/time_for_children")
        kotlin.test.assertNotEquals(parentPath, selfPath)
        for (fault in listOf("none", "mismatch", "permission")) {
            val seen = mutableListOf<Path>()
            val denied = AccessDeniedException("controlled parent observation")
            val observations = HelperSecurityObservation(namespaceKey = { path ->
                seen.add(path)
                if (path == parentPath && fault == "permission") throw denied
                if (path == parentPath && fault == "mismatch") {
                    "(dev=1,ino=2)"
                } else {
                    Files.readAttributes(path, BasicFileAttributes::class.java).fileKey().toString()
                }
            })
            if (fault == "none") {
                val result = SupervisorDaemon.observeConfinedBootstrap(pid, native, observations)
                kotlin.test.assertEquals(result.parent.identity, result.child.identity)
                result.requireCommon()
                assertTrue(parentPath in seen && selfPath in seen)
            } else {
                val failure = kotlin.test.assertFailsWith<ConfinedHelperRejection> { SupervisorDaemon.observeConfinedBootstrap(pid, native, observations) }
                kotlin.test.assertEquals(if (fault == "permission") ConfinedRejectReason.Permission else ConfinedRejectReason.Ancestry, failure.reason)
                if (fault == "permission") {
                    kotlin.test.assertEquals(13, failure.errno)
                    assertSame(denied, failure.cause)
                }
            }
        }
    }

    @Test
    fun `daemon accepts only legacy or the exact private confined bootstrap arguments`() {
        kotlin.test.assertEquals(PoolProvisioning.Legacy, SupervisorDaemon.provisioning(arrayOf("/private/daemon.sock")))
        kotlin.test.assertEquals(PoolProvisioning.PrestartedConfined, SupervisorDaemon.provisioning(arrayOf("/private/daemon.sock", "--confined-read-v1")))
        for (args in listOf(emptyArray(), arrayOf(""), arrayOf("/private/daemon.sock", "unknown"), arrayOf("/private/daemon.sock", "--confined-read-v1", "extra"))) {
            kotlin.test.assertFailsWith<IllegalArgumentException> { SupervisorDaemon.provisioning(args) }
        }
    }

    @Test
    fun `supervisor engine state is the shared delegate state`() {
        val engine = SupervisorDaemonEngine(socketPath = "/tmp/supervisor-shared-state.sock")
        assertSame(UnixListenDaemonState.Uninitialized, engine.state)
        engine.triggerGlobalShutdown("unit")
        assertTrue(engine.state is UnixListenDaemonState.ShuttingDown)
    }
}
