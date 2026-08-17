package io.mazewall.enforcer.supervisor

import io.mazewall.core.Arch
import io.mazewall.ffi.NativeConstants
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class SupervisorNotificationMachineTest {

    private val arch = Arch.AMD64

    @Test
    fun `unknown nr is abort never continue`() {
        val route = SupervisorNotificationMachine.evaluateFastPath(
            SupervisorNotificationMachine.classify(999_999, arch),
            resolvedPath = null,
            rawPath = null,
        )
        val abort = route as SupervisorRoute.Abort
        assertEquals(NativeConstants.EPERM, abort.errno)
    }

    @Test
    fun `open of a bypass path continues without asking the jvm`() {
        val javaHome = Path.of(System.getProperty("java.home")).resolve("release")
        val route = SupervisorNotificationMachine.evaluateFastPath(
            SupervisedKind.Open,
            resolvedPath = javaHome,
            rawPath = javaHome.toString(),
        )
        assertEquals(SupervisorRoute.Continue, route)
    }

    @Test
    fun `unresolved class file falls back to continue`() {
        val route = SupervisorNotificationMachine.evaluateFastPath(
            SupervisedKind.Open,
            resolvedPath = null,
            rawPath = "Foo.class",
        )
        assertEquals(SupervisorRoute.Continue, route)
    }

    @Test
    fun `exec allow becomes secure exec not raw continue`() {
        val route = SupervisorNotificationMachine.evaluateJvm(SupervisedKind.Exec, JvmVerdict.Allow)
        assertEquals(SupervisorRoute.SecureExec, route)
    }

    @Test
    fun `spawn allow continues without fd inject`() {
        val route = SupervisorNotificationMachine.evaluateJvm(SupervisedKind.Spawn, JvmVerdict.Allow)
        assertEquals(SupervisorRoute.Continue, route)
    }

    @Test
    fun `open allow upgrades to inject fd`() {
        val route = SupervisorNotificationMachine.evaluateJvm(SupervisedKind.Open, JvmVerdict.Allow)
        assertEquals(SupervisorRoute.InjectFd, route)
    }

    @Test
    fun `unknown jvm decision code is null so handler fail-closes`() {
        assertEquals(null, SupervisorNotificationMachine.parseJvmVerdict(99, 0))
    }

    @Test
    fun `jvm allow on unknown kind aborts`() {
        val route = SupervisorNotificationMachine.evaluateJvm(SupervisedKind.Unknown, JvmVerdict.Allow)
        assertTrue(route is SupervisorRoute.Abort)
    }
}
