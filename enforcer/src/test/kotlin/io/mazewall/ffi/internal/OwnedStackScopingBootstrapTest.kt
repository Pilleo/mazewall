package io.mazewall.ffi.internal

import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.enforcer.internal.execution.HelperSupportLifetime
import io.mazewall.enforcer.internal.execution.ReviewedHelperSupport
import io.mazewall.enforcer.internal.execution.ScopingPreparation
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.NativeStackContexts
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnedStackScopingBootstrapTest {
    private fun requestIdentity(path: Path): LiveStackBootstrap.LiveDeploymentIdentity {
        val agent = LiveStackBootstrap.ArtifactIdentity("agent", path.toString(), "0".repeat(64), "00", "request")
        val enforcer = LiveStackBootstrap.ArtifactIdentity("enforcer", "/not-opened/enforcer.jar", "0".repeat(64), "00", "request")
        return LiveStackBootstrap.LiveDeploymentIdentity(
            LiveStackBootstrap.Profile.CLOSED_V1,
            LiveStackBootstrap.Variant.ONLY,
            "request",
            listOf(agent, enforcer),
            emptyList(),
            emptyList(),
            "helper",
            emptyList(),
            emptyList(),
            listOf(2, 257),
            java.util.Optional.empty(),
        )
    }

    @Test
    fun `trusted helper support requires controlled lifetime and snapshots reviewed entries`() {
        val entry = ReviewedHelperSupport(
            Path.of("/not-opened/support.jar"),
            "a".repeat(64),
        )
        val entries = mutableListOf(entry)
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            TrustedHelperSupport.reviewed(entries, HelperSupportLifetime.Uncontrolled)
        }
        val support = TrustedHelperSupport.reviewed(
            entries,
            HelperSupportLifetime.NoWriters,
        )
        entries.clear()
        kotlin.test.assertEquals(listOf(entry), support.recipe.entries)
        kotlin.test.assertFailsWith<UnsupportedOperationException> { (support.recipe.entries as MutableList).clear() }
    }

    @Test
    fun `checked owner retains original reviewed support without starting`() {
        val path = Path.of("/not-opened/agent.so")
        val support = TrustedHelperSupport.reviewed(
            listOf(ReviewedHelperSupport(Path.of("/not-opened/support.jar"), "a".repeat(64))),
            HelperSupportLifetime.NoWriters,
        )
        val owner = newCheckedRuntimeOwner(path, requestIdentity(path), support)
        try {
            kotlin.test.assertIs<io.mazewall.enforcer.api.execution.ManagedRuntimeAcquisitionStatus.NotStarted>(owner.status())
            val protocol = owner.javaClass
                .getDeclaredField("protocol")
                .apply { isAccessible = true }
                .get(owner)
            val request = protocol.javaClass
                .getDeclaredField("checkedRequest")
                .apply { isAccessible = true }
                .get(protocol) as CheckedBootstrapRequest
            kotlin.test.assertSame(support, request.helperSupport)
        } finally {
            owner.close()
        }
    }

    @Test
    fun `checked helper admission retains the observed baseline and expected support`() {
        val pid = Pid(Math.toIntExact(ProcessHandle.current().pid()))
        val baseline = HelperSecurityObservation().observeAncestry(
            pid,
            Tid(pid.value),
            HelperSecurityObservation.Lineage.Bootstrap,
        )
        val support = TrustedHelperSupport.reviewed(
            listOf(ReviewedHelperSupport(Path.of("/not-opened/support.jar"), "a".repeat(64))),
            HelperSupportLifetime.NoWriters,
        )
        val admission = TrustedHelperAdmission.fromChecked(support, baseline)
        kotlin.test.assertSame(support, admission.support)
        kotlin.test.assertSame(baseline, admission.baseline)
    }

    @Test
    fun `support present checked request refuses captured non-real engine before gettid`() {
        val base = io.mazewall.platform.native.LinuxNative.engineIdentity as io.mazewall.platform.native.NativeEngine
        val calls = java.util.concurrent.atomic
            .AtomicInteger()
        val stub = object : io.mazewall.platform.native.NativeEngine by base {
            override val process = object : io.mazewall.platform.native.NativeProcess by base.process {
                override fun gettid(): io.mazewall.platform.lifecycle.Tid {
                    calls.incrementAndGet()
                    return base.process.gettid()
                }
            }
        }
        val snapshot = io.mazewall.platform.native.RuntimeSnapshot::class.java
            .getDeclaredConstructor(io.mazewall.platform.native.NativeEngine::class.java)
            .apply { isAccessible = true }
            .newInstance(stub)
        val path = java.nio.file.Path
            .of("/not-opened/agent.so")
        val agent = LiveStackBootstrap.ArtifactIdentity("agent", path.toString(), "0".repeat(64), "00", "request")
        val enforcer = LiveStackBootstrap.ArtifactIdentity("enforcer", "/not-opened/enforcer.jar", "0".repeat(64), "00", "request")
        val identity = LiveStackBootstrap.LiveDeploymentIdentity(
            LiveStackBootstrap.Profile.CLOSED_V1,
            LiveStackBootstrap.Variant.ONLY,
            "request",
            listOf(agent, enforcer),
            emptyList(),
            emptyList(),
            "helper",
            emptyList(),
            emptyList(),
            listOf(2, 257),
            java.util.Optional.empty(),
        )
        val support = TrustedHelperSupport.reviewed(
            listOf(
                io.mazewall.enforcer.internal.execution
                .ReviewedHelperSupport(
                    java.nio.file.Path
                    .of("/not-opened/support.jar"),
                        "0".repeat(64),
                ),
            ),
            io.mazewall.enforcer.internal.execution.HelperSupportLifetime.NoWriters,
        )
        val failure = kotlin.test.assertFailsWith<CheckedBootstrapRefusal> { CheckedBootstrapRequest(path, identity, support).check(snapshot) }
        kotlin.test.assertEquals(io.mazewall.platform.native.LiveFailureStatus.Unsupported, failure.failure.status)
        kotlin.test.assertEquals(io.mazewall.platform.native.LiveFailureReason.RuntimeMismatch, failure.failure.reason)
        kotlin.test.assertEquals(0, calls.get())
    }

    @Test
    fun `arbitrary context interpreter remains private with no default hook arguments`() {
        val companion = OwnedStackScopingBootstrap.Companion
        val inputs = Class.forName("io.mazewall.ffi.internal.CaptureInputs")
        val capture = companion.javaClass.getDeclaredMethod(
            "captureAdmitted",
            ManagedRuntime::class.java,
            ScopingPreparation::class.java,
            inputs,
            NativeStackContexts::class.java,
            Function1::class.java,
            Function1::class.java,
        )
        assertTrue(Modifier.isPrivate(capture.modifiers))
        assertFalse(
            companion.javaClass.declaredMethods.any { method ->
            !Modifier.isPrivate(method.modifiers) && method.parameterTypes.any { it == NativeStackContexts::class.java }
        },
        )
    }
}

