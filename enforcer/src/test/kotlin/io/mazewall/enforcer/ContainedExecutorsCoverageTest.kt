package io.mazewall.enforcer

import io.mazewall.*
import io.mazewall.core.SandboxedPath
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContainedExecutorsCoverageTest {

    @AfterEach
    fun tearDown() {
        Platform.resetToDefault()
        ThreadStateRegistry.state = ContainerState()
        System.clearProperty("io.mazewall.fallback")
    }

    @Test
    fun testIsPathSubsetLogic() {
        val mockProvider = object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"
            override fun hasKernelSeccompSupport(): Boolean = true
            override fun checkSeccompSanity(): io.mazewall.LinuxNative.SyscallResult<Long, io.mazewall.LinuxNative.SyscallHandledState.Unhandled> =
                io.mazewall.LinuxNative.SyscallResult.Error(22, -1)
        }
        Platform.setProvider(mockProvider)

        val p1 = Policy.builder().allowFsRead("/tmp").build()
        val p2 = Policy.builder().allowFsRead("/").build()

        ThreadStateRegistry.state = ThreadStateRegistry.state.withLandlockPolicy(p1.definition)
        assertFailsWith<IllegalStateException> {
            ContainedExecutors.installOnCurrentThread(p2)
        }
    }

    @Test
    fun testHandleUnsupportedPlatformBehaviors() {
        val mockProvider = object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"
            override fun hasKernelSeccompSupport(): Boolean = false
            override fun checkSeccompSanity(): io.mazewall.LinuxNative.SyscallResult<Long, io.mazewall.LinuxNative.SyscallHandledState.Unhandled> =
                io.mazewall.LinuxNative.SyscallResult.Error(22, -1)
        }
        Platform.setProvider(mockProvider)

        System.setProperty("io.mazewall.fallback", "FAIL")
        assertFailsWith<UnsupportedOperationException> {
            ContainedExecutors.installOnCurrentThread(Policy.builder().build())
        }

        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        ContainedExecutors.installOnCurrentThread(Policy.builder().build())

        System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")
        ContainedExecutors.installOnCurrentThread(Policy.builder().build())
    }

    @Test
    fun testNonLinuxFallbackBehaviors() {
        val mockProvider = object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "macOS"
        }
        Platform.setProvider(mockProvider)

        System.setProperty("io.mazewall.fallback", "FAIL")
        assertFailsWith<UnsupportedOperationException> {
            ContainedExecutors.installOnCurrentThread(Policy.builder().build())
        }

        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
        ContainedExecutors.installOnCurrentThread(Policy.builder().build())

        System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")
        ContainedExecutors.installOnCurrentThread(Policy.builder().build())
    }

    @Test
    fun `test that ContainedExecutors and SeccompAction contain ACT_TRAP signal mask warning`() {
        var rootDir = java.io.File(".").absoluteFile
        while (rootDir.parentFile != null && !java.io.File(rootDir, "enforcer").exists()) {
            rootDir = rootDir.parentFile
        }

        val executorsFile = java.io.File(rootDir, "enforcer/src/main/kotlin/io/mazewall/enforcer/ContainedExecutors.kt")
        val actionFile = java.io.File(rootDir, "enforcer/src/main/kotlin/io/mazewall/core/SeccompAction.kt")

        assertTrue(executorsFile.exists(), "ContainedExecutors.kt should be found at ${executorsFile.absolutePath}")
        assertTrue(actionFile.exists(), "SeccompAction.kt should be found at ${actionFile.absolutePath}")

        val executorsContent = executorsFile.readText()
        val actionContent = actionFile.readText()

        assertTrue(
            executorsContent.contains("ACT_TRAP") && executorsContent.contains("sigprocmask") && executorsContent.contains("sigaltstack"),
            "ContainedExecutors.kt should document ACT_TRAP unreliability regarding signal masks"
        )
        assertTrue(
            actionContent.contains("ACT_TRAP") && actionContent.contains("sigprocmask") && actionContent.contains("sigaltstack"),
            "SeccompAction.kt should document ACT_TRAP unreliability regarding signal masks"
        )
    }

    @Test
    fun `test that allowUnsafePrctl contains TOCTOU KDoc documentation`() {
        var rootDir = java.io.File(".").absoluteFile
        while (rootDir.parentFile != null && !java.io.File(rootDir, "enforcer").exists()) {
            rootDir = rootDir.parentFile
        }

        val bpfFilterFile = java.io.File(rootDir, "enforcer/src/main/kotlin/io/mazewall/BpfFilter.kt")
        val policyFile = java.io.File(rootDir, "enforcer/src/main/kotlin/io/mazewall/Policy.kt")
        val policyBuilderFile = java.io.File(rootDir, "enforcer/src/main/kotlin/io/mazewall/PolicyBuilder.kt")
        val policyDefinitionFile = java.io.File(rootDir, "enforcer/src/main/kotlin/io/mazewall/PolicyDefinition.kt")
        val syscallInspectorFile = java.io.File(rootDir, "enforcer/src/main/kotlin/io/mazewall/seccomp/SyscallInspector.kt")

        assertTrue(bpfFilterFile.exists(), "BpfFilter.kt should be found")
        assertTrue(policyFile.exists(), "Policy.kt should be found")
        assertTrue(policyBuilderFile.exists(), "PolicyBuilder.kt should be found")
        assertTrue(policyDefinitionFile.exists(), "PolicyDefinition.kt should be found")
        assertTrue(syscallInspectorFile.exists(), "SyscallInspector.kt should be found")

        val bpfFilterContent = bpfFilterFile.readText()
        val policyContent = policyFile.readText()
        val policyBuilderContent = policyBuilderFile.readText()
        val policyDefinitionContent = policyDefinitionFile.readText()
        val syscallInspectorContent = syscallInspectorFile.readText()

        assertTrue(bpfFilterContent.contains("TOCTOU") && bpfFilterContent.contains("allowUnsafePrctl"), "BpfFilter.kt should document TOCTOU warnings for allowUnsafePrctl")
        assertTrue(policyContent.contains("TOCTOU") && policyContent.contains("allowUnsafePrctl"), "Policy.kt should document TOCTOU warnings for allowUnsafePrctl")
        assertTrue(policyBuilderContent.contains("TOCTOU") && policyBuilderContent.contains("allowUnsafePrctl"), "PolicyBuilder.kt should document TOCTOU warnings for allowUnsafePrctl")
        assertTrue(policyDefinitionContent.contains("TOCTOU") && policyDefinitionContent.contains("allowUnsafePrctl"), "PolicyDefinition.kt should document TOCTOU warnings for allowUnsafePrctl")
        assertTrue(syscallInspectorContent.contains("TOCTOU") && syscallInspectorContent.contains("UnsafePrctlInspector"), "SyscallInspector.kt should document TOCTOU warnings for UnsafePrctlInspector")
    }
}
