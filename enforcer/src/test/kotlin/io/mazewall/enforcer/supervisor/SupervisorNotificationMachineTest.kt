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
        fun jvmVerdictTestCases(): Stream<Pair<JvmVerdict, Int>> =
            Stream.of(
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
        fun execRewriteCases(): Stream<ExecRewriteCase> =
            Stream.of(
            ExecRewriteCase("unsupported on AARCH64", Arch.AARCH64, "/bin/true", ExecRewritePlan.UnsupportedArch),
            ExecRewriteCase("missing path on AMD64", Arch.AMD64, null, ExecRewritePlan.MissingPath),
            ExecRewriteCase("ready on AMD64 with path", Arch.AMD64, "/bin/true", ExecRewritePlan.Ready("/bin/true")),
        )
    }

    @Test
    fun `custom supervised nr routes to AskJvm on fast path`() {
        val route = SupervisorNotificationMachine.evaluateFastPath(
            SupervisorNotificationMachine.classify(999_999, arch),
            resolvedPath = null,
            rawPath = null,
        )
        assertEquals(SupervisorRoute.AskJvm, route)
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
        "Open,    Deny,     Abort",
        "Open,    Allow,    InjectFd",
        "Open,    InjectFd, InjectFd",
        "Accept,  Deny,     Abort",
        "Accept,  Allow,    InjectFd",
        "Accept,  InjectFd, InjectFd",
        "Connect, Deny,     Abort",
        "Connect, Allow,    InjectFd",
        "Connect, InjectFd, InjectFd",
        "Exec,    Deny,     Abort",
        "Exec,    Allow,    SecureExec",
        "Exec,    InjectFd, InjectFd",
        "Spawn,   Deny,     Abort",
        "Spawn,   Allow,    Continue",
        "Spawn,   InjectFd, InjectFd",
        "Unknown, Deny,     Abort",
        "Unknown, Allow,    Continue",
        "Unknown, InjectFd, InjectFd",
    )
    fun `test evaluateJvm routing table`(
        kindName: String,
        verdictType: String,
        expectedRouteType: String,
    ) {
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
        val expectedRoute = when (expectedRouteType) {
            "Continue" -> SupervisorRoute.Continue
            "SecureExec" -> SupervisorRoute.SecureExec
            "InjectFd" -> SupervisorRoute.InjectFd
            "Abort" -> SupervisorRoute.Abort(NativeConstants.EPERM, "jvm deny")
            else -> error("Unknown expected route $expectedRouteType")
        }
        assertEquals(expectedRoute, route)
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
    fun `inject target follows kind not raw nr`(
        kindName: String,
        expectedTargetName: String,
    ) {
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
}
