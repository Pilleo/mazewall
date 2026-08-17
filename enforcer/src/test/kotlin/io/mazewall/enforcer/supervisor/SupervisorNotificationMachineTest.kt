package io.mazewall.enforcer.supervisor

import io.mazewall.core.Arch
import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.seccomp.SupervisedKind
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
        assertTrue(route !is SupervisorRoute.Continue)
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
    fun `jvm verdict wire codes round-trip`() {
        val deny = JvmVerdict.Deny(NativeConstants.EPERM)
        assertEquals(deny, SupervisorNotificationMachine.parseJvmVerdict(deny.toWire(), NativeConstants.EPERM))
        assertEquals(JvmVerdict.Allow, SupervisorNotificationMachine.parseJvmVerdict(JvmVerdict.Allow.toWire(), 0))
        assertEquals(JvmVerdict.InjectFd, SupervisorNotificationMachine.parseJvmVerdict(JvmVerdict.InjectFd.toWire(), 0))
    }

    @Test
    fun `inject target follows kind not raw nr`() {
        assertEquals(InjectTarget.Open, injectTarget(SupervisedKind.Open))
        assertEquals(InjectTarget.Accept, injectTarget(SupervisedKind.Accept))
        assertEquals(InjectTarget.Unsupported, injectTarget(SupervisedKind.Unknown))
    }

    @Test
    fun `exec rewrite is unsupported off x86_64`() {
        assertEquals(ExecRewritePlan.UnsupportedArch, planExecRewrite(Arch.AARCH64, "/bin/true", null))
        assertEquals(ExecRewritePlan.MissingPath, planExecRewrite(Arch.AMD64, null, null))
        assertEquals(ExecRewritePlan.Ready("/bin/true"), planExecRewrite(Arch.AMD64, "/bin/true", null))
    }

    @Test
    fun `jvm allow on unknown kind aborts`() {
        val route = SupervisorNotificationMachine.evaluateJvm(SupervisedKind.Unknown, JvmVerdict.Allow)
        assertTrue(route is SupervisorRoute.Abort)
    }
}
