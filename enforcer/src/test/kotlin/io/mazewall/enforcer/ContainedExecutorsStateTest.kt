package io.mazewall.enforcer

import io.mazewall.*
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContainedExecutorsStateTest {
    @AfterEach
    fun tearDown() {
        Platform.resetToDefault()
        ContainmentStateRegistry.threadState = ContainerState()
        System.clearProperty("io.mazewall.fallback")
    }

    @Test
    fun testIsPathSubsetLogic() {
        val mockProvider = object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"

            override fun hasKernelSeccompSupport(): Boolean = true

            override fun checkSeccompSanity(): io.mazewall.LinuxNative.SyscallResult<Long, io.mazewall.LinuxNative.SyscallHandledState.Unhandled> =
                io.mazewall.LinuxNative.SyscallResult
                .Error(22, -1)
        }
        Platform.setProvider(mockProvider)

        val p1 = Policy.builder().allowFsRead("/tmp").build()
        val p2 = Policy.builder().allowFsRead("/").build()

        ContainmentStateRegistry.threadState = ContainmentStateRegistry.threadState.withLandlockPolicy(p1.definition)
        assertFailsWith<IllegalStateException> {
            ContainedExecutors.installOnCurrentThread(p2)
        }
    }

    @ParameterizedTest(name = "os={0}, seccomp={1}, fallback={2} -> reject={3}")
    @MethodSource("unsupportedPlatformFallbacks")
    fun `unsupported platform fallback is fail-closed unless bypass is explicitly selected`(
        osName: String,
        seccompSupported: Boolean,
        fallback: String,
        shouldReject: Boolean,
    ) {
        val mockProvider = object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = osName

            override fun hasKernelSeccompSupport(): Boolean = seccompSupported

            override fun checkSeccompSanity(): io.mazewall.LinuxNative.SyscallResult<Long, io.mazewall.LinuxNative.SyscallHandledState.Unhandled> =
                io.mazewall.LinuxNative.SyscallResult
                .Error(22, -1)
        }
        Platform.setProvider(mockProvider)
        System.setProperty("io.mazewall.fallback", fallback)

        val failure = runCatching {
            ContainedExecutors.installOnCurrentThread(Policy.builder().build())
        }.exceptionOrNull()

        if (shouldReject) {
            assertIs<UnsupportedOperationException>(failure)
        } else {
            assertNull(failure)
        }
    }

    @Test
    fun `unsupported-platform fallback receipt reports bypass instead of installation`() {
        Platform.setProvider(
            object : PlatformProvider by RealPlatformProvider {
                override fun getOsName(): String = "Linux"

                override fun hasKernelSeccompSupport(): Boolean = false
            },
        )
        System.setProperty("io.mazewall.fallback", "SILENT_BYPASS")

        val receipt = ContainedExecutors.installOnCurrentThread(Policy.builder().build().definition)

        assertEquals(false, receipt.installed)
        assertEquals(false, receipt.landlockApplied)
    }

    @Test
    fun `failed installation fallback receipt reports bypass instead of installation`() {
        Platform.setProvider(
            object : PlatformProvider by RealPlatformProvider {
                override fun getOsName(): String = "Linux"

                override fun hasKernelSeccompSupport(): Boolean = true

                override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(22, -1)
            },
        )
        val existingPolicy = Policy.builder().allowFsRead("/tmp").build()
        val incompatiblePolicy = Policy.builder().allowFsRead("/").build()
        ContainmentStateRegistry.threadState = ContainmentStateRegistry.threadState.withLandlockPolicy(existingPolicy.definition)
        System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")

        val receipt = ContainedExecutors.installOnCurrentThread(incompatiblePolicy.definition)

        assertEquals(false, receipt.installed)
        assertEquals(true, receipt.landlockApplied)
    }

    @Test
    fun `test thread-scoped containment disallowed on virtual threads`() {
        val mockProvider = object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"

            override fun hasKernelSeccompSupport(): Boolean = true

            override fun checkSeccompSanity(): io.mazewall.LinuxNative.SyscallResult<Long, io.mazewall.LinuxNative.SyscallHandledState.Unhandled> =
                io.mazewall.LinuxNative.SyscallResult
                .Error(22, -1)
        }
        Platform.setProvider(mockProvider)

        val policy = Policy.builder().allowFsRead("/tmp").build()

        // 1. Verify Virtual Thread is rejected
        var virtualException: Throwable? = null
        val vThread = Thread.ofVirtual().start {
            try {
                ContainedExecutors.installOnCurrentThread(policy)
            } catch (t: Throwable) {
                virtualException = t
            }
        }
        vThread.join()
        assertTrue(
            virtualException is IllegalStateException,
            "Installing thread-scoped containment on a Virtual Thread must throw IllegalStateException, got: $virtualException",
        )
    }

    @Test
    fun `repeat install receipt reports active landlock when state already has landlock policy`() {
        Platform.setProvider(
            object : PlatformProvider by RealPlatformProvider {
                override fun getOsName(): String = "Linux"

                override fun hasKernelSeccompSupport(): Boolean = true

                override fun checkSeccompSanity(): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> = LinuxNative.SyscallResult.Error(22, -1)
            },
        )
        System.setProperty("io.mazewall.fallback", "WARN_AND_BYPASS")

        val policyWithLandlock = Policy.builder().allowFsRead("/tmp").build()
        ContainmentStateRegistry.threadState = ContainmentStateRegistry.threadState.withLandlockPolicy(policyWithLandlock.definition)

        val receipt = ContainedExecutors.installOnCurrentThread(policyWithLandlock.definition)
        assertTrue(receipt.landlockApplied, "Repeat install with unchanged Landlock policy must report landlockApplied=true")

        // Policy with no Landlock on clean state must report landlockApplied=false
        ContainmentStateRegistry.threadState = io.mazewall.enforcer.state
            .ContainerState()
        val policyWithoutLandlock = Policy.builder().build()
        val receiptClean = ContainedExecutors.installOnCurrentThread(policyWithoutLandlock.definition)
        assertEquals(false, receiptClean.landlockApplied, "Policy without Landlock on clean state must report landlockApplied=false")
    }

    companion object {
        @JvmStatic
        fun unsupportedPlatformFallbacks(): Stream<Arguments> =
            Stream.of(
            Arguments.of("Linux", false, "FAIL", true),
            Arguments.of("Linux", false, "SILENT_BYPASS", false),
            Arguments.of("Linux", false, "WARN_AND_BYPASS", false),
            Arguments.of("macOS", false, "FAIL", true),
            Arguments.of("macOS", false, "SILENT_BYPASS", false),
            Arguments.of("macOS", false, "WARN_AND_BYPASS", false),
        )
    }
}