internal fun captureSynthetic257(
    runtime: ManagedRuntime,
    reviewed: io.mazewall.enforcer.api.ReviewedOperationArtifact,
    resources: io.mazewall.enforcer.api.execution.PreparedOperationResources,
    recording: RecordingCapture,
): Pair<ScopingPreparation, io.mazewall.enforcer.api.execution.PreparedStackScoping> {
    val entry = resources.implementationClass.getMethod("call")
    val boundary = Class.forName("sun.nio.fs.UnixNativeDispatcher").getDeclaredMethod(
        "openat0",
        Int::class.javaPrimitiveType,
        Long::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
    )
    val identity = LiveStackBootstrap.LiveDeploymentIdentity(
        LiveStackBootstrap.Profile.CLOSED_V1,
        LiveStackBootstrap.Variant.ONLY,
        "not-qualified",
        emptyList(),
        emptyList(),
        emptyList(),
        "helper",
        listOf(LiveStackBootstrap.BindingTarget(1, "libnio", 0xe950), LiveStackBootstrap.BindingTarget(2, "libnio", 0xe9e0)),
        emptyList(),
        listOf(257),
        java.util.Optional.empty(),
    )
    val companion = OwnedStackScopingBootstrap.Companion
    val capture = companion.javaClass.declaredMethods
        .single { it.name == "captureInputs" }
        .apply { isAccessible = true }
    val inputs = capture.invoke(
        companion,
        identity,
        reviewed,
        io.mazewall.enforcer.api.execution
            .DomainLimits(1, 1),
        resources,
        listOf(entry.declaringClass, boundary.declaringClass),
        listOf(entry, boundary),
        listOf(listOf(boundary, entry)),
        io.mazewall.enforcer.api.DefaultStacktraceScopingPolicy,
    )
    val preparation = runtime.admitScopingPreparation()
    val interpreter = companion.javaClass.declaredMethods
        .single { it.name == "captureAdmitted" }
        .apply { isAccessible = true }
    val ready = kotlin.test.assertIs<io.mazewall.platform.native.LiveSetup.Ready<*>>(
        interpreter.invoke(companion, runtime, preparation, inputs, recording.contexts, { _: Any -> Unit }, { _: Any -> Unit }),
    )
    return preparation to kotlin.test.assertIs<io.mazewall.enforcer.api.execution.PreparedStackScoping>(ready.value)
}
