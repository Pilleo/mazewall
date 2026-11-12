package io.mazewall.ffi.internal

import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.enforcer.internal.execution.ScopingPreparation
import io.mazewall.platform.native.NativeStackContexts
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnedStackScopingBootstrapTest {
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
