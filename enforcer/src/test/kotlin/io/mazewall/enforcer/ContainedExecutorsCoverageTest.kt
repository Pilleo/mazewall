package io.mazewall.enforcer

import io.mazewall.*
import io.mazewall.core.SandboxedPath
import io.mazewall.core.SeccompAction
import io.mazewall.core.Syscall
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContainedExecutorsCoverageTest {

    @Test
    fun `test isPathSubset logic`() {
        val p1 = Policy.builder().allowFsRead("/tmp").build()
        val p2 = Policy.builder().allowFsRead("/").build()

        // We need a dummy policy definition that is not null to satisfy the type
        val emptyPolicy = Policy.builder().build().definition

        ThreadStateRegistry.state = ThreadStateRegistry.state.withLandlockPolicy(p1.definition)
        try {
            assertFailsWith<IllegalStateException> {
                ContainedExecutors.installOnCurrentThread(p2)
            }
        } finally {
             ThreadStateRegistry.state = ThreadStateRegistry.state.withLandlockPolicy(emptyPolicy)
        }
    }

    @Test
    fun `test handleUnsupportedPlatform behaviors`() {
        val mockProvider = object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "macOS"
        }
        Platform.setProvider(mockProvider)
        try {
            System.setProperty("io.mazewall.fallback", "FAIL")
            assertFailsWith<UnsupportedOperationException> {
                ContainedExecutors.installOnCurrentThread(Policy.builder().build())
            }

            System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")
            ContainedExecutors.installOnCurrentThread(Policy.builder().build())

            System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")
            ContainedExecutors.installOnCurrentThread(Policy.builder().build())
        } finally {
            Platform.resetToDefault()
            System.clearProperty("io.mazewall.fallback")
        }
    }
}
