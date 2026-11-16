package io.mazewall.enforcer

import io.mazewall.*
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.internal.ffi.RealPlatformProvider
import io.mazewall.enforcer.internal.seccomp.BpfFilter
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.enforcer.state.ContainmentStateRegistry
import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
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

            override fun checkSeccompSanity(): io.mazewall.platform.native.LinuxNative.SyscallResult<Long, io.mazewall.platform.native.LinuxNative.SyscallHandledState.Unhandled> =
                io.mazewall.platform.native.LinuxNative.SyscallResult
                .Error(22, -1)
        }
        Platform.setProvider(mockProvider)

        val p1 = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowFsRead("/tmp").build()
        val p2 = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowFsRead("/").build()

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

            override fun checkSeccompSanity(): io.mazewall.platform.native.LinuxNative.SyscallResult<Long, io.mazewall.platform.native.LinuxNative.SyscallHandledState.Unhandled> =
                io.mazewall.platform.native.LinuxNative.SyscallResult
                .Error(22, -1)
        }
        Platform.setProvider(mockProvider)
        System.setProperty("io.mazewall.fallback", fallback)

        val failure = runCatching {
            ContainedExecutors.installOnCurrentThread(ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build())
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

        val receipt = ContainedExecutors.installOnCurrentThread(ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build().definition)

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
        val existingPolicy = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowFsRead("/tmp").build()
        val incompatiblePolicy = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowFsRead("/").build()
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

            override fun checkSeccompSanity(): io.mazewall.platform.native.LinuxNative.SyscallResult<Long, io.mazewall.platform.native.LinuxNative.SyscallHandledState.Unhandled> =
                io.mazewall.platform.native.LinuxNative.SyscallResult
                .Error(22, -1)
        }
        Platform.setProvider(mockProvider)

        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowFsRead("/tmp").build()

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

        val policyWithLandlock = WorkerPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).allowFsRead("/tmp").build()
        ContainmentStateRegistry.threadState = ContainmentStateRegistry.threadState.withLandlockPolicy(policyWithLandlock.definition)

        val receipt = ContainedExecutors.installOnCurrentThread(policyWithLandlock.definition)
        assertTrue(receipt.landlockApplied, "Repeat install with unchanged Landlock policy must report landlockApplied=true")

        // Policy with no Landlock on clean state must report landlockApplied=false
        ContainmentStateRegistry.threadState = io.mazewall.enforcer.state
            .ContainerState()
        val policyWithoutLandlock = ProcessPolicyBuilder.denyList(RuntimeProfile.NATIVE_IMAGE).build()
        val receiptClean = ContainedExecutors.installOnCurrentThread(policyWithoutLandlock.definition)
        assertEquals(false, receiptClean.landlockApplied, "Policy without Landlock on clean state must report landlockApplied=false")
    }

    @Test
    fun `legacy scoping preserves definite denied handler routes`() {
        val handler = object : StacktraceScopingPolicy {
            override val handlers: Map<Syscall, ScopingHandler> = mapOf(Syscall.CONNECT to { _, _, _ -> true })
        }
        val frozen = io.mazewall.enforcer.internal.execution.FrozenStackScoping
            .Legacy(handler)
        val denied = listOf(
            PolicyDefinition<PolicyScope.ThreadLocalOnly>(syscallActions = mapOf(Syscall.CONNECT to SeccompAction.ACT_ERRNO(NativeConstants.EACCES))),
            PolicyDefinition<PolicyScope.ThreadLocalOnly>(defaultAction = SeccompAction.ACT_ERRNO(NativeConstants.EPERM)),
        )
        for (policy in denied) {
            val actual = ContainedExecutors.augmentForScoping(policy, frozen)
            val expected = policy.syscallActions[Syscall.CONNECT] ?: policy.defaultAction
            assertEquals(expected, actual.syscallActions[Syscall.CONNECT] ?: actual.defaultAction)
            val filter = BpfFilter.build(Arch.AMD64, actual)
            val action = io.mazewall.seccomp.BpfSimulator
                .simulate(filter.instructions, Syscall.CONNECT.numberFor(Arch.AMD64), Arch.AMD64)
            assertEquals(NativeConstants.SECCOMP_RET_ERRNO or (expected as SeccompAction.ACT_ERRNO).errno, action)
        }
    }

    @Test
    fun `legacy scoping preserves denial kinds and previous non-denial behavior`() {
        val handler = object : StacktraceScopingPolicy {
            override val handlers: Map<Syscall, ScopingHandler> = mapOf(Syscall.CONNECT to { _, _, _ -> true })
        }
        val frozen = io.mazewall.enforcer.internal.execution.FrozenStackScoping
            .Legacy(handler)
        val denials = listOf(
            SeccompAction.ACT_KILL_PROCESS to NativeConstants.SECCOMP_RET_KILL_PROCESS,
            SeccompAction.ACT_KILL_THREAD to NativeConstants.SECCOMP_RET_KILL_THREAD,
            SeccompAction.ACT_TRAP to NativeConstants.SECCOMP_RET_TRAP,
            SeccompAction.ACT_ERRNO(NativeConstants.EACCES) to (NativeConstants.SECCOMP_RET_ERRNO or NativeConstants.EACCES),
        )
        for ((denial, native) in denials) {
            for (explicit in listOf(false, true)) {
            val policy = PolicyDefinition<PolicyScope.ThreadLocalOnly>(
                defaultAction = if (explicit) SeccompAction.ACT_ALLOW else denial,
                syscallActions = if (explicit) mapOf(Syscall.CONNECT to denial) else emptyMap(),
            )
            val actual = ContainedExecutors.augmentForScoping(policy, frozen)
            assertEquals(denial, actual.syscallActions[Syscall.CONNECT] ?: actual.defaultAction)
            val program = BpfFilter.build(Arch.AMD64, actual).instructions
            assertEquals(
                native,
                io.mazewall.seccomp.BpfSimulator
                .simulate(program, Syscall.CONNECT.numberFor(Arch.AMD64), Arch.AMD64),
            )
            assertEquals(policy.argumentRules, actual.argumentRules)
        }
        }
        for (action in listOf(SeccompAction.ACT_ALLOW, SeccompAction.ACT_NOTIFY)) {
            val policy = PolicyDefinition<PolicyScope.ThreadLocalOnly>(syscallActions = mapOf(Syscall.CONNECT to action))
            assertEquals(SeccompAction.ACT_NOTIFY, ContainedExecutors.augmentForScoping(policy, frozen).syscallActions[Syscall.CONNECT])
        }
        for (action in listOf(SeccompAction.ACT_ALLOW)) {
            val policy = PolicyDefinition<PolicyScope.ThreadLocalOnly>(defaultAction = action)
            assertEquals(SeccompAction.ACT_NOTIFY, ContainedExecutors.augmentForScoping(policy, frozen).syscallActions[Syscall.CONNECT])
        }
        val ordinary = PolicyDefinition<PolicyScope.ThreadLocalOnly>(defaultAction = SeccompAction.ACT_LOG)
        assertEquals(
            ordinary,
            ContainedExecutors.augmentForScoping(
                ordinary,
                io.mazewall.enforcer.internal.execution.FrozenStackScoping
            .Legacy(DefaultStacktraceScopingPolicy),
            ),
        )
    }

    @Test
    fun `legacy selected log and trace actions reject composition`() {
        val scoping = io.mazewall.enforcer.internal.execution.FrozenStackScoping.Legacy(
            object : StacktraceScopingPolicy {
                override val handlers: Map<Syscall, ScopingHandler> = mapOf(Syscall.CONNECT to { _, _, _ -> true })
            },
        )
        for (action in listOf(SeccompAction.ACT_LOG, SeccompAction.ACT_TRACE(37))) {
            for (explicit in listOf(false, true)) {
                val policy = PolicyDefinition<PolicyScope.ThreadLocalOnly>(
                    defaultAction = if (explicit) SeccompAction.ACT_ALLOW else action,
                    syscallActions = if (explicit) mapOf(Syscall.CONNECT to action) else emptyMap(),
                )
                assertFailsWith<IllegalArgumentException> { ContainedExecutors.augmentForScoping(policy, scoping) }
            }
        }
    }

    @Test
    fun `selected log refusal precedes effects for supported platform regardless of fallback`() {
        Platform.setProvider(object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"

            override fun hasKernelSeccompSupport(): Boolean = true
        })
        var reads = 0
        val scoping = object : StacktraceScopingPolicy {
            override val handlers: Map<Syscall, ScopingHandler>
                get() {
                    reads++
                    return mapOf(Syscall.CONNECT to { _, _, _ -> true })
                }
        }
        for (action in listOf(SeccompAction.ACT_LOG, SeccompAction.ACT_TRACE(37))) {
            val policy = PolicyDefinition<PolicyScope.ThreadLocalOnly>(syscallActions = mapOf(Syscall.CONNECT to action))
            for (fallback in listOf("FAIL", "WARN_AND_BYPASS", "SILENT_BYPASS")) {
                System.setProperty("io.mazewall.fallback", fallback)
                val before = ContainmentStateRegistry.threadState
                assertFailsWith<IllegalArgumentException> { ContainedExecutors.installOnCurrentThread(policy, scoping) }
                assertTrue(before === ContainmentStateRegistry.threadState)
            }
        }
        assertEquals(6, reads)
    }

    @Test
    fun `unsupported platform fallback precedes selected log composition`() {
        Platform.setProvider(object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"

            override fun hasKernelSeccompSupport(): Boolean = false
        })
        val scoping = object : StacktraceScopingPolicy {
            override val handlers: Map<Syscall, ScopingHandler>
                get() = error("unsupported platform must not inspect handlers")
        }
        for (action in listOf(SeccompAction.ACT_LOG, SeccompAction.ACT_TRACE(37))) {
            val policy = PolicyDefinition<PolicyScope.ThreadLocalOnly>(syscallActions = mapOf(Syscall.CONNECT to action))
            for (fallback in listOf("FAIL", "WARN_AND_BYPASS", "SILENT_BYPASS")) {
                ContainmentStateRegistry.threadState = ContainerState()
                System.setProperty("io.mazewall.fallback", fallback)
                if (fallback == "FAIL") {
                    assertFailsWith<UnsupportedOperationException> { ContainedExecutors.installOnCurrentThread(policy, scoping) }
                } else {
                    val receipt = ContainedExecutors.installOnCurrentThread(policy, scoping)
                    assertEquals(false, receipt.installed)
                    assertIs<UnsupportedOperationException>(receipt.failure)
                }
            }
        }
    }

    @Test
    fun `supported capture retains original handler after source map mutation`() {
        Platform.setProvider(object : PlatformProvider by RealPlatformProvider {
            override fun getOsName(): String = "Linux"

            override fun hasKernelSeccompSupport(): Boolean = true
        })
        val original: ScopingHandler = { _, _, _ -> true }
        val replacement: ScopingHandler = { _, _, _ -> false }
        val mutableHandlers = mutableMapOf(Syscall.CONNECT to original)
        var reads = 0
        val scoping = object : StacktraceScopingPolicy {
            override val handlers: Map<Syscall, ScopingHandler>
                get() {
                    reads++
                    return mutableHandlers
                }
        }
        val capture = ContainedExecutors::class.java.getDeclaredMethod(
            "captureSupportedScoping",
            PolicyDefinition::class.java,
            StacktraceScopingPolicy::class.java,
            InstallationEnvironment::class.java,
        )
        capture.isAccessible = true
        val policy = PolicyDefinition<PolicyScope.ThreadLocalOnly>()
        val captured = capture.invoke(ContainedExecutors, policy, scoping, InstallationEnvironment.capture()) as io.mazewall.enforcer.internal.supervisor.CapturedScopingPolicy
        mutableHandlers[Syscall.CONNECT] = replacement
        mutableHandlers[Syscall.OPEN] = replacement
        assertEquals(1, reads)
        assertEquals(setOf(Syscall.CONNECT), captured.dispatch.handlers.keys)
        assertTrue(captured.dispatch.handlers[Syscall.CONNECT] === original)
    }

    @Test
    fun `inherited audit spawn refusal precedes supported effects and preserves fallback`() {
        val scoping = object : StacktraceScopingPolicy {
            override val handlers: Map<Syscall, ScopingHandler> = emptyMap()
        }
        for (supported in listOf(true, false)) {
            Platform.setProvider(object : PlatformProvider by RealPlatformProvider {
                override fun getOsName(): String = "Linux"

                override fun hasKernelSeccompSupport(): Boolean = supported
            })
            for (action in listOf(SeccompAction.ACT_LOG, SeccompAction.ACT_TRACE(37))) {
                for (fallback in listOf("FAIL", "WARN_AND_BYPASS", "SILENT_BYPASS")) {
                    ContainmentStateRegistry.threadState = ContainerState()
                    System.setProperty("io.mazewall.fallback", fallback)
                    val policy = PolicyDefinition<PolicyScope.ThreadLocalOnly>(defaultAction = action, syscallActions = mapOf(Syscall.EXECVE to SeccompAction.ACT_NOTIFY))
                    val before = ContainmentStateRegistry.threadState
                    if (supported) {
                        assertFailsWith<IllegalArgumentException> { ContainedExecutors.installOnCurrentThread(policy, scoping) }
                        assertTrue(before === ContainmentStateRegistry.threadState)
                    } else if (fallback == "FAIL") {
                        assertFailsWith<UnsupportedOperationException> { ContainedExecutors.installOnCurrentThread(policy, scoping) }
                    } else {
                        val receipt = ContainedExecutors.installOnCurrentThread(policy, scoping)
                        assertEquals(false, receipt.installed)
                        assertIs<UnsupportedOperationException>(receipt.failure)
                    }
                }
            }
        }
    }

    @Test
    fun `conservative audit check rejects absent architecture routes before planner`() {
        val check = ContainedExecutors::class.java.getDeclaredMethod("validateSpawnComposition", PolicyDefinition::class.java)
        check.isAccessible = true
        val policy = PolicyDefinition<PolicyScope.ThreadLocalOnly>(
            defaultAction = SeccompAction.ACT_LOG,
            syscallActions = mapOf(Syscall.EXECVE to SeccompAction.ACT_NOTIFY, Syscall.CLONE to SeccompAction.ACT_ALLOW),
        )
        assertFailsWith<java.lang.reflect.InvocationTargetException> { check.invoke(ContainedExecutors, policy) }
        val state = ContainerState()
        val plan = io.mazewall.enforcer.engine.FilterInstallationPlanner
            .calculateNewFilter(policy, state)
        assertEquals(policy, plan.toInstall)
        val alreadyApplied = ContainerState(
            defaultAction = SeccompAction.ACT_LOG,
            syscallActions = mapOf(Syscall.EXECVE to SeccompAction.ACT_NOTIFY, Syscall.CLONE to SeccompAction.ACT_ALLOW),
            allowsMmapExec = false,
            allowsNonThreadClone = false,
            allowsUnsafePrctl = false,
        )
        val noFilter = io.mazewall.enforcer.engine.FilterInstallationPlanner
            .calculateNewFilter(policy, alreadyApplied)
        assertEquals(false, noFilter.needsNewFilter)
        val delta = io.mazewall.enforcer.engine.FilterInstallationPlanner
            .calculateNewFilter(policy, alreadyApplied.copy(syscallActions = emptyMap()))
        assertEquals(true, delta.needsNewFilter)
        assertEquals(SeccompAction.ACT_ALLOW, delta.toInstall.defaultAction)
        assertFailsWith<java.lang.reflect.InvocationTargetException> { check.invoke(ContainedExecutors, policy) }
    }

    @Test
    fun `prepared spawn policy retains checked action snapshot`() {
        val actions = mutableMapOf<Syscall, SeccompAction>(Syscall.EXECVE to SeccompAction.ACT_NOTIFY)
        val policy = PolicyDefinition<PolicyScope.ThreadLocalOnly>(syscallActions = actions)
        val captured = io.mazewall.enforcer.internal.supervisor.CapturedScopingPolicy
            .capture(DefaultStacktraceScopingPolicy)
        val method = ContainedExecutors::class.java.getDeclaredMethod(
            "prepareSpawnPolicy",
            PolicyDefinition::class.java,
            io.mazewall.enforcer.internal.supervisor.CapturedScopingPolicy::class.java,
        )
        method.isAccessible = true
        val prepared = method.invoke(ContainedExecutors, policy, captured) as PolicyDefinition<*>
        actions[Syscall.EXECVE] = SeccompAction.ACT_ALLOW
        assertEquals(SeccompAction.ACT_NOTIFY, prepared.syscallActions[Syscall.EXECVE])
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
