package io.mazewall.ffi.internal

import io.mazewall.enforcer.api.DefaultStacktraceScopingPolicy
import io.mazewall.enforcer.api.ReviewedOperationArtifact
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.enforcer.api.execution.ManagedRuntimeAcquisitionOwner
import io.mazewall.enforcer.api.execution.ManagedRuntimeAcquisitionStatus
import io.mazewall.enforcer.api.execution.PreparedOperationResources
import io.mazewall.platform.native.LiveSetup
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.nio.file.Path
import java.time.Duration
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal object OwnedStackScopingPackagedBridge {
    @JvmStatic
    fun run(args: Array<String>) {
        val mode = args.singleOrNull() ?: "valid"
        if (mode == "canonical-request-validation") {
            canonicalRequestValidation()
            return
        }
        if (
            mode == "protected-owner" ||
                mode == "protected-checked" ||
                mode == "protected-no-setup" ||
                mode == "protected-measure" ||
                mode == "protected-absent" ||
                mode.startsWith("protected-reject-")
        ) {
            protectedOwner(mode)
            return
        }
        if (mode == "t4" || mode.startsWith("t4-reject-")) {
            checkedFixture(mode)
            return
        }
        if (mode == "protected-producer" || mode == "protected-producer-measure" || mode.startsWith("protected-canonical")) {
            protectedProducer(mode == "protected-producer-measure" || mode == "protected-canonical-measure", mode.startsWith("protected-canonical"), mode)
            return
        }
        if (mode == "producer") {
            producerRefusal()
            return
        }
        val managed = listOf("helper", "enforcer", "kotlin_stdlib", "serialization_core", "serialization_json", "jetbrains_annotations", "managed_fixture")
        val native = listOf("agent", "helper", "java", "release", "modules", "libjvm", "libjava", "libnio", "libc", "loader")
        val actualClasspath = System.getProperty("java.class.path").split(java.io.File.pathSeparator)
        require(actualClasspath.size >= managed.size)
        val classpath = actualClasspath.take(managed.size)
        val paths = managed.zip(classpath).toMap()
        var roles = (native + managed).distinct()
        when (mode) {
            "missing" -> roles = roles - "enforcer"
            "extra" -> roles = roles + "extra"
            "duplicate" -> roles = roles + "helper"
        }
        val artifacts = roles.map { role ->
            val path = paths[role]
            val hash =
                path?.let { file ->
                    java.util.HexFormat
                    .of()
                    .formatHex(
                        java.security.MessageDigest
                        .getInstance("SHA-256")
                        .digest(
                            java.nio.file.Files
                            .readAllBytes(
                                java.nio.file.Path
                                .of(file),
                            ),
                        ),
                    )
                }
                    ?: "0".repeat(64)
            val source = when {
                mode == "repo-source" && role == "enforcer" -> "wrong-source"
                mode == "external-source" && role == "kotlin_stdlib" -> ""
                else -> "test"
            }
            LiveStackBootstrap.ArtifactIdentity(role, path ?: "/not-inspected/$role", if (mode == "bad-digest" && role == "enforcer") "0".repeat(64) else hash, "sha256:$hash", source)
        }
        val declaredClasspath = if (mode == "one-sided") classpath.reversed() else classpath
        val identity = LiveStackBootstrap.LiveDeploymentIdentity(
            LiveStackBootstrap.Profile.CLOSED_V1,
            LiveStackBootstrap.Variant.ONLY,
            "test",
            artifacts,
            emptyList(),
            declaredClasspath,
            "helper",
            emptyList(),
            emptyList(),
            listOf(2),
            Optional.empty(),
        )
        val type = LiveStackBootstrap::class.java
        val method = if (mode == "production") {
            type.getDeclaredMethod("deploymentInputs", LiveStackBootstrap.LiveDeploymentIdentity::class.java, Int::class.javaPrimitiveType)
        } else {
            type.getDeclaredMethod("checkManagedLayout", LiveStackBootstrap.LiveDeploymentIdentity::class.java, Map::class.java, List::class.java)
        }.apply { isAccessible = true }
        try {
            if (mode == "production") method.invoke(null, identity, 0) else method.invoke(null, identity, artifacts.associateBy { it.role() }, classpath)
            check(mode == "valid") { "expected a managed component refusal" }
            println("MANAGED_COMPONENT_VALID")
        } catch (failure: java.lang.reflect.InvocationTargetException) {
            if (mode == "valid" || failure.cause !is UnsupportedOperationException) throw failure
            println("MANAGED_REJECTED:" + failure.cause?.message)
        }
    }

    private fun canonicalRequestValidation() {
        val path = java.nio.file.Path
            .of("/not-opened/agent.so")
        val agent = LiveStackBootstrap.ArtifactIdentity("agent", path.toString(), "0".repeat(64), "00", "request")
        val enforcer = LiveStackBootstrap.ArtifactIdentity("enforcer", "/not-opened/enforcer.jar", "0".repeat(64), "00", "request")

        fun identity(
            artifacts: List<LiveStackBootstrap.ArtifactIdentity>,
            routes: List<Int>,
            variant: LiveStackBootstrap.Variant,
        ) = LiveStackBootstrap.LiveDeploymentIdentity(
                LiveStackBootstrap.Profile.CLOSED_V1,
            variant,
            "request",
            artifacts,
            emptyList(),
            emptyList(),
                "helper",
            emptyList(),
            emptyList(),
            routes,
            Optional.empty(),
            )
        val valid = identity(listOf(agent, enforcer), listOf(2, 257), LiveStackBootstrap.Variant.ONLY)
        val equivalentPath = java.nio.file.Path
            .of(path.toString())
        val owner = newCheckedRuntimeOwner(equivalentPath, valid)
        check(owner.status() === io.mazewall.enforcer.api.execution.ManagedRuntimeAcquisitionStatus.NotStarted)
        check(owner.retire(java.time.Duration.ofSeconds(1)) is io.mazewall.enforcer.api.execution.ManagedRuntimeAcquisitionStatus.Complete)
        val invalid = listOf(
            identity(listOf(agent), listOf(2, 257), LiveStackBootstrap.Variant.ONLY),
            identity(listOf(agent, agent, enforcer), listOf(2, 257), LiveStackBootstrap.Variant.ONLY),
            identity(listOf(agent, enforcer, enforcer), listOf(2, 257), LiveStackBootstrap.Variant.ONLY),
            identity(listOf(agent, enforcer), listOf(257, 2), LiveStackBootstrap.Variant.ONLY),
            identity(listOf(agent, enforcer), listOf(2, 257), LiveStackBootstrap.Variant.SHARED),
        )
        for (candidate in invalid) check(runCatching { newCheckedRuntimeOwner(path, candidate) }.exceptionOrNull() is IllegalArgumentException)
        check(runCatching { newCheckedRuntimeOwner(path.resolveSibling("different.so"), valid) }.exceptionOrNull() is IllegalArgumentException)
    }

    private fun protectedOwner(mode: String) {
        val argument = java.lang.management.ManagementFactory
            .getRuntimeMXBean()
            .inputArguments
            .single { it.startsWith("-agentpath:") && it.endsWith("=live-stack=only") }
        val agent = java.nio.file.Path
            .of(argument.removePrefix("-agentpath:").removeSuffix("=live-stack=only"))
        check(agent.isAbsolute && agent.toRealPath() == agent)
        val classpath = System.getProperty("java.class.path").split(java.io.File.pathSeparator)
        check(classpath.size == 7)
        val requestedAgent = if (mode == "protected-reject-agent") agent.resolveSibling("not-the-agent.so") else agent
        val artifacts = mutableListOf(
            LiveStackBootstrap.ArtifactIdentity("agent", requestedAgent.toString(), "0".repeat(64), "00", "test-request"),
        )
        if (mode != "protected-reject-legacy") {
            artifacts.add(LiveStackBootstrap.ArtifactIdentity("enforcer", classpath[1], "0".repeat(64), "sha256:" + "0".repeat(64), "test-request"))
        }
        val routes = when (mode) {
            "protected-reject-routes" -> listOf(2)
            "protected-reject-order" -> listOf(257, 2)
            else -> listOf(2, 257)
        }
        val identity = LiveStackBootstrap.LiveDeploymentIdentity(
            LiveStackBootstrap.Profile.CLOSED_V1,
            LiveStackBootstrap.Variant.ONLY,
            "test-request",
            artifacts,
            emptyList(),
            emptyList(),
            "helper",
            emptyList(),
            emptyList(),
            routes,
            Optional.empty(),
        )
        if (mode != "protected-no-setup") prepareOpenatFixture()
        if (mode == "protected-measure") ProductionDeploymentVerifier.observe(identity)
        println("PROTECTED_OWNER_READY")
        check(System.`in`.read() == 71) { "missing protected parent handoff" }
        if (mode == "protected-measure") return
        val contexts = if (mode == "protected-checked") {
            val captured = io.mazewall.platform.native.LinuxNative
                .snapshot()
            val typed = RealNativeStackContexts.bootstrapChecked(agent, identity, captured)
            check(typed is RealNativeStackContexts.BootstrapResult.Checked) { "Actual checked bootstrap refused: $typed" }
            val original = typed.contextsFor(identity, captured)
            check(typed.contextsFor(identity, captured) === original)
            val equalIdentity = LiveStackBootstrap.LiveDeploymentIdentity(
                identity.profile(),
                identity.variant(),
                identity.sourceEvidenceId(),
                identity.artifacts(),
                identity.effectiveVmArguments(),
                identity.orderedClasspath(),
                identity.helperArtifactId(),
                identity.bindingTargets(),
                identity.dependencyTargets(),
                identity.routeSubset(),
                identity.retransformFixture(),
            )
            check(equalIdentity == identity && equalIdentity !== identity)
            val otherSnapshot = io.mazewall.platform.native.LinuxNative
                .snapshot()
            check(otherSnapshot !== captured && otherSnapshot.engineIdentity === captured.engineIdentity)
            check(typed.revalidate(identity, captured) === typed)
            val wrongIdentity = typed.revalidate(equalIdentity, captured) as RealNativeStackContexts.BootstrapResult.Refused
            check(wrongIdentity.failure.status == io.mazewall.platform.native.LiveFailureStatus.Unsupported)
            check(wrongIdentity.failure.reason == io.mazewall.platform.native.LiveFailureReason.RuntimeMismatch)
            val wrongSnapshot = typed.revalidate(identity, otherSnapshot) as RealNativeStackContexts.BootstrapResult.Refused
            check(wrongSnapshot.failure.status == io.mazewall.platform.native.LiveFailureStatus.Unsupported)
            check(wrongSnapshot.failure.reason == io.mazewall.platform.native.LiveFailureReason.RuntimeMismatch)
            check(runCatching { typed.contextsFor(equalIdentity, captured) }.exceptionOrNull() is IllegalArgumentException)
            check(runCatching { typed.contextsFor(identity, otherSnapshot) }.exceptionOrNull() is IllegalArgumentException)
            println("PROTECTED_CHECKED_ORIGINAL_REFERENCES_ONLY")
            original
        } else {
            RealNativeStackContexts.bootstrap(agent, identity)
        }
        val result = contexts.reserve(
            io.mazewall.platform.native
                .WorkerCapacity(1),
        )
        if (mode == "protected-no-setup") {
            when (result) {
                is io.mazewall.platform.native.LiveSetup.Ready -> {
                    check(contexts.retireInstallation(result.value) is io.mazewall.platform.native.LiveCompletion.Complete)
                    println("PROTECTED_NO_SETUP_READY_AND_RETIRED")
                }
                is io.mazewall.platform.native.LiveSetup.Refused -> println("PROTECTED_NO_SETUP_REFUSED:${result.failure}")
            }
            return
        }
        if (mode.startsWith("protected-reject-") || mode == "protected-absent") {
            check(result is io.mazewall.platform.native.LiveSetup.Refused)
            val expected = when (mode) {
                "protected-reject-agent" -> "requested agent path"
                "protected-reject-legacy", "protected-absent" -> "identity kind does not match startup binding"
                else -> "requested routes"
            }
            check(result.failure.status == io.mazewall.platform.native.LiveFailureStatus.Unsupported)
            check(result.failure.reason == io.mazewall.platform.native.LiveFailureReason.RuntimeMismatch)
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            val causes = mutableListOf<Throwable>()
            var cause = result.failure.cause
            while (cause != null && causes.size < 8 && seen.add(cause)) {
                causes.add(cause)
                cause = cause.cause
            }
            check(cause == null && causes.any { it.message?.contains(expected) == true }) { "unexpected protected refusal: $result" }
            println("PROTECTED_REQUEST_REFUSED:$mode")
            return
        }
        val owner = requireReady("protected reserve", result)
        check(contexts.retireInstallation(owner) is io.mazewall.platform.native.LiveCompletion.Complete)
        println("PROTECTED_ORIGINAL_OWNER_RETIRED")
    }

    private fun <T> requireReady(
        stage: String,
        result: io.mazewall.platform.native.LiveSetup<T>,
    ): T =
        when (result) {
            is io.mazewall.platform.native.LiveSetup.Ready -> result.value
            is io.mazewall.platform.native.LiveSetup.Refused ->
                throw IllegalStateException("fixture $stage refused: ${result.failure}", result.failure.cause)
        }

    private fun prepareOpenatFixture() {
        val fixture = java.nio.file.Files
            .createTempFile("managed-t4-openat-", ".txt")
        var primary: Throwable? = null
        try {
            java.nio.file.Files
                .writeString(fixture, "actual JNI enrollment fixture")
            check(
                java.nio.file.Files
                    .readString(fixture) == "actual JNI enrollment fixture",
            )
            val dispatcher = Class.forName("sun.nio.fs.UnixNativeDispatcher")
            val openat = dispatcher.getDeclaredMethod("openat", Int::class.javaPrimitiveType, ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            val close = dispatcher.getDeclaredMethod("close", Int::class.javaPrimitiveType)
            openat.isAccessible = true
            close.isAccessible = true
            val fd = openat.invoke(null, -100, fixture.toAbsolutePath().toString().toByteArray(java.nio.charset.StandardCharsets.UTF_8), 0, 0) as Int
            check(fd >= 0)
            try {
                Unit
            } finally {
                close.invoke(null, fd)
            }
            println("MANAGED_T4_OPENAT_PREPARED")
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try {
                java.nio.file.Files
                    .delete(fixture)
            } catch (cleanup: Throwable) {
                val failure = primary
                if (failure == null) throw cleanup
                if (failure !== cleanup) {
                    try {
                        failure.addSuppressed(cleanup)
                    } catch (_: Throwable) {
                        // retain original
                    }
                }
            }
        }
    }

    private fun checkedFixture(mode: String) {
        println("MANAGED_T4_CHILD_READY")
        check(System.`in`.read() == 71) { "missing parent handoff" }
        val expectedPath = java.nio.file.Path
            .of(requireNotNull(System.getProperty("mazewall.fixture.expected")))
            .toRealPath()
        val artifacts = java.nio.file.Files.readAllLines(expectedPath).map { line ->
            val parts = line.split('\t')
            require(parts.size == 5)
            LiveStackBootstrap.ArtifactIdentity(parts[0], parts[1], parts[2], parts[3], parts[4])
        }
        val byRole = artifacts.associateBy { it.role() }
        val managedRoles = listOf("helper", "enforcer", "kotlin_stdlib", "serialization_core", "serialization_json", "jetbrains_annotations", "managed_fixture")
        val classpath = managedRoles.map { checkNotNull(byRole[it]).canonicalPath() }
        val argumentsPath = java.nio.file.Path
            .of(requireNotNull(System.getProperty("mazewall.fixture.vmArguments")))
            .toRealPath()
        val frozenArguments = java.nio.file.Files
            .readAllLines(argumentsPath)
        val identity = LiveStackBootstrap.LiveDeploymentIdentity(
            LiveStackBootstrap.Profile.CLOSED_V1,
            LiveStackBootstrap.Variant.ONLY,
            checkNotNull(byRole["helper"]).sourceBuildEvidenceId(),
            artifacts,
            frozenArguments,
            classpath,
            "helper",
            listOf(LiveStackBootstrap.BindingTarget(1, "libnio", if (mode == "t4-reject-binding") 0xe951 else 0xe950), LiveStackBootstrap.BindingTarget(2, "libnio", 0xe9e0)),
            listOf(
                LiveStackBootstrap.DependencyTarget("libnio", 0x15ee0, "libc", if (mode == "t4-reject-dependency") 0x127991 else 0x127990),
                LiveStackBootstrap.DependencyTarget("libnio", 0x16100, "libc", 0x127a90),
                LiveStackBootstrap.DependencyTarget("libnio", 0x15c18, "libc", 0x2a980),
                LiveStackBootstrap.DependencyTarget("libnio", 0x15f40, "libjava", 0x1a0a0),
                LiveStackBootstrap.DependencyTarget("libjvm", 0x178cad0, "libjvm", 0xb03f90),
                LiveStackBootstrap.DependencyTarget("libjvm", 0x178cb08, "libjvm", 0xb01220),
                LiveStackBootstrap.DependencyTarget("libjvm", 0x178cb58, "libjvm", 0xaf7300),
                LiveStackBootstrap.DependencyTarget("libjvm", 0x178cb70, "libjvm", 0xaf6980),
                LiveStackBootstrap.DependencyTarget("libjvm", 0x178cb88, "libjvm", 0xb0f040),
                LiveStackBootstrap.DependencyTarget("libjvm", 0x178cba8, "libjvm", 0xb05e90),
            ),
            listOf(2),
            Optional.empty(),
        )
        if (mode != "t4-reject-absent-binding") prepareOpenatFixture()
        val contexts = RealNativeStackContexts.bootstrap(
            java.nio.file.Path
            .of(checkNotNull(byRole["agent"]).canonicalPath()),
                identity,
        )
        val reservation = contexts.reserve(
            io.mazewall.platform.native
            .WorkerCapacity(1),
        )
        if (mode.startsWith("t4-reject-")) {
            check(reservation is io.mazewall.platform.native.LiveSetup.Refused)
            if (mode == "t4-reject-foreign-loader") {
                check(reservation.failure.reason == io.mazewall.platform.native.LiveFailureReason.MissingBinding) {
                    "foreign loader did not refuse native registration: $reservation"
                }
                println("MANAGED_T4_REJECTED:$mode")
                return
            }
            val expected = when (mode) {
                "t4-reject-absent-binding" -> "missing actual binding"
                "t4-reject-bad-build", "t4-reject-sys-build" -> "loader build ID mismatch"
                "t4-reject-missing", "t4-reject-extra", "t4-reject-sys-missing" -> "managed role set"
                "t4-reject-duplicate" -> "duplicate fixture role"
                "t4-reject-alias" -> "malformed preparation inputs"
                "t4-reject-missing-zip" -> "fixture images refused"
                "t4-reject-swapped", "t4-reject-sys-digest" -> "artifact digest mismatch"
                "t4-reject-bad-digest" -> "managed test digest"
                "t4-reject-binding", "t4-reject-dependency" -> "unaudited binding/dependency/route"
                else -> error("unknown fixture rejection")
            }
            check(
                reservation.failure.cause
                ?.message
                ?.contains(expected) == true,
            ) {
                "fixture rejection did not reach $expected: $reservation"
            }
            println("MANAGED_T4_REJECTED:$mode")
            return
        }
        val owner = requireReady("reserve", reservation)
        val changedIdentity = LiveStackBootstrap.LiveDeploymentIdentity(
            identity.profile(),
            identity.variant(),
            identity.sourceEvidenceId() + "-foreign",
            identity.artifacts(),
            identity.effectiveVmArguments(),
            identity.orderedClasspath(),
            identity.helperArtifactId(),
            identity.bindingTargets(),
            identity.dependencyTargets(),
            identity.routeSubset(),
            identity.retransformFixture(),
        )
        val mismatchedContexts = RealNativeStackContexts.bootstrap(
            java.nio.file.Path
                .of(checkNotNull(byRole["agent"]).canonicalPath()),
            changedIdentity,
        )
        val mismatch = mismatchedContexts.reserve(
            io.mazewall.platform.native
            .WorkerCapacity(1),
        )
        check(
            mismatch is io.mazewall.platform.native.LiveSetup.Refused &&
            mismatch.failure.cause
                ?.message
                ?.contains("checked association mismatch") == true,
        ) {
            "different original identity did not refuse: $mismatch"
        }
        println("MANAGED_T4_FOREIGN_IDENTITY_REJECTED")
        val foreignContexts = RealNativeStackContexts.bootstrap(
            java.nio.file.Path
            .of(checkNotNull(byRole["agent"]).canonicalPath()),
                identity,
        )
        val foreignRetirement = foreignContexts.retireInstallation(owner)
        check(
            foreignRetirement is io.mazewall.platform.native.LiveCompletion.Refused &&
            foreignRetirement.failure.reason == io.mazewall.platform.native.LiveFailureReason.IdentityMismatch,
        ) {
            "foreign adapter accepted original owner: $foreignRetirement"
        }
        val workload = OwnedStackScopingPackagedWorkload::class.java
        val boundary = Class.forName("sun.nio.fs.UnixNativeDispatcher").getDeclaredMethod("open0", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        val enrollment = requireReady("enroll", contexts.enroll(owner, listOf(workload, boundary.declaringClass), listOf(workload.getMethod("call"), boundary)))
        val sealed = requireReady("seal", contexts.seal(owner, enrollment))
        val ready = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val workerOwner = java.util.concurrent.atomic
            .AtomicReference<io.mazewall.platform.native.WorkerOwner>()
        val workerFailure = java.util.concurrent.atomic
            .AtomicReference<Throwable>()
        val thread = Thread.ofPlatform().unstarted {
            try {
                val worker = requireReady("register", contexts.registerCurrent(sealed))
                workerOwner.set(worker)
                val lease = requireReady("begin", contexts.beginCurrent(worker))
                ready.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "worker handoff timed out" }
                check(contexts.endCurrent(lease) is io.mazewall.platform.native.LiveCompletion.Complete)
            } catch (problem: Throwable) {
                workerFailure.set(problem)
            } finally {
                ready.countDown()
            }
        }
        thread.start()
        try {
            check(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)) { "worker lease did not start" }
            workerFailure.get()?.let { throw it }
            check(thread.isAlive) { "worker exited before the live-lease observation" }
            val pending = contexts.retireInstallation(sealed)
            check(
                pending == io.mazewall.platform.native.LiveCompletion
                .Pending(io.mazewall.platform.native.LivePendingReason.CleanupPending),
            ) {
                "expected actual cleanup pending while worker lease is live: $pending"
            }
        } finally {
            release.countDown()
            thread.join(5000)
        }
        check(!thread.isAlive) { "registered platform worker did not physically exit" }
        workerFailure.get()?.let { throw it }
        check(contexts.retireWorker(checkNotNull(workerOwner.get())) is io.mazewall.platform.native.LiveCompletion.Complete)
        check(contexts.invalidate(sealed) is io.mazewall.platform.native.LiveCompletion.Complete)
        check(contexts.retireInstallation(sealed) is io.mazewall.platform.native.LiveCompletion.Complete)
        println("MANAGED_T4_ACTUAL_LEASE_RETIRED")
    }

    private fun protectedProducer(
        measureOnly: Boolean,
        checkedOwner: Boolean,
        mode: String,
    ) {
        val frontier = if (mode.startsWith("protected-canonical-") && mode != "protected-canonical-measure") mode.removePrefix("protected-canonical-") else ""
        val isolatedUnknown = frontier == "notification-fatal" || frontier == "old-abandon-after-reserve"
        var frontierHandled = false
        val argument = java.lang.management.ManagementFactory
            .getRuntimeMXBean()
            .inputArguments
            .single { it.startsWith("-agentpath:") && it.endsWith("=live-stack=only") }
        val agent = java.nio.file.Path
            .of(
                argument
                    .removePrefix("-agentpath:")
                    .removeSuffix("=live-stack=only"),
            )
        check(agent.isAbsolute && agent.toRealPath() == agent)
        val classpath = System.getProperty("java.class.path").split(java.io.File.pathSeparator)
        check(classpath.size == 7)
        val producer = Class.forName("io.mazewall.ffi.internal.OwnedStackScopingBootstrapKt", false, ClassLoader.getSystemClassLoader())
        check(
            java.nio.file.Path
                .of(
                    producer.protectionDomain
                        .codeSource
                        .location
                        .toURI(),
                ).toRealPath() == java.nio.file.Path
                .of(classpath[1])
                .toRealPath(),
        )
        val identity = LiveStackBootstrap.LiveDeploymentIdentity(
            LiveStackBootstrap.Profile.CLOSED_V1,
            LiveStackBootstrap.Variant.ONLY,
            "test-request",
            listOf(
                LiveStackBootstrap.ArtifactIdentity(
                    "agent",
                    agent.toString(),
                    "0".repeat(64),
                    "00",
                    "test-request",
                ),
                LiveStackBootstrap.ArtifactIdentity(
                    "enforcer",
                    classpath[1],
                    "0".repeat(64),
                    "sha256:" + "0".repeat(64),
                    "test-request",
                ),
            ),
            emptyList(),
            emptyList(),
            "helper",
            listOf(
                LiveStackBootstrap.BindingTarget(1, "libnio", 0xe950),
                LiveStackBootstrap.BindingTarget(2, "libnio", 0xe9e0),
            ),
            emptyList(),
            listOf(2, 257),
            Optional.empty(),
        )
        val selectedOwner = if (checkedOwner) {
            val missingEnforcer = LiveStackBootstrap.LiveDeploymentIdentity(
                identity.profile(),
                identity.variant(),
                identity.sourceEvidenceId(),
                identity.artifacts().filter { it.role() != "enforcer" },
                identity.effectiveVmArguments(),
                identity.orderedClasspath(),
                identity.helperArtifactId(),
                identity.bindingTargets(),
                identity.dependencyTargets(),
                identity.routeSubset(),
                identity.retransformFixture(),
            )
            check(runCatching { newCheckedRuntimeOwner(agent, missingEnforcer) }.exceptionOrNull() is IllegalArgumentException)
            newCheckedRuntimeOwner(agent, identity)
        } else {
            io.mazewall.enforcer.api.execution.ManagedRuntimeAcquisitionOwner
                .create()
        }
        prepareOpenatFixture()
        val profile = io.mazewall.enforcer.api.RuntimeProfile.HOTSPOT_JIT
        val artifactPath = java.nio.file.Path
            .of(
                OwnedStackScopingPackagedWorkload::class.java
                    .protectionDomain
                    .codeSource
                    .location
                    .toURI(),
            ).toRealPath()
        val policy = io.mazewall.enforcer.api.WorkerPolicyBuilder
            .denyList(profile)
            .allowFsRead(artifactPath)
            .build()
        val owner = selectedOwner
        val resources = io.mazewall.enforcer.api.execution.PreparedOperationResources
            .resolve(
                profile,
                policy,
                OwnedStackScopingPackagedWorkload::class.java,
                emptySet(),
            )
        var transferred = false
        var producerEntered = false
        var drainAttempted = false
        var settled = false
        var runtime: io.mazewall.enforcer.api.execution.ManagedRuntime? = null
        var primary: Throwable? = null
        val timeout = java.time.Duration.ofSeconds(10)
        try {
            val binding = io.mazewall.enforcer.api.OperationBinding(
                "packaged",
                io.mazewall.enforcer.api.OperationAdapter.CALLABLE,
                resources.implementationSha256,
                resources.resourceGeneration,
                mapOf(
                    "entryPoint" to "call()I",
                ),
            )
            val document = io.mazewall.enforcer.api.OperationPolicyDocument(
                binding,
                io.mazewall.enforcer.api.CanonicalPolicyArtifact
                    .worker(profile, policy),
                emptySet(),
                emptyMap(),
            )
            val reviewed = io.mazewall.enforcer.api.ReviewedOperationArtifact
                .load(
                    document.toJson(),
                    io.mazewall.enforcer.api.TrustedPolicyReview(
                        "test",
                        document.sha256(),
                        emptySet(),
                    ),
                    binding,
                )
            val actualRuntime = owner.start(timeout, timeout)
            runtime = actualRuntime
            check(owner.ownsRuntime(actualRuntime))
            try {
                ProductionDeploymentVerifier.observe(identity)
                println("PROTECTED_PRODUCER_READY_FOR_PARENT")
                check(System.`in`.read() == 71) { "missing protected parent handoff" }
                if (measureOnly) return
                val entry = OwnedStackScopingPackagedWorkload::class.java.getMethod("call")
                val dispatcher = Class.forName("sun.nio.fs.UnixNativeDispatcher")
                val open = dispatcher.getDeclaredMethod("open0", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                val openat = dispatcher.getDeclaredMethod("openat0", Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                val limits = io.mazewall.enforcer.api.execution
                    .DomainLimits(1, 1)
                producerEntered = true
                if (frontier.isNotEmpty()) {
                    val tokenBytes = System.`in`.readNBytes(33)
                    val token = tokenBytes.toString(Charsets.US_ASCII).removeSuffix("\n")
                    check(tokenBytes.size == 33 && token.matches(Regex("[0-9a-f]{32}")))
                    frontierHandled = true // Ownership stays with this bounded frontier; no outer cleanup replay.
                    exerciseCanonicalFrontier(
                        frontier,
                        token,
                        owner,
                        actualRuntime,
                        reviewed,
                        limits,
                        resources,
                        agent,
                        identity,
                        listOf(entry.declaringClass, dispatcher),
                        listOf(entry, open, openat),
                        listOf(listOf(open, entry), listOf(openat, entry)),
                    )
                    return
                }
                val wrapper = requireReady(
                    "main producer",
                    prepareOwnedStackScoping(
                        actualRuntime,
                        reviewed,
                        limits,
                        resources,
                        agent,
                        identity,
                        listOf(
                            entry.declaringClass,
                            dispatcher,
                        ),
                        listOf(
                            entry,
                            open,
                            openat,
                        ),
                        listOf(
                            listOf(open, entry),
                            listOf(openat, entry),
                        ),
                    ),
                )
                transferred = true
                val bootstrap = observedField(wrapper, "bootstrap")
                val staging = observedField(bootstrap, "staging")
                val enrollment = observedField(bootstrap, "enrollment")
                val sealed = observedField(bootstrap, "sealed")
                check(observedField(bootstrap, "runtime") === actualRuntime)
                check(observedField(bootstrap, "inputs").let { observedField(it, "resources") } === resources)
                println("PROTECTED_MAIN_PRODUCER_COUNTED_READY")
                val refusal = try {
                    actualRuntime.prepare(policy, limits, wrapper)
                    error("Live dispatcher unexpectedly accepted")
                } catch (failure: UnsupportedOperationException) {
                    check(failure.message == "Live stack scoping dispatcher is not available")
                    check(failure.stackTrace.any { it.className == "io.mazewall.enforcer.internal.execution.OwnedDomainKt" && it.methodName == "requireSupportedScopingStart" })
                    failure
                }
                drainAttempted = true
                val report = actualRuntime.drain(timeout)
                check(report is io.mazewall.enforcer.api.execution.DrainReport.Incomplete)
                check(report.unfinishedInvocations == 0 && report.liveWorkers == 0)
                check(containsOriginalFailure(report.cleanupFailure, refusal))
                val shared = observedField(actualRuntime, "shared")
                val preparation = observedField(shared, "scopingPreparation")
                val state = observedField(preparation, "state")
                check(state.javaClass.simpleName == "TransferredSettled")
                val domain = observedField(state, "domain")
                check(observedField(domain, "preparedOperation") === resources)
                val scoping = observedField(domain, "scoping")
                check(observedField(scoping, "bootstrap") === bootstrap)
                check(observedField(bootstrap, "staging") === staging && observedField(bootstrap, "enrollment") === enrollment && observedField(bootstrap, "sealed") === sealed)
                val release = observedField(domain, "liveRelease")
                check(observedField(release, "pass").toString() == "Retained")
                listOf("invalidate", "installation", "landlock", "resources").forEach { check(observedField(release, it).toString() == "Complete") }
                check(observedNullableField(release, "nativeOutcome") == null && observedNullableField(release, "failure") == null)
                settled = true
                println("PROTECTED_MAIN_PRODUCER_B6_REFUSED_ORIGINAL_RETIRED")
            } finally {
                // The retained acquisition owns this exact runtime on both success and failure.
            }
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            if (!isolatedUnknown && !frontierHandled) {
            var retirementFailure: Throwable? = null
            var resourceFailure: Throwable? = null
            var outcome: io.mazewall.enforcer.api.execution.ManagedRuntimeAcquisitionStatus? = null
            try {
                check(!drainAttempted || settled) { "Original drain outcome remains unresolved; no retirement replay" }
                outcome = owner.retire(timeout)
                println("PROTECTED_PRODUCER_ACQUISITION_RETIRE:$outcome")
            } catch (failure: Throwable) {
                retirementFailure = failure
            }
            try {
                if (!transferred) {
                    val callerOwned = if (!producerEntered) {
                        true
                    } else {
                        val state = runtime
                            ?.let {
                                observedNullableField(
                                    observedField(it, "shared"),
                                    "scopingPreparation",
                                )
                            }?.let { observedField(it, "state") }
                        outcome is io.mazewall.enforcer.api.execution.ManagedRuntimeAcquisitionStatus.Complete &&
                            (state == null || state.javaClass.simpleName == "PreparedSettled")
                    }
                    check(callerOwned) { "Resource ownership remains unresolved: $outcome" }
                    val phase = observedField(resources, "phase").toString()
                    if (phase == "AVAILABLE") resources.close() else check(phase == "CLOSED")
                }
            } catch (failure: Throwable) {
                resourceFailure = failure
            }
            val failures = listOfNotNull(primary, retirementFailure, resourceFailure)
            if (failures.any { it is InterruptedException }) Thread.currentThread().interrupt()
            val fatal = failures.firstOrNull { it is Error }
            var selected = fatal ?: failures.firstOrNull()
            for (failure in failures) {
                if (failure !== selected) {
                    try {
                        selected?.addSuppressed(failure)
                    } catch (metadata: Throwable) {
                        if (selected !is Error && metadata is Error) selected = metadata
                    }
                }
            }
            if (selected != null && selected !== primary) throw selected
            }
        }
    }

    private fun exerciseCanonicalFrontier(
        mode: String,
        token: String,
        owner: ManagedRuntimeAcquisitionOwner,
        runtime: ManagedRuntime,
        artifact: ReviewedOperationArtifact,
        limits: DomainLimits,
        resources: PreparedOperationResources,
        agent: Path,
        identity: LiveStackBootstrap.LiveDeploymentIdentity,
        owners: List<Class<*>>,
        methods: List<Executable>,
        corridors: List<List<Executable>>,
    ) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val afterReserveCalls = AtomicInteger()
        val outcome = AtomicReference<Any>()
        val failure = AtomicReference<Throwable>()
        val observedRawOwner = AtomicReference<Any>()
        val fatal = LinkageError("controlled observer failure")
        val earlier = IllegalStateException("controlled construction failure")

        fun field(
            value: Any,
            name: String,
        ): Any? =
            value.javaClass.getDeclaredField(name).let {
            it.isAccessible = true // test-only observation of existing private state
            it.get(value)
        }

        fun preparation(): Any = checkNotNull(field(checkNotNull(field(runtime, "shared")), "scopingPreparation"))

        fun pause() {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "frontier release timed out" }
        }
        val beforeClaim: () -> Unit = { if (mode == "before-claim") pause() }
        val afterClaim: () -> Unit = {
            if (mode == "after-claim") pause()
            if (mode == "reenter-abandon") {
                runtime.abandonScopingPreparation(preparation() as io.mazewall.enforcer.internal.execution.ScopingPreparation, null)
            }
        }
        val afterReserve: () -> Unit = {
            afterReserveCalls.incrementAndGet()
            observedRawOwner.set(checkNotNull(field(checkNotNull(field(runtime, "shared")), "checkedRawOwner")))
            if (mode == "after-reserve-fatal") throw fatal
            if (mode == "notification-fatal") throw earlier
            if (mode == "old-abandon-after-reserve") {
                runtime.abandonScopingPreparation(preparation() as io.mazewall.enforcer.internal.execution.ScopingPreparation, null)
            }
        }
        val beforeNotification: () -> Unit = { if (mode == "notification-fatal") throw fatal }
        val target = OwnedStackScopingBootstrap.Companion
        val method = target.javaClass.declaredMethods
            .single { it.name == "prepareChecked" }
            .apply { isAccessible = true }
        val observations = Class
            .forName("io.mazewall.ffi.internal.CheckedObservations")
            .declaredConstructors
            .single()
            .apply { isAccessible = true }
            .newInstance(beforeClaim, afterClaim, afterReserve, beforeNotification)
        val request = CheckedBootstrapRequest(agent, identity)
        val actor = Thread.ofPlatform().unstarted {
            try {
                outcome.set(
                    method.invoke(
                        target,
                        runtime,
                        artifact,
                        limits,
                        resources,
                        request,
                        owners,
                        methods,
                        corridors,
                        DefaultStacktraceScopingPolicy,
                        observations,
                    ),
                )
            } catch (problem: InvocationTargetException) {
                failure.set(checkNotNull(problem.cause))
            } catch (problem: Throwable) {
                failure.set(problem)
            }
        }
        actor.start()
        try {
            if (mode == "before-claim" || mode == "after-claim") {
                check(entered.await(10, TimeUnit.SECONDS)) { "actual original actor did not reach frontier" }
                val retirement = owner.retire(Duration.ofMillis(250))
                check(retirement is ManagedRuntimeAcquisitionStatus.Pending) { "unexpected early retirement: $retirement" }
            }
        } finally {
            release.countDown()
        }
        actor.join(10000)
        check(!actor.isAlive) { "original producer still in flight" }
        when (mode) {
            "before-claim", "reenter-abandon" -> {
                check(failure.get() == null && outcome.get() is LiveSetup.Refused)
                check(afterReserveCalls.get() == 0) // callback observation, not independent kernel count
            }
            "after-claim" -> {
                check(failure.get() == null && outcome.get() is LiveSetup.Refused)
                if (observedRawOwner.get() == null) {
                    println("CANONICAL_RESERVE_REFUSED_WITHOUT_OBSERVED_OWNER")
                    error("No actual owner observed; retirement proof is unavailable")
                }
                check(afterReserveCalls.get() == 1)
            }
            "after-reserve-fatal", "notification-fatal" -> check(failure.get() === fatal)
            "old-abandon-after-reserve" -> check(failure.get() != null)
            else -> error("unsupported frontier")
        }
        val shared = checkNotNull(field(runtime, "shared"))
        val prep = checkNotNull(field(shared, "scopingPreparation"))
        val state = checkNotNull(field(prep, "state"))
        val future = checkNotNull(field(prep, "constructionFinished")) as java.util.concurrent.CompletableFuture<*>
        val count = field(shared, "preparations") as Int
        val unknown = field(shared, "checkedUnknown") as Boolean
        val raw = field(shared, "checkedRawOwner")
        val resourcePhase = checkNotNull(field(resources, "phase")).toString()
        check(resourcePhase == "AVAILABLE") { "resource unexpectedly transferred: $resourcePhase" }
        when (mode) {
            "before-claim", "reenter-abandon" -> {
                check(state.javaClass.simpleName == "PreparedSettled" && count == 0 && future.isDone && !unknown && raw == null)
                check(owner.retire(Duration.ofSeconds(10)) is ManagedRuntimeAcquisitionStatus.Complete)
                resources.close() // actual admission never transferred this still-AVAILABLE resource
            }
            "after-claim", "after-reserve-fatal" -> {
                check(state.javaClass.simpleName == "Retiring" && count == 0 && future.isDone && !unknown && raw == null)
                val retained = checkNotNull(field(state, "retained"))
                check(field(retained, "owner") === observedRawOwner.get())
                check(field(retained, "resources") == null)
                val observation = field(retained, "observationIdentity") // legitimately null on construction failure
                val retirement = owner.retire(Duration.ofSeconds(10)) // one authorized retirement after actor return
                val settled = checkNotNull(field(prep, "state"))
                check(settled.javaClass.simpleName == "PreparedSettled")
                check(field(settled, "observationIdentity") === observation)
                if (mode == "after-claim") check(retirement is ManagedRuntimeAcquisitionStatus.Complete)
                if (mode == "after-reserve-fatal") {
                    check(retirement is ManagedRuntimeAcquisitionStatus.Unknown && containsOriginalFailure(retirement.history, fatal))
                }
                // Fatal history may keep acquisition Unknown; never reclassify that as Complete.
                resources.close() // retained.resources was null and phase was AVAILABLE
            }
            "notification-fatal" -> {
                check(state.javaClass.simpleName == "Retiring" && count == 0 && !future.isDone && unknown && raw == null)
                val retained = checkNotNull(field(state, "retained"))
                check(field(retained, "owner") === observedRawOwner.get() && field(retained, "resources") == null)
                check(field(shared, "checkedRawFailure") === earlier)
                check(field(shared, "checkedNotificationFailure") === fatal)
                println("CANONICAL_NOTIFICATION_UNKNOWN_ORIGINAL_RETAINED")
                writeCanonicalUnknownReceipt(mode, token, checkNotNull(observedRawOwner.get()), earlier, fatal)
                Runtime.getRuntime().halt(0) // Test process containment only; never runtime completion.
            }
            "old-abandon-after-reserve" -> {
                check(state.javaClass.simpleName == "Constructing" && count == 1 && !future.isDone && unknown)
                check(raw === observedRawOwner.get())
                check(field(shared, "checkedAttachmentFailure") === failure.get())
                println("CANONICAL_OLD_ABANDON_UNKNOWN_ORIGINAL_RETAINED")
                writeCanonicalUnknownReceipt(mode, token, checkNotNull(observedRawOwner.get()), checkNotNull(failure.get()), null)
                Runtime.getRuntime().halt(0) // Test process containment only; never runtime completion.
            }
        }
        println("CANONICAL_FRONTIER_OK:$mode:$token")
    }

    private fun writeCanonicalUnknownReceipt(
        mode: String,
        token: String,
        owner: Any,
        first: Throwable,
        second: Throwable?,
    ) {
        val receipt = "version=1\nmode=$mode\ntoken=$token\nowner=${System.identityHashCode(
            owner,
        )}\nfirst=${System.identityHashCode(
            first,
        )}\nfirstClass=${first.javaClass.name}\nsecond=${second?.let(System::identityHashCode) ?: 0}\nsecondClass=${second?.javaClass?.name ?: "none"}\ndisposition=UNKNOWN\n"
        val bytes = receipt.toByteArray(Charsets.US_ASCII)
        check(bytes.size <= 1024)
        java.nio.channels.FileChannel
            .open(
            Path.of("/evidence/canonical-unknown-receipt.txt"),
            java.nio.file.StandardOpenOption.CREATE_NEW,
            java.nio.file.StandardOpenOption.WRITE,
        ).use { channel ->
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) check(channel.write(buffer) > 0)
            channel.force(true)
        }
        println("CANONICAL_UNKNOWN_RECEIPT_READY:$mode:$token")
        System.out.flush()
    }

    private fun observedNullableField(
        owner: Any,
        name: String,
    ): Any? =
        owner.javaClass
            .getDeclaredField(name)
            .let { field ->
                field.isAccessible = true
                field.get(owner)
            }

    private fun observedField(
        owner: Any,
        name: String,
    ): Any =
        checkNotNull(observedNullableField(owner, name)) {
            "Missing observed $name"
        }

    private fun containsOriginalFailure(
        failure: Throwable?,
        original: Throwable,
    ): Boolean {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        val pending = java.util.ArrayDeque<Throwable>()
        if (failure != null) pending.add(failure)
        while (pending.isNotEmpty() && seen.size < 32) {
            val current = pending.removeFirst()
            if (!seen.add(current)) continue
            if (current === original) return true
            current.cause?.let(pending::addLast)
            current.suppressed.forEach(pending::addLast)
        }
        return false
    }

    private fun producerRefusal() {
        val producer = Class.forName("io.mazewall.ffi.internal.OwnedStackScopingBootstrapKt", false, ClassLoader.getSystemClassLoader())
        val producerPath = java.nio.file.Path
            .of(
                producer.protectionDomain.codeSource.location
                .toURI(),
            ).toRealPath()
        val expectedProducer = java.nio.file.Path
            .of(System.getProperty("java.class.path").split(java.io.File.pathSeparator)[1])
            .toRealPath()
        check(producerPath == expectedProducer) { "producer did not resolve from the final enforcer JAR" }
        val runtimeProfile = io.mazewall.enforcer.api.RuntimeProfile.HOTSPOT_JIT
        val artifactPath = java.nio.file.Path
            .of(
                OwnedStackScopingPackagedWorkload::class.java.protectionDomain.codeSource.location
                .toURI(),
            ).toRealPath()
        val policy = io.mazewall.enforcer.api.WorkerPolicyBuilder
            .denyList(runtimeProfile)
            .allowFsRead(artifactPath)
            .build()
        val resources = io.mazewall.enforcer.api.execution.PreparedOperationResources
            .resolve(runtimeProfile, policy, OwnedStackScopingPackagedWorkload::class.java, emptySet())
        try {
            val binding = io.mazewall.enforcer.api.OperationBinding(
                "packaged",
                io.mazewall.enforcer.api.OperationAdapter.CALLABLE,
                resources.implementationSha256,
                resources.resourceGeneration,
                mapOf("entryPoint" to "call()I"),
            )
            val document = io.mazewall.enforcer.api
                .OperationPolicyDocument(
                    binding,
                    io.mazewall.enforcer.api.CanonicalPolicyArtifact
                    .worker(runtimeProfile, policy),
                        emptySet(),
                    emptyMap(),
                )
            val reviewed = io.mazewall.enforcer.api.ReviewedOperationArtifact
                .load(
                    document.toJson(),
                    io.mazewall.enforcer.api
                    .TrustedPolicyReview("test", document.sha256(), emptySet()),
                        binding,
                )
            val timeout = java.time.Duration.ofSeconds(10)
            val runtime = io.mazewall.enforcer.api.execution.ManagedRuntime
                .open(runtimeProfile, io.mazewall.enforcer.api.Policy.NO_EXEC_HOTSPOT, timeout, timeout)
            try {
                val entry = OwnedStackScopingPackagedWorkload::class.java.getMethod("call")
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
                val result = prepareOwnedStackScoping(
                    runtime,
                    reviewed,
                    io.mazewall.enforcer.api.execution
                        .DomainLimits(1, 1),
                    resources,
                    java.nio.file.Path
                        .of("/no-such-live-agent.so"),
                    identity,
                    listOf(entry.declaringClass, boundary.declaringClass),
                    listOf(entry, boundary),
                    listOf(listOf(boundary, entry)),
                    io.mazewall.enforcer.api.DefaultStacktraceScopingPolicy,
                )
                check(result is io.mazewall.platform.native.LiveSetup.Refused && result.failure.status == io.mazewall.platform.native.LiveFailureStatus.Unsupported)
                check(result.failure.cause is UnsatisfiedLinkError) { "expected missing deployment JNI binding: ${result.failure.cause}" }
                println("MANAGED_PRODUCER_ORIGIN $producerPath")
                println("MANAGED_PRODUCER_UNSUPPORTED reason=${result.failure.reason} cause=${checkNotNull(result.failure.cause)::class.java.name}")
            } finally {
                val report = runtime.drain(timeout)
                check(report is io.mazewall.enforcer.api.execution.DrainReport.Complete) { "runtime drain incomplete: $report" }
                println("MANAGED_PRODUCER_DRAIN_COMPLETE")
            }
        } finally {
            resources.close()
        }
    }
}
