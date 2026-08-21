package io.mazewall.enforcer.supervisor

import io.mazewall.core.Arch
import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.seccomp.SupervisedKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Path
import java.util.stream.Stream

internal class SupervisorNotificationMachineTest {

    private val arch = Arch.AMD64

    companion object {
        @JvmStatic
        fun jvmVerdictTestCases(): Stream<Pair<JvmVerdict, Int>> = Stream.of(
            Pair(JvmVerdict.Deny(NativeConstants.EPERM), NativeConstants.EPERM),
            Pair(JvmVerdict.Deny(NativeConstants.EACCES), NativeConstants.EACCES),
            Pair(JvmVerdict.Allow, 0),
            Pair(JvmVerdict.InjectFd, 0),
        )

        data class ExecRewriteCase(
            val name: String,
            val arch: Arch,
            val path: String?,
            val expectedPlan: ExecRewritePlan,
        ) {
            override fun toString(): String = name
        }

        @JvmStatic
        fun execRewriteCases(): Stream<ExecRewriteCase> = Stream.of(
            ExecRewriteCase("unsupported on AARCH64", Arch.AARCH64, "/bin/true", ExecRewritePlan.UnsupportedArch),
            ExecRewriteCase("missing path on AMD64", Arch.AMD64, null, ExecRewritePlan.MissingPath),
            ExecRewriteCase("ready on AMD64 with path", Arch.AMD64, "/bin/true", ExecRewritePlan.Ready("/bin/true")),
        )
    }

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

    @ParameterizedTest(name = "kind={0} verdict={1} -> {2}")
    @CsvSource(
        "Exec,    Allow,    SecureExec",
        "Spawn,   Allow,    Continue",
        "Open,    Allow,    InjectFd",
        "Accept,  Allow,    InjectFd",
        "Unknown, Allow,    Abort",
    )
    fun `test evaluateJvm routing table`(kindName: String, verdictType: String, expectedRouteType: String) {
        val kind = when (kindName) {
            "Open" -> SupervisedKind.Open
            "Accept" -> SupervisedKind.Accept
            "Connect" -> SupervisedKind.Connect
            "Exec" -> SupervisedKind.Exec
            "Spawn" -> SupervisedKind.Spawn
            else -> SupervisedKind.Unknown
        }
        val verdict = when (verdictType) {
            "Allow" -> JvmVerdict.Allow
            "InjectFd" -> JvmVerdict.InjectFd
            else -> JvmVerdict.Deny(NativeConstants.EPERM)
        }
        val route = SupervisorNotificationMachine.evaluateJvm(kind, verdict)
        val actualRouteType = when (route) {
            is SupervisorRoute.Continue -> "Continue"
            is SupervisorRoute.SecureExec -> "SecureExec"
            is SupervisorRoute.InjectFd -> "InjectFd"
            is SupervisorRoute.Abort -> "Abort"
            is SupervisorRoute.AskJvm -> "AskJvm"
        }
        assertEquals(expectedRouteType, actualRouteType)
    }

    @Test
    fun `unknown jvm decision code is null so handler fail-closes`() {
        assertNull(SupervisorNotificationMachine.parseJvmVerdict(99, 0))
    }

    @ParameterizedTest(name = "{0} round-trips wire format")
    @MethodSource("jvmVerdictTestCases")
    fun `jvm verdict wire codes round-trip`(testCase: Pair<JvmVerdict, Int>) {
        val (verdict, errno) = testCase
        assertEquals(verdict, SupervisorNotificationMachine.parseJvmVerdict(verdict.toWire(), errno))
    }

    @ParameterizedTest(name = "kind {0} maps to inject target {1}")
    @CsvSource(
        "Open, Open",
        "Accept, Accept",
        "Connect, Connect",
        "Unknown, Unsupported",
        "Exec, Unsupported",
        "Spawn, Unsupported",
    )
    fun `inject target follows kind not raw nr`(kindName: String, expectedTargetName: String) {
        val kind = when (kindName) {
            "Open" -> SupervisedKind.Open
            "Accept" -> SupervisedKind.Accept
            "Connect" -> SupervisedKind.Connect
            "Exec" -> SupervisedKind.Exec
            "Spawn" -> SupervisedKind.Spawn
            else -> SupervisedKind.Unknown
        }
        val target = injectTarget(kind)
        val actualTargetName = when (target) {
            is InjectTarget.Open -> "Open"
            is InjectTarget.Accept -> "Accept"
            is InjectTarget.Connect -> "Connect"
            is InjectTarget.Unsupported -> "Unsupported"
        }
        assertEquals(expectedTargetName, actualTargetName)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("execRewriteCases")
    fun `exec rewrite planning tests`(testCase: ExecRewriteCase) {
        assertEquals(testCase.expectedPlan, planExecRewrite(testCase.arch, testCase.path, null))
    }

    @Test
    fun `compile-time exhaustive coverage of supervisor model types`() {
        val kinds = listOf(
            SupervisedKind.Open,
            SupervisedKind.Accept,
            SupervisedKind.Connect,
            SupervisedKind.Exec,
            SupervisedKind.Spawn,
            SupervisedKind.Unknown,
        )
        for (kind in kinds) {
            when (kind) {
                SupervisedKind.Open -> Unit
                SupervisedKind.Accept -> Unit
                SupervisedKind.Connect -> Unit
                SupervisedKind.Exec -> Unit
                SupervisedKind.Spawn -> Unit
                SupervisedKind.Unknown -> Unit
            }
        }
        val verdicts = listOf(JvmVerdict.Allow, JvmVerdict.InjectFd, JvmVerdict.Deny(NativeConstants.EPERM))
        for (verdict in verdicts) {
            when (verdict) {
                is JvmVerdict.Allow -> Unit
                is JvmVerdict.InjectFd -> Unit
                is JvmVerdict.Deny -> Unit
            }
            for (kind in kinds) {
                val route = SupervisorNotificationMachine.evaluateJvm(kind, verdict)
                when (route) {
                    is SupervisorRoute.Continue -> Unit
                    is SupervisorRoute.SecureExec -> Unit
                    is SupervisorRoute.InjectFd -> Unit
                    is SupervisorRoute.Abort -> Unit
                    is SupervisorRoute.AskJvm -> Unit
                }
            }
        }
    }
}
