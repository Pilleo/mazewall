package io.mazewall.ffi.internal

import io.mazewall.enforcer.api.ReviewedOperationArtifact
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.enforcer.api.execution.PreparedOperationResources
import java.nio.file.Path
import java.util.Optional

internal fun captureWithMissingAgent(
    runtime: ManagedRuntime,
    reviewed: ReviewedOperationArtifact,
    resources: PreparedOperationResources,
    omitCorridor: Boolean = false,
    scopingPolicy: io.mazewall.enforcer.api.StacktraceScopingPolicy = io.mazewall.enforcer.api.DefaultStacktraceScopingPolicy,
) = run {
    val entry = resources.implementationClass.getMethod("call")
    val boundary = Class.forName("sun.nio.fs.UnixNativeDispatcher").getDeclaredMethod("open0", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
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
        listOf(2),
        Optional.empty(),
    )
    prepareOwnedStackScoping(
        runtime,
        reviewed,
        DomainLimits(1, 1),
        resources,
        Path.of("/no-such-live-agent.so"),
        identity,
        listOf(entry.declaringClass, boundary.declaringClass),
        listOf(entry, boundary),
        if (omitCorridor) emptyList() else listOf(listOf(boundary, entry)),
        scopingPolicy,
    )
}

internal class RecordingCapture(
    private val completeCapture: Boolean = false,
    private val wrongOwner: Boolean = false,
    private val wrongCapacity: Boolean = false,
    private val wrongEnrollment: Boolean = false,
    private val wrongMembership: Boolean = false,
    private val wrongSealed: Boolean = false,
    private val extraClass: Boolean = false,
    private val missingMethod: Boolean = false,
    private val wrongSealedProcess: Boolean = false,
    private val wrongSealedCapacity: Boolean = false,
) {
    var beforeEnroll: () -> Unit = {}
    var beforeSeal: () -> Unit = {}
    var beforeInvalidate: () -> Unit = {}
    val reserved = java.util.concurrent.atomic
        .AtomicReference<io.mazewall.platform.native.InstallationOwner>()
    val retireCalls = java.util.concurrent.atomic
        .AtomicInteger()
    val invalidateFailure = java.util.concurrent.atomic
        .AtomicReference<Throwable?>()
    val pendingRetire = java.util.concurrent.atomic
        .AtomicInteger()
    val pendingInvalidate = java.util.concurrent.atomic
        .AtomicInteger()
    val pendingInvalidateOutcome = io.mazewall.platform.native.LiveCompletion
        .Pending(io.mazewall.platform.native.LivePendingReason.SlotContended)
    val pendingRetireOutcome = io.mazewall.platform.native.LiveCompletion
        .Pending(io.mazewall.platform.native.LivePendingReason.NotPhysicallyRetired)
    val refusedOutcome = io.mazewall.platform.native.LiveCompletion.Refused(
        io.mazewall.platform.native
            .LiveFailure(io.mazewall.platform.native.LiveFailureStatus.Stale, io.mazewall.platform.native.LiveFailureReason.IdentityMismatch),
    )
    val refuseInvalidate = java.util.concurrent.atomic
        .AtomicBoolean()
    val retiredOwner = java.util.concurrent.atomic
        .AtomicReference<io.mazewall.platform.native.InstallationOwner>()
    private val otherCapability = object : io.mazewall.platform.native.NativeStackContexts by io.mazewall.platform.native.UnsupportedNativeStackContexts {
        override fun invalidate(owner: io.mazewall.platform.native.InstallationOwner): io.mazewall.platform.native.LiveCompletion {
            beforeInvalidate()
            retiredOwner.set(owner)
            retireCalls.incrementAndGet()
            return io.mazewall.platform.native.LiveCompletion.Complete
        }

        override fun retireInstallation(owner: io.mazewall.platform.native.InstallationOwner): io.mazewall.platform.native.LiveCompletion {
            retiredOwner.set(owner)
            retireCalls.incrementAndGet()
            return io.mazewall.platform.native.LiveCompletion.Complete
        }
    }
    val contexts = object : io.mazewall.platform.native.NativeStackContexts by io.mazewall.platform.native.UnsupportedNativeStackContexts {
        override fun reserve(capacity: io.mazewall.platform.native.WorkerCapacity): io.mazewall.platform.native.LiveSetup<io.mazewall.platform.native.InstallationOwner> {
            val owner = io.mazewall.platform.native.InstallationOwner(
                if (wrongOwner) otherCapability else this,
                io.mazewall.platform.native
                    .ProcessIdentity(
                        io.mazewall.platform.lifecycle
                        .Pid(41),
                            io.mazewall.platform.native
                            .ProcessCookie(1uL),
                                io.mazewall.platform.native
                                .ProcessStartTicks(1uL),
                    ),
                io.mazewall.platform.native
                    .InstallationId(1),
                if (wrongCapacity) {
                    io.mazewall.platform.native
                    .WorkerCapacity(2)
                } else {
                    capacity
                },
            )
            reserved.set(owner)
            return io.mazewall.platform.native.LiveSetup
                .Ready(owner)
        }

        override fun enroll(
            owner: io.mazewall.platform.native.InstallationOwner,
            owners: List<Class<*>>,
            methods: List<java.lang.reflect.Executable>,
        ): io.mazewall.platform.native.LiveSetup<io.mazewall.platform.native.Enrollment> {
            beforeEnroll()
            if (!completeCapture) {
                return io.mazewall.platform.native.UnsupportedNativeStackContexts
                .enroll(owner, owners, methods)
            }
            val classes = owners.mapIndexed { index, type ->
                io.mazewall.platform.native
                .EnrolledClass(
                    io.mazewall.platform.native
                    .ClassToken(index.toLong() + 1),
                        type,
                )
            }
            val enrolledMethods = methods.mapIndexed { index, method ->
                io.mazewall.platform.native
                    .EnrolledMethod(
                        io.mazewall.platform.native
                        .MethodToken(index.toLong() + 1),
                            method,
                        method.name,
                        java.lang.invoke.MethodType
                            .methodType(
                            if (method is java.lang.reflect.Method) method.returnType else Void.TYPE,
                            method.parameterTypes.toList(),
                        ).toMethodDescriptorString(),
                        method.modifiers,
                    )
            }
            val enrolledOwner = if (wrongEnrollment) {
                io.mazewall.platform.native
                .InstallationOwner(owner.capability, owner.process, owner.installation, owner.capacity)
            } else {
                owner
            }
            return io.mazewall.platform.native.LiveSetup
                .Ready(
                    io.mazewall.platform.native
                    .Enrollment(
                        enrolledOwner,
                        io.mazewall.platform.native
                        .CodeGeneration(1),
                            if (wrongMembership) {
                                classes.take(1)
                            } else if (extraClass) {
                                classes +
                                io.mazewall.platform.native
                                    .EnrolledClass(
                                        io.mazewall.platform.native
                                        .ClassToken(999),
                                            String::class.java,
                                    )
                            } else {
                                classes
                            },
                        if (wrongMembership) {
                            enrolledMethods.filter { it.identity.declaringClass === classes.first().identity }
                        } else if (missingMethod) {
                            enrolledMethods.take(1)
                        } else {
                            enrolledMethods
                        },
                    ),
                )
        }

        override fun seal(
            owner: io.mazewall.platform.native.InstallationOwner,
            enrollment: io.mazewall.platform.native.Enrollment,
        ): io.mazewall.platform.native.LiveSetup<io.mazewall.platform.native.InstallationOwner> {
            beforeSeal()
            return if (completeCapture) {
                val sealed = if (wrongSealed || wrongSealedProcess || wrongSealedCapacity) {
                    val constructor = io.mazewall.platform.native.InstallationOwner::class.java
                        .getDeclaredConstructor(
                        io.mazewall.platform.native.NativeStackContexts::class.java,
                        io.mazewall.platform.native.ProcessIdentity::class.java,
                        Long::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        io.mazewall.platform.native.InstallationState::class.java,
                    ).apply { isAccessible = true }
                    constructor.newInstance(
                        owner.capability,
                        if (wrongSealedProcess) {
                            io.mazewall.platform.native.ProcessIdentity(
                                io.mazewall.platform.lifecycle
                                    .Pid(42),
                                io.mazewall.platform.native
                                    .ProcessCookie(1uL),
                                io.mazewall.platform.native
                                    .ProcessStartTicks(1uL),
                            )
                        } else {
                            owner.process
                        },
                        if (wrongSealed) 2L else owner.installation.value,
                        if (wrongSealedCapacity) 2 else owner.capacity.value,
                        io.mazewall.platform.native.InstallationState
                        .Sealed(enrollment),
                    )
                } else {
                    owner.sealed(enrollment)
                }
                io.mazewall.platform.native.LiveSetup
                    .Ready(sealed)
            } else {
                io.mazewall.platform.native.UnsupportedNativeStackContexts
                    .seal(owner, enrollment)
            }
        }

        override fun invalidate(owner: io.mazewall.platform.native.InstallationOwner): io.mazewall.platform.native.LiveCompletion {
            beforeInvalidate()
            retiredOwner.set(owner)
            retireCalls.incrementAndGet()
            invalidateFailure.getAndSet(null)?.let { throw it }
            if (refuseInvalidate.get()) return refusedOutcome
            if (pendingInvalidate.getAndUpdate { value -> if (value > 0) value - 1 else 0 } > 0) return pendingInvalidateOutcome
            return io.mazewall.platform.native.LiveCompletion.Complete
        }

        override fun retireInstallation(owner: io.mazewall.platform.native.InstallationOwner): io.mazewall.platform.native.LiveCompletion {
            retiredOwner.set(owner)
            retireCalls.incrementAndGet()
            return if (pendingRetire.getAndUpdate { value -> if (value > 0) value - 1 else 0 } > 0) {
                pendingRetireOutcome
            } else {
                io.mazewall.platform.native.LiveCompletion.Complete
            }
        }
    }
}

internal fun captureSynthetic(
    runtime: ManagedRuntime,
    reviewed: ReviewedOperationArtifact,
    resources: PreparedOperationResources,
    recording: RecordingCapture,
    beforePublish: (Any) -> Unit = {},
    afterReserve: (io.mazewall.platform.native.InstallationOwner) -> Unit,
): Any {
    val entry = resources.implementationClass.getMethod("call")
    val boundary = Class.forName("sun.nio.fs.UnixNativeDispatcher").getDeclaredMethod("open0", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
    val identity = LiveStackBootstrap.LiveDeploymentIdentity(
        LiveStackBootstrap.Profile.CLOSED_V1,
        LiveStackBootstrap.Variant.ONLY,
        "not-qualified",
        emptyList(),
        emptyList(),
        emptyList(),
        "helper",
        emptyList(),
        emptyList(),
        listOf(2),
        Optional.empty(),
    )
    val inputsClass = Class.forName("io.mazewall.ffi.internal.CaptureInputs")
    val constructor = inputsClass.declaredConstructors.single().apply { isAccessible = true }
    val inputs = constructor.newInstance(
        identity,
        reviewed,
        DomainLimits(1, 1),
        resources,
        listOf(entry.declaringClass, boundary.declaringClass),
        listOf(entry, boundary),
        listOf(listOf(boundary, entry)),
        emptyMap<Any, Any>(),
    )
    val preparation = runtime.admitScopingPreparation()
    val companion = OwnedStackScopingBootstrap.Companion
    val method = companion.javaClass
        .getDeclaredMethod(
        "captureAdmitted",
        ManagedRuntime::class.java,
        io.mazewall.enforcer.internal.execution.ScopingPreparation::class.java,
        inputsClass,
        io.mazewall.platform.native.NativeStackContexts::class.java,
        kotlin.jvm.functions.Function1::class.java,
        kotlin.jvm.functions.Function1::class.java,
    ).apply { isAccessible = true }
    return try {
        method.invoke(companion, runtime, preparation, inputs, recording.contexts, afterReserve, beforePublish)
    } catch (failure: java.lang.reflect.InvocationTargetException) {
        throw failure.targetException
    }
}

internal fun assertActualCaptureFreezesInputs(
    reviewed: ReviewedOperationArtifact,
    resources: PreparedOperationResources,
) {
    val entry = resources.implementationClass.getMethod("call")
    val boundary = Class.forName("sun.nio.fs.UnixNativeDispatcher").getDeclaredMethod("open0", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
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
        listOf(2),
        Optional.empty(),
    )
    val owners = mutableListOf(entry.declaringClass, boundary.declaringClass)
    val methods = mutableListOf<java.lang.reflect.Executable>(boundary, entry)
    val corridor = mutableListOf<java.lang.reflect.Executable>(boundary, entry)
    val corridors = mutableListOf<List<java.lang.reflect.Executable>>(corridor)
    val handlerMap = mutableMapOf<io.mazewall.platform.syscall.Syscall, io.mazewall.enforcer.api.ScopingHandler>(
        io.mazewall.platform.syscall.Syscall.OPEN to { _, _, _ -> true },
    )
    val calls = java.util.concurrent.atomic
        .AtomicInteger()
    val thread = Thread.currentThread()
    val policy = object : io.mazewall.enforcer.api.StacktraceScopingPolicy {
        override val handlers: Map<io.mazewall.platform.syscall.Syscall, io.mazewall.enforcer.api.ScopingHandler>
            get() {
                kotlin.test.assertSame(thread, Thread.currentThread())
                calls.incrementAndGet()
                return handlerMap
            }
    }
    val companion = OwnedStackScopingBootstrap.Companion
    val method = companion.javaClass
        .getDeclaredMethod(
        "captureInputs",
        LiveStackBootstrap.LiveDeploymentIdentity::class.java,
        ReviewedOperationArtifact::class.java,
        DomainLimits::class.java,
        PreparedOperationResources::class.java,
        List::class.java,
        List::class.java,
        List::class.java,
        io.mazewall.enforcer.api.StacktraceScopingPolicy::class.java,
    ).apply { isAccessible = true }
    val captured = try {
        method.invoke(companion, identity, reviewed, DomainLimits(1, 1), resources, owners, methods, corridors, policy)
    } catch (failure: java.lang.reflect.InvocationTargetException) {
        throw failure.targetException
    }
    owners.clear()
    methods.clear()
    corridor.clear()
    corridors.clear()
    handlerMap.clear()

    fun field(name: String): Any? =
        captured.javaClass.getDeclaredField(name).let {
        it.isAccessible = true
        it.get(captured)
    }
    kotlin.test.assertSame(identity, field("identity"))
    kotlin.test.assertEquals(listOf(entry.declaringClass, boundary.declaringClass), field("owners"))
    kotlin.test.assertEquals(listOf(boundary, entry), field("methods"))
    kotlin.test.assertEquals(listOf(listOf(boundary, entry)), field("corridors"))
    kotlin.test.assertEquals(setOf(io.mazewall.platform.syscall.Syscall.OPEN), (field("handlers") as Map<*, *>).keys)
    kotlin.test.assertEquals(setOf(io.mazewall.platform.syscall.Syscall.OPEN), field("routes"))
    kotlin.test.assertEquals(1, calls.get())
}
