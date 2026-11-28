package io.mazewall.enforcer.api.execution

import io.mazewall.enforcer.api.CanonicalPolicyArtifact
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.internal.execution.HelperBinding
import io.mazewall.enforcer.internal.execution.HelperSupportLifetime
import io.mazewall.enforcer.internal.execution.PreparedHelperReadPlan
import io.mazewall.enforcer.internal.execution.PreparedResourceSet
import io.mazewall.enforcer.internal.execution.ReviewedHelperSupport
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.internal.HelperSecurityObservation
import io.mazewall.ffi.internal.PreparedPath
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.landlock.Landlock
import io.mazewall.landlock.LandlockFdOutcome
import io.mazewall.landlock.LandlockRuleset
import io.mazewall.landlock.PreparedLandlock
import io.mazewall.landlock.RulesetState
import io.mazewall.platform.fd.FdOwnership
import io.mazewall.platform.fd.FdState
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.lifecycle.Pid
import io.mazewall.platform.lifecycle.TestProcessLauncher
import io.mazewall.platform.lifecycle.UsesProcessDescriptors
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.native.NativeEngine
import io.mazewall.platform.native.NativeFileSystem
import io.mazewall.platform.process.JvmChildProcess
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Execution(ExecutionMode.CONCURRENT)
@UsesProcessDescriptors
class PreparedCloseOwnershipTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `failed acquisition retains its original manager and ruleset until explicit drain`() {
        runProbe("legacy338")
    }

    @Test
    fun `suppression disabled startup fatal retains distinct owned ruleset close fatal`() {
        runProbe("legacy338suppression")
    }

    @Test
    fun `dispatched uncertain ruleset close is never replayed after descriptor reuse`() {
        runProbe("legacy338unknown")
    }

    @Test
    fun `confined Legacy acquisition retains its ruleset through manager drain`() {
        runProbe("legacy338confined")
    }

    @Test
    fun `owned acquisition rejects wrong resource policy and engine before effects`() {
        runProbe("legacy338provenance")
    }

    @Test
    fun `claimed origin accepts separate snapshots of the original engine and retires aggregate once`() {
        runProbe("legacy338claimed")
    }

    @Test
    fun `concurrent drains share the original manager cleanup pass`() {
        runProbe("legacy338concurrent")
    }

    @Test
    fun `cancelled running and queued preparations retain manager until their real boundaries exit`() {
        runProbe("legacy338cancel")
    }

    @Test
    fun `interrupted caller preserves its flag while actual manager retains acquisition`() {
        runProbe("legacy338interrupt")
    }

    @Test
    fun `failed actual domain construction consumes its reserved generation`() {
        runProbe("legacy338generation")
    }

    @Test
    fun `drain after actual generation reservation prevents domain commit without reusing the ticket`() {
        runProbe("legacy338generationrace")
    }

    @Test
    fun `predispatch close retains owner while dispatched close is terminal`() = runProbe("path")

    @Test
    fun `helper plan retries retained support without closing borrowed roots`() = runProbe("plan")

    @Test
    fun `claimed manager rollback retires exact artifact and grants once after ruleset`() {
        runProbe("legacy338claimedmanager")
    }

    @Test
    fun `direct manager rejects process-only adapter before owner transfer`() = runProbe("direct-binding-koog")

    @Test
    fun `direct manager rejects mismatched spring class before owner transfer`() = runProbe("direct-binding-spring")

    @Test
    fun `direct manager rejects inherited bootstrap method before owner transfer`() = runProbe("direct-binding-inherited")

    @Test
    fun `direct manager rejects wrong implementation digest without consuming owner`() = runProbe("direct-binding-digest")

    @Test
    fun `direct manager rejects wrong resource generation without consuming owner`() = runProbe("direct-binding-generation")

    @Test
    fun `direct manager rejects missing callable before owner transfer`() = runProbe("direct-binding-missing")

    @Test
    fun `direct manager rejects strict recipe under captured abi two before owner transfer`() = runProbe("direct-binding-abi2")

    @Test
    fun `direct manager rejects write grant under captured abi two before owner transfer`() = runProbe("direct-binding-abi2-write")

    @Test
    fun `direct add rule denial retains the real ruleset for original manager rollback`() = runProbe("direct338-addrule")

    @Test
    fun `direct result manager rollback retires original artifact after ruleset`() {
        runProbe("direct338claimedmanager")
    }

    @Test
    fun `direct unknown ruleset close retains pins and never closes reused descriptor`() {
        runProbe("direct338unknown")
    }

    @Test
    fun `direct dispatched slot close throwing pending remains unknown and never replays`() {
        runProbe("direct338pendingslot")
    }

    @Test
    fun `operation resources retry a retained grant after closing artifact`() = runProbe("operation")

    @Test
    fun `operation resources retain an unknown close without replay`() = runProbe("operation-unknown")

    @Test
    fun `landlock owner retries retained ruleset and closes independent grants`() = runProbe("landlock")

    @Test
    fun `manager retains pre-return path cleanup after second open fails`() {
        runProbe("legacy338prereturn")
    }

    @Test
    fun `uncertain pre-return close is not replayed after descriptor reuse`() {
        runProbe("legacy338prereturn-reuse")
    }

    @Test
    fun `stored pre-return owner survives caller cancellation until manager exits`() {
        runProbe("legacy338prereturn-race")
    }

    @Test
    fun `pre-return path close pre-dispatch failure retries on original manager`() {
        runProbe("legacy338prereturn-predispatch")
    }

    private fun runProbe(mode: String) {
        val agent = System.getProperty("mazewall.test.childCoverage.agent")
        val coverageDirectory = System.getProperty("mazewall.test.childCoverage.directory")
        check((agent == null) == (coverageDirectory == null)) { "Owned child coverage requires both agent and output directory" }
        val coverageFile = coverageDirectory?.let {
            check(it.isNotBlank()) { "Empty owned child coverage directory" }
            val outputDirectory = Path.of(it)
            check(outputDirectory.isAbsolute && Files.isDirectory(outputDirectory) && !Files.isSymbolicLink(outputDirectory))
            val expectedDirectory = Path.of(System.getProperty("user.dir")).toRealPath().resolve("build/jacoco/unit-children")
            check(outputDirectory.toRealPath() == outputDirectory && outputDirectory == expectedDirectory)
            outputDirectory.resolve("ownership-${java.util.UUID.randomUUID()}.exec").also { path ->
                check(!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            }
        }
        val coverageArguments = if (coverageFile == null) {
            emptyList()
        } else {
            val agentPath = Path.of(checkNotNull(agent))
            check(agentPath.isAbsolute && Files.isRegularFile(agentPath, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            check(agentPath == Path.of(checkNotNull(coverageDirectory)).resolve("jacocoagent.jar"))
            listOf("-javaagent:$agentPath=destfile=$coverageFile,append=false,includes=io.mazewall.*")
        }
        val builder = ProcessBuilder(
            listOf(Path.of(System.getProperty("java.home"), "bin", "java").toString()) +
                coverageArguments +
                listOf(
                    "-XX:TieredStopAtLevel=1",
                    "-Xmx256m",
                    "-Xms64m",
                    "--enable-native-access=ALL-UNNAMED",
                    "-cp",
                    System.getProperty("java.class.path"),
                    PreparedCloseOwnershipProbe::class.java.name,
                    directory.toString(),
                    mode,
                ),
        )
        JvmChildProcess.stripInheritedJvmOptions(builder.environment())
        val log = directory.resolve("close-ownership-$mode.log")
        val process = TestProcessLauncher.startWithLog(builder, log)
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Close ownership probe must finish")
            Assertions.assertEquals(0, process.exitValue()) { TestProcessLauncher.readLog(log) }
            if (coverageFile != null) validateOwnedCoverage(coverageFile, Path.of(checkNotNull(agent)))
        } finally {
            if (process.isAlive) process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Probe child must be reaped")
        }
    }

    private fun validateOwnedCoverage(
        file: Path,
        agent: Path,
    ) {
        check(Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "Missing owned child coverage" }
        val size = Files.size(file)
        check(size in 1L..8_388_608L) { "Invalid owned child coverage size" }
        val bytes = Files.readAllBytes(file)
        check(bytes.size.toLong() == size) { "Owned child coverage changed while reading" }
        val prefix = java.util.zip.ZipFile(agent.toFile()).use { archive ->
            val matches = archive
                .entries()
                .asSequence()
                .map { it.name }
                .filter { it.startsWith("org/jacoco/agent/rt/internal_") && it.endsWith("/core/data/ExecutionDataReader.class") }
                .toList()
            check(matches.size == 1) { "Unsupported JaCoCo reader" }
            matches.single().removeSuffix("/core/data/ExecutionDataReader.class").replace('/', '.')
        }
        URLClassLoader(arrayOf(agent.toUri().toURL()), null).use { loader ->
            val readerClass = loader.loadClass("$prefix.core.data.ExecutionDataReader")
            val sessionVisitor = loader.loadClass("$prefix.core.data.ISessionInfoVisitor")
            val dataVisitor = loader.loadClass("$prefix.core.data.IExecutionDataVisitor")
            var sessions = 0
            var records = 0
            var ownershipHit = false
            val sessionProxy = java.lang.reflect.Proxy
                .newProxyInstance(loader, arrayOf(sessionVisitor)) { _, _, _ ->
                    sessions++
                    null
                }
            val dataProxy = java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(dataVisitor)) { _, _, args ->
                val entry = checkNotNull(args).single()
                records++
                val name = entry.javaClass.getMethod("getName").invoke(entry) as String
                val hits = entry.javaClass.getMethod("hasHits").invoke(entry) as Boolean
                if ((name.startsWith("io/mazewall/enforcer/api/execution/") || name.startsWith("io/mazewall/ffi/internal/PreparedPath")) && hits) ownershipHit = true
                null
            }
            java.io.ByteArrayInputStream(bytes).use { stream ->
                val reader = readerClass.getConstructor(java.io.InputStream::class.java).newInstance(stream)
                readerClass.getMethod("setSessionInfoVisitor", sessionVisitor).invoke(reader, sessionProxy)
                readerClass.getMethod("setExecutionDataVisitor", dataVisitor).invoke(reader, dataProxy)
                val read = readerClass.getMethod("read")
                while (read.invoke(reader) as Boolean) { /* consume complete blocks */ }
                check(stream.available() == 0 && sessions > 0 && records > 0 && ownershipHit) { "Incomplete owned child coverage" }
            }
        }
    }
}

internal object PreparedCloseOwnershipProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args[1] == "direct-binding-koog") return directBindingRefusal(Path.of(args[0]), false)
        if (args[1] == "direct-binding-spring") return directBindingRefusal(Path.of(args[0]), true)
        if (args[1] == "direct-binding-inherited") return directBindingRefusal(Path.of(args[0]), false, true)
        if (args[1] == "direct-binding-digest") return directBindingRefusal(Path.of(args[0]), false, mismatch = "digest")
        if (args[1] == "direct-binding-generation") return directBindingRefusal(Path.of(args[0]), false, mismatch = "generation")
        if (args[1] == "direct-binding-missing") return directBindingRefusal(Path.of(args[0]), false, missing = true)
        if (args[1] == "direct-binding-abi2-write") return directBindingRefusal(Path.of(args[0]), false, abi2 = true, writeGrant = true)
        if (args[1] == "direct-binding-abi2") return directBindingRefusal(Path.of(args[0]), false, abi2 = true)
        if (args[1] == "direct338-addrule") return directAddRule338(Path.of(args[0]))
        if (args[1] == "legacy338suppression") return legacyAcquisition338(Path.of(args[0]), true, false, false, true)
        if (args[1] == "legacy338prereturn-race") return legacyPreReturnRace338(Path.of(args[0]))
        if (args[1] == "legacy338prereturn") return legacyPreReturn338(Path.of(args[0]), false, false)
        if (args[1] == "legacy338prereturn-reuse") return legacyPreReturn338(Path.of(args[0]), true, false)
        if (args[1] == "legacy338prereturn-predispatch") return legacyPreReturn338(Path.of(args[0]), false, true)
        if (args[1] == "legacy338provenance") return legacyProvenance338(Path.of(args[0]))
        if (args[1] == "legacy338claimed") return legacyClaimed338(Path.of(args[0]))
        if (args[1] == "legacy338claimedmanager") return legacyClaimedManager338(Path.of(args[0]), false)
        if (args[1] == "direct338claimedmanager") return legacyClaimedManager338(Path.of(args[0]), true)
        if (args[1] == "direct338unknown") return legacyClaimedManager338(Path.of(args[0]), true, true)
        if (args[1] == "direct338pendingslot") return legacyClaimedManager338(Path.of(args[0]), true, false, true)
        if (args[1] == "legacy338cancel") return legacyCancel338(Path.of(args[0]))
        if (args[1] == "legacy338interrupt") return legacyInterrupt338(Path.of(args[0]))
        if (args[1] == "legacy338generation") return legacyGeneration338()
        if (args[1] == "legacy338generationrace") return legacyGenerationRace338()
        if (args[1].startsWith("legacy338")) return legacyAcquisition338(Path.of(args[0]), args[1] == "legacy338unknown", args[1] == "legacy338confined", args[1] == "legacy338concurrent")
        if (args[1] == "plan") return plan(Path.of(args[0]))
        if (args[1] == "operation" || args[1] == "operation-unknown") {
            return operation(Path.of(args[0]), unknown = args[1] == "operation-unknown")
        }
        if (args[1] == "landlock") return landlock(Path.of(args[0]))
        val path = Files.writeString(Path.of(args[0]).resolve("resource"), "resource")
        val real = LinuxNative.snapshot()
        val failure = IllegalStateException("before dispatch")
        var target = -1
        var armed = false
        var syntheticError = false
        val attempts = mutableListOf<Int>()
        val native = object : NativeEngine by real {
            override val fileSystem = object : NativeFileSystem by real.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    attempts.add(fd.value)
                    if (fd.value == target && armed) {
                        armed = false
                        throw failure
                    }
                    val result = real.fileSystem.close(fd)
                    return if (fd.value == target && syntheticError) LinuxNative.SyscallResult.Error(5, -1) else result
                }
            }
        }
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(path).build()
        val resources = PreparedResourceSet.capture(policy.definition, native)
        val selected = resources.grants.single { it.path.requested == path }.path
        target = selected.descriptor.value
        val identity = Files.readAttributes(selected.descriptorPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
        armed = true
        check(runCatching { resources.close() }.exceptionOrNull() === failure)
        check(attempts.size == resources.grants.size) { "independent owners must all be attempted" }
        check(selected.descriptor.isLiveForIo()) { "predispatch failure must retain the original typed owner" }
        check(Files.readAttributes(selected.descriptorPath(), java.nio.file.attribute.BasicFileAttributes::class.java).fileKey() == identity)
        resources.close()
        check(!selected.descriptor.isLiveForIo())
        check(attempts.count { it == target } == 2)

        val terminal = PreparedPath.open(path, native)
        target = terminal.descriptor.value
        val before = attempts.size
        syntheticError = true
        val closeFailure = checkNotNull(runCatching { terminal.close() }.exceptionOrNull())
        check(!terminal.descriptor.isLiveForIo())
        check(runCatching { terminal.close() }.exceptionOrNull() === closeFailure)
        check(attempts.size == before + 1) { "dispatched generation must not be closed twice" }
    }

    private fun legacyPreReturnRace338(directory: Path) {
        val path = Files.writeString(directory.resolve("race"), "race")
        val original = LinuxNative.snapshot()
        val token = java.util.concurrent.atomic
            .AtomicReference<FileDescriptor<*, FdState.Open, FdOwnership.Owned>>()
        val manager = java.util.concurrent.atomic
            .AtomicReference<Thread>()
        val closes = java.util.concurrent.atomic
            .AtomicInteger()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val failure = AssertionError("after typed path store")
        val engine = object : NativeEngine by original {
            override val fileSystem = object : NativeFileSystem by original.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (fd === token.get()) {
                        check(Thread.currentThread() === manager.get())
                        closes.incrementAndGet()
                    }
                    return original.fileSystem.close(fd)
                }
            }
        }
        LinuxNative.setEngine(engine)
        val environment = io.mazewall.enforcer.api.InstallationEnvironment
            .capture(io.mazewall.enforcer.api.Platform.FallbackBehavior.FAIL)
        val effectsClass = Class.forName("io.mazewall.enforcer.api.execution.LegacyAcquisitionEffects")
        val effectConstructor = effectsClass
            .getDeclaredConstructor(
            kotlin.jvm.functions.Function0::class.java,
            kotlin.jvm.functions.Function0::class.java,
            kotlin.jvm.functions.Function0::class.java,
            kotlin.jvm.functions.Function0::class.java,
        ).apply { isAccessible = true }
        lateinit var runtime: ManagedRuntime
        val noOp: () -> Unit = {}
        val afterStore: () -> Unit = {
            manager.set(Thread.currentThread())
            val control = ManagedRuntime::class.java
                .getDeclaredField("legacyControl")
                .apply { isAccessible = true }
                .get(runtime)
            val record = control.javaClass
                .getDeclaredField("active")
                .apply { isAccessible = true }
                .get(control)
            val capture = record.javaClass
                .getDeclaredField("managedCapture")
                .apply { isAccessible = true }
                .get(record)
            val slots = capture.javaClass
                .getDeclaredField("slots")
                .apply { isAccessible = true }
                .get(capture) as List<*>
            val first = checkNotNull(slots.first())

            @Suppress("UNCHECKED_CAST")
            val actual = first.javaClass
                .getDeclaredField("descriptor")
                .apply { isAccessible = true }
                .get(first) as FileDescriptor<*, FdState.Open, FdOwnership.Owned>
            token.set(actual)
            entered.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var released = false
            while (!released && System.nanoTime() < deadline) {
                try {
                    released = release.await(100, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    // Keep the physical manager paused until the test releases the actual owner.
                }
            }
            check(released)
            throw failure
        }
        val effects = effectConstructor.newInstance(noOp, noOp, noOp, afterStore)
        val ctor = ManagedRuntime::class.java
            .getDeclaredConstructor(
            RuntimeProfile::class.java,
            io.mazewall.enforcer.api.InstallationEnvironment::class.java,
            java.time.Duration::class.java,
            java.time.Duration::class.java,
            effectsClass,
        ).apply { isAccessible = true }
        runtime = ctor.newInstance(RuntimeProfile.HOTSPOT_JIT, environment, java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(5), effects)
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(path).build()
        val caller = Thread.ofPlatform().start { runCatching { runtime.prepare(policy, DomainLimits(1, 1)) } }
        try {
            check(entered.await(5, TimeUnit.SECONDS))
            caller.interrupt()
            caller.join(5000)
            check(!caller.isAlive)
            val pending = runtime.drain(java.time.Duration.ZERO)
            check(pending is DrainReport.Incomplete && pending.liveWorkers == 1)
            check(closes.get() == 0 && checkNotNull(token.get()).isLiveForIo())
            release.countDown()
            val settled = runtime.drain(java.time.Duration.ofSeconds(5))
            check(settled is DrainReport.Incomplete && settled.cleanupFailure === failure && settled.liveWorkers == 0) { "settled=$settled failure=$failure closes=${closes.get()}" }
            check(closes.get() == 1 && !checkNotNull(token.get()).isLiveForIo() && !checkNotNull(manager.get()).isAlive)
        } finally {
            release.countDown()
            if (caller.isAlive) caller.interrupt()
            caller.join(5000)
            runtime.drain(java.time.Duration.ofSeconds(5))
            LinuxNative.setEngine(original)
        }
    }

    private fun legacyPreReturn338(
        directory: Path,
        closeThenError: Boolean,
        preDispatch: Boolean,
    ) {
        val first = Files.writeString(directory.resolve("first"), "first")
        val second = Files.writeString(directory.resolve("second"), "second")
        val original = LinuxNative.snapshot()
        val fd = java.util.concurrent.atomic
            .AtomicReference<FileDescriptor<*, FdState.Open, FdOwnership.Owned>>()
        val attempts = java.util.concurrent.atomic
            .AtomicInteger()
        val opens = java.util.concurrent.atomic
            .AtomicInteger()
        val reused = mutableListOf<PreparedPath>()
        val manager = java.util.concurrent.atomic
            .AtomicReference<Thread>()
        lateinit var runtime: ManagedRuntime
        lateinit var foreign: ManagedRuntime
        val engine = object : NativeEngine by original {
            override val fileSystem = object : NativeFileSystem by original.fileSystem {
                override fun open(
                    path: io.mazewall.ffi.memory.ManagedSegment,
                    flags: io.mazewall.platform.lifecycle.OpenFlags,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (opens.incrementAndGet() == 2) return LinuxNative.SyscallResult.Error(5, -1)
                    val control = ManagedRuntime::class.java
                        .getDeclaredField("legacyControl")
                        .apply { isAccessible = true }
                        .get(runtime)
                    val record = control.javaClass
                        .getDeclaredField("active")
                        .apply { isAccessible = true }
                        .get(control)
                    val capture = record.javaClass
                        .getDeclaredField("managedCapture")
                        .apply { isAccessible = true }
                        .get(record)
                    val key = ManagedRuntime::class.java
                        .getDeclaredField("legacyOwnershipKey")
                        .apply { isAccessible = true }
                        .get(runtime)
                    val forged = PreparedPath.ManagedSlot(foreign, key, record, capture, environmentNative(runtime), first) {}
                    check(runCatching { forged.open() }.exceptionOrNull() is IllegalStateException)
                    val forgedSlot = PreparedPath.ManagedSlot(runtime, key, record, capture, environmentNative(runtime), first) {}
                    check(runCatching { forgedSlot.open() }.exceptionOrNull() is IllegalStateException)
                    val request = record.javaClass
                        .getDeclaredField("request")
                        .apply { isAccessible = true }
                        .get(record)
                    val definition = request.javaClass
                        .getDeclaredField("definition")
                        .apply { isAccessible = true }
                        .get(request) as io.mazewall.enforcer.api.PolicyDefinition<*>
                    var callbacks = 0
                    val forgedCapture = PreparedResourceSet.ManagedCapture(runtime, key, record, definition, environmentNative(runtime)) { callbacks++ }
                    check(runCatching { forgedCapture.capture() }.exceptionOrNull() is IllegalStateException)
                    check(callbacks == 0) { "forged capture must not invoke callback" }
                    val slots = capture.javaClass
                        .getDeclaredField("slots")
                        .apply { isAccessible = true }
                        .get(capture) as List<*>
                    val activeSlot = slots.first() as PreparedPath.ManagedSlot
                    check(runCatching { activeSlot.closeOwned() }.exceptionOrNull() is IllegalStateException)
                    check(runCatching { activeSlot.open() }.exceptionOrNull() is IllegalStateException)
                    check(opens.get() == 1 && attempts.get() == 0) { "forged runtime, slot and phase must fail before effects" }
                    manager.set(Thread.currentThread())
                    return original.fileSystem.open(path, flags)
                }

                override fun close(token: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (fd.get() == null) fd.set(token)
                    if (token === fd.get()) {
                        check(Thread.currentThread() === manager.get())
                        if (attempts.incrementAndGet() == 1 && !preDispatch) {
                            if (closeThenError) {
                                original.fileSystem.close(token)
                                return LinuxNative.SyscallResult.Error(5, -1)
                            }
                            throw IllegalStateException("before real close")
                        }
                    }
                    return original.fileSystem.close(token)
                }
            }
        }
        LinuxNative.setEngine(engine)
        val environment = io.mazewall.enforcer.api.InstallationEnvironment
            .capture(io.mazewall.enforcer.api.Platform.FallbackBehavior.FAIL)
        val constructor = ManagedRuntime::class.java
            .getDeclaredConstructor(
            RuntimeProfile::class.java,
            io.mazewall.enforcer.api.InstallationEnvironment::class.java,
            java.time.Duration::class.java,
            java.time.Duration::class.java,
        ).apply { isAccessible = true }
        runtime = if (preDispatch) {
            val effectsClass = Class.forName("io.mazewall.enforcer.api.execution.LegacyAcquisitionEffects")
            val effectsCtor = effectsClass.getDeclaredConstructor(kotlin.jvm.functions.Function0::class.java, kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
            val calls = java.util.concurrent.atomic
                .AtomicInteger()
            val noOp: () -> Unit = {}
            val before: () -> Unit = {
                if (calls.incrementAndGet() == 1) {
                    val control = ManagedRuntime::class.java
                        .getDeclaredField("legacyControl")
                        .apply { isAccessible = true }
                        .get(runtime)
                    val record = control.javaClass
                        .getDeclaredField("active")
                        .apply { isAccessible = true }
                        .get(control)
                    val capture = record.javaClass
                        .getDeclaredField("managedCapture")
                        .apply { isAccessible = true }
                        .get(record)
                    val slots = capture.javaClass
                        .getDeclaredField("slots")
                        .apply { isAccessible = true }
                        .get(capture) as List<*>
                    val firstSlot = checkNotNull(slots.first())

                    @Suppress("UNCHECKED_CAST")
                    val owned = firstSlot.javaClass
                        .getDeclaredField("descriptor")
                        .apply { isAccessible = true }
                        .get(firstSlot) as FileDescriptor<*, FdState.Open, FdOwnership.Owned>
                    fd.set(owned)
                    throw IllegalStateException("before path close dispatch")
                }
            }
            val withEffects = ManagedRuntime::class.java
                .getDeclaredConstructor(
                RuntimeProfile::class.java,
                io.mazewall.enforcer.api.InstallationEnvironment::class.java,
                java.time.Duration::class.java,
                java.time.Duration::class.java,
                effectsClass,
            ).apply { isAccessible = true }
            withEffects.newInstance(RuntimeProfile.HOTSPOT_JIT, environment, java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(5), effectsCtor.newInstance(noOp, before))
        } else {
            constructor.newInstance(RuntimeProfile.HOTSPOT_JIT, environment, java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(5))
        }
        foreign = constructor.newInstance(RuntimeProfile.HOTSPOT_JIT, environment, java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(5))
        val policy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.HOTSPOT_JIT)
            .allowFsRead(first)
            .allowFsRead(second)
            .build()
        try {
            check(runCatching { runtime.prepare(policy, DomainLimits(1, 1)) }.isFailure)
            val retained = checkNotNull(fd.get())
            if (preDispatch) check(attempts.get() == 0) { "pre-dispatch failure must not close the token" }
            if (!closeThenError) check(retained.isLiveForIo())
            val report = runtime.drain(java.time.Duration.ofSeconds(5))
            check(report is DrainReport.Incomplete) { "cleanup history must remain retained" }
            check(attempts.get() == 1) { "original manager must close exactly once" }
            if (preDispatch) {
                check(!retained.isLiveForIo())
            } else if (!closeThenError) {
                check(retained.isLiveForIo())
            }
            if (closeThenError) {
                repeat(64) {
                    if (reused.none { path -> path.descriptor.value == retained.value }) reused.add(PreparedPath.open(first, original))
                }
                val replacement = checkNotNull(reused.firstOrNull { it.descriptor.value == retained.value })
                runtime.drain(java.time.Duration.ofSeconds(5))
                check(attempts.get() == 1 && replacement.descriptor.isLiveForIo()) { "reused descriptor must remain open" }
            }
        } finally {
            runtime.drain(java.time.Duration.ofSeconds(5))
            val retained = fd.get()
            reused.forEach { it.close() }
            if (!closeThenError && retained != null && retained.isLiveForIo()) {
                original.fileSystem.close(retained)
                retained.markClosed()
            }
            foreign.drain(java.time.Duration.ofSeconds(5))
            LinuxNative.setEngine(original)
        }
    }

    private fun environmentNative(runtime: ManagedRuntime): NativeEngine =
        (
            ManagedRuntime::class.java
            .getDeclaredField("environment")
            .apply { isAccessible = true }
            .get(runtime) as io.mazewall.enforcer.api.InstallationEnvironment
        ).native

    private fun legacyGenerationRace338() {
        val environment = io.mazewall.enforcer.api.InstallationEnvironment
            .capture(io.mazewall.enforcer.api.Platform.FallbackBehavior.FAIL)
        val arrived = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val recorded = java.util.concurrent.atomic
            .AtomicLong()
        lateinit var runtime: ManagedRuntime
        val noOp: () -> Unit = {}
        val afterReservation: () -> Unit = {
            val control = ManagedRuntime::class.java
                .getDeclaredField("legacyControl")
                .apply { isAccessible = true }
                .get(runtime)
            val owner = control.javaClass
                .getDeclaredField("active")
                .apply { isAccessible = true }
                .get(control)
            recorded.set(
                owner.javaClass
                .getDeclaredField("reservedGeneration")
                .apply { isAccessible = true }
                .getLong(owner),
            )
            arrived.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val effectsClass = Class.forName("io.mazewall.enforcer.api.execution.LegacyAcquisitionEffects")
        val effects = effectsClass
            .getDeclaredConstructor(kotlin.jvm.functions.Function0::class.java, kotlin.jvm.functions.Function0::class.java, kotlin.jvm.functions.Function0::class.java)
            .apply { isAccessible = true }
            .newInstance(noOp, noOp, afterReservation)
        val constructor = ManagedRuntime::class.java
            .getDeclaredConstructor(
            RuntimeProfile::class.java,
            io.mazewall.enforcer.api.InstallationEnvironment::class.java,
            java.time.Duration::class.java,
            java.time.Duration::class.java,
            effectsClass,
        ).apply {
            isAccessible =
            true
        }
        runtime = constructor.newInstance(RuntimeProfile.HOTSPOT_JIT, environment, java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(5), effects)
        val caller = java.util.concurrent.Executors
            .newSingleThreadExecutor()
        try {
            val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build()
            val result = caller.submit<Throwable?> { runCatching { runtime.prepare(policy, DomainLimits(1, 1)) }.exceptionOrNull() }
            check(arrived.await(5, TimeUnit.SECONDS))
            val generationState = ManagedRuntime::class.java
                .getDeclaredField("shared")
                .apply { isAccessible = true }
                .get(runtime)
            val generation = generationState.javaClass.getDeclaredField("nextGeneration").apply { isAccessible = true }
            check(recorded.get() == 1L && generation.getLong(generationState) == 1L)
            check(runtime.drain(java.time.Duration.ZERO) is DrainReport.Incomplete)
            release.countDown()
            check(result.get(5, TimeUnit.SECONDS) is java.util.concurrent.RejectedExecutionException)
            check(generation.getLong(generationState) == 1L) { "rejected commit must retain consumed ticket" }
            check(runtime.drain(java.time.Duration.ofSeconds(5)) == DrainReport.Complete)
        } finally {
            release.countDown()
            caller.shutdown()
            check(caller.awaitTermination(5, TimeUnit.SECONDS))
            runtime.drain(java.time.Duration.ofSeconds(5))
        }
    }

    private fun legacyGeneration338() {
        val environment = io.mazewall.enforcer.api.InstallationEnvironment
            .capture(io.mazewall.enforcer.api.Platform.FallbackBehavior.FAIL)
        val constructor = ManagedRuntime::class.java
            .getDeclaredConstructor(
            RuntimeProfile::class.java,
            io.mazewall.enforcer.api.InstallationEnvironment::class.java,
            java.time.Duration::class.java,
            java.time.Duration::class.java,
        ).apply {
            isAccessible =
            true
        }
        val runtime = constructor.newInstance(RuntimeProfile.HOTSPOT_JIT, environment, java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(5))
        try {
            val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).build()
            val failure = runCatching { runtime.prepare(policy, DomainLimits(1, Int.MAX_VALUE)) }.exceptionOrNull()
            check(failure is OutOfMemoryError) { "oversized ArrayBlockingQueue must actually fail: $failure" }
            val generationState = ManagedRuntime::class.java
                .getDeclaredField("shared")
                .apply { isAccessible = true }
                .get(runtime)
            val generation = generationState.javaClass.getDeclaredField("nextGeneration").apply { isAccessible = true }
            check(generation.getLong(generationState) == 1L) { "failed actual domain construction must consume generation one" }
            check(runtime.prepare(policy, DomainLimits(1, 1)).generation == 2L) { "the next domain must not reuse the consumed generation" }
        } finally {
            runtime.drain(java.time.Duration.ofSeconds(5))
        }
    }

    private fun legacyInterrupt338(directory: Path) {
        val path = Files.writeString(directory.resolve("interrupted"), "resource")
        val environment = io.mazewall.enforcer.api.InstallationEnvironment
            .capture(io.mazewall.enforcer.api.Platform.FallbackBehavior.FAIL)
        val arrived = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val manager = java.util.concurrent.atomic
            .AtomicReference<Thread>()
        val managerInterrupted = java.util.concurrent.atomic
            .AtomicBoolean()
        val callerInterrupted = java.util.concurrent.atomic
            .AtomicBoolean()
        val failure = AssertionError("interrupted acquisition")
        val rollback = java.util.concurrent.atomic
            .AtomicInteger()
        val effectsClass = Class.forName("io.mazewall.enforcer.api.execution.LegacyAcquisitionEffects")
        val effectsConstructor = effectsClass.getDeclaredConstructor(kotlin.jvm.functions.Function0::class.java, kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
        val onStore: () -> Unit = {
            manager.set(Thread.currentThread())
            arrived.countDown()
            var done = false
            while (!done) {
                try {
                    done = release.await(5, TimeUnit.SECONDS)
                    check(done)
                } catch (_: InterruptedException) {
                    managerInterrupted.set(true)
                }
            }
            throw failure
        }
        val onBefore: () -> Unit = { if (rollback.incrementAndGet() == 1) throw AssertionError("predispatch") }
        val effects = effectsConstructor.newInstance(onStore, onBefore)
        val constructor = ManagedRuntime::class.java
            .getDeclaredConstructor(
            RuntimeProfile::class.java,
            io.mazewall.enforcer.api.InstallationEnvironment::class.java,
            java.time.Duration::class.java,
            java.time.Duration::class.java,
            effectsClass,
        ).apply {
            isAccessible =
            true
        }
        val runtime = constructor.newInstance(RuntimeProfile.HOTSPOT_JIT, environment, java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(5), effects)
        val result = java.util.concurrent.atomic
            .AtomicReference<Throwable?>()
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(path).build()
        val caller = Thread.ofPlatform().start {
            result.set(runCatching { runtime.prepare(policy, DomainLimits(1, 1)) }.exceptionOrNull())
            callerInterrupted.set(Thread.currentThread().isInterrupted)
        }
        try {
            check(arrived.await(5, TimeUnit.SECONDS))
            caller.interrupt()
            caller.join(5000)
            check(!caller.isAlive && result.get() is InterruptedException && callerInterrupted.get())
            val pending = runtime.drain(java.time.Duration.ZERO)
            check(pending is DrainReport.Incomplete && pending.liveWorkers == 1 && checkNotNull(manager.get()).isAlive)
            release.countDown()
            val settled = runtime.drain(java.time.Duration.ofSeconds(5))
            check(settled is DrainReport.Incomplete && settled.cleanupFailure === failure && settled.liveWorkers == 0)
            check(managerInterrupted.get() && rollback.get() == 2 && !checkNotNull(manager.get()).isAlive)
        } finally {
            release.countDown()
            if (caller.isAlive) caller.interrupt()
            caller.join(5000)
            runtime.drain(java.time.Duration.ofSeconds(5))
        }
    }

    private fun legacyCancel338(directory: Path) {
        val path = Files.writeString(directory.resolve("cancel"), "resource")
        val native = LinuxNative.snapshot()
        val environment = io.mazewall.enforcer.api.InstallationEnvironment
            .capture(io.mazewall.enforcer.api.Platform.FallbackBehavior.FAIL)
        val arrived = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val interrupted = java.util.concurrent.atomic
            .AtomicBoolean()
        val stores = java.util.concurrent.atomic
            .AtomicInteger()
        val rollback = java.util.concurrent.atomic
            .AtomicInteger()
        val managerThread = java.util.concurrent.atomic
            .AtomicReference<Thread>()
        val failure = AssertionError("held after ruleset store")
        val effectsClass = Class.forName("io.mazewall.enforcer.api.execution.LegacyAcquisitionEffects")
        val effectsConstructor = effectsClass.getDeclaredConstructor(kotlin.jvm.functions.Function0::class.java, kotlin.jvm.functions.Function0::class.java).apply { isAccessible = true }
        val onStore: () -> Unit = {
            stores.incrementAndGet()
            managerThread.set(Thread.currentThread())
            arrived.countDown()
            var done = false
            while (!done) {
                try {
                    done = release.await(5, TimeUnit.SECONDS)
                    check(done)
                } catch (_: InterruptedException) {
                    interrupted.set(true)
                }
            }
            throw failure
        }
        val onBefore: () -> Unit = { if (rollback.incrementAndGet() == 1) throw AssertionError("predispatch") }
        val effects = effectsConstructor.newInstance(onStore, onBefore)
        val constructor = ManagedRuntime::class.java
            .getDeclaredConstructor(
            RuntimeProfile::class.java,
            io.mazewall.enforcer.api.InstallationEnvironment::class.java,
            java.time.Duration::class.java,
            java.time.Duration::class.java,
            effectsClass,
        ).apply {
            isAccessible =
            true
        }
        val runtime = constructor.newInstance(RuntimeProfile.HOTSPOT_JIT, environment, java.time.Duration.ofMillis(300), java.time.Duration.ofSeconds(5), effects)
        val callers = java.util.concurrent.Executors
            .newFixedThreadPool(2)
        try {
            val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(path).build()
            val first = callers.submit<Throwable?> { runCatching { runtime.prepare(policy, DomainLimits(1, 1)) }.exceptionOrNull() }
            check(arrived.await(5, TimeUnit.SECONDS))
            val second = callers.submit<Throwable?> { runCatching { runtime.prepare(policy, DomainLimits(1, 1)) }.exceptionOrNull() }
            val executor = ManagedRuntime::class.java
                .getDeclaredField("manager")
                .apply { isAccessible = true }
                .get(runtime) as java.util.concurrent.ThreadPoolExecutor
            val control = ManagedRuntime::class.java
                .getDeclaredField("legacyControl")
                .apply { isAccessible = true }
                .get(runtime)
            val gate = ManagedRuntime::class.java
                .getDeclaredField("lock")
                .apply { isAccessible = true }
                .get(runtime)
            val physical = ManagedRuntime::class.java
                .getDeclaredField("managerThread")
                .apply { isAccessible = true }
                .get(runtime)
            val shared = ManagedRuntime::class.java
                .getDeclaredField("shared")
                .apply { isAccessible = true }
                .get(runtime)
            val preparations = shared.javaClass.getDeclaredField("preparations").apply { isAccessible = true }
            val queued = checkNotNull(executor.queue.poll(5, TimeUnit.SECONDS)) { "real second preparation must reach the original manager queue" }
            try {
                check(queued.javaClass.name.endsWith("LegacyPreparationTask"))
                queued.javaClass.declaredFields.forEach { field ->
                    val ordinary = runCatching { field.get(queued) }.getOrNull()
                    check(ordinary !== control && ordinary !== gate && ordinary !== physical) { "ordinary same-package field access exposed a private job capability" }
                }
                val before = preparations.getInt(shared)
                check(runCatching { queued.run() }.exceptionOrNull() is IllegalStateException) { "foreign run must refuse before Callable or bookkeeping" }
                check(preparations.getInt(shared) == before && stores.get() == 1)
            } finally {
                check(executor.queue.offer(queued, 5, TimeUnit.SECONDS)) { "restore the exact queued job" }
            }
            check(first.get(5, TimeUnit.SECONDS) is java.util.concurrent.TimeoutException)
            check(second.get(5, TimeUnit.SECONDS) is java.util.concurrent.TimeoutException)
            check(interrupted.get()) { "original running manager must observe cancellation interrupt" }
            check(runtime.drain(java.time.Duration.ofMillis(50)) is DrainReport.Incomplete)
            check(checkNotNull(managerThread.get()).isAlive && stores.get() == 1 && rollback.get() == 0)
            release.countDown()
            val duplicateDone = java.util.concurrent.CountDownLatch(1)
            val duplicateFailure = java.util.concurrent.atomic
                .AtomicReference<Throwable?>()
            val duplicate = Runnable {
                try {
                    val before = preparations.getInt(shared)
                    queued.run()
                    check(preparations.getInt(shared) == before) { "duplicate run must not decrement a second time" }
                } catch (problem: Throwable) {
                    duplicateFailure.set(problem)
                } finally {
                    duplicateDone.countDown()
                }
            }
            check(executor.queue.offer(duplicate, 5, TimeUnit.SECONDS))
            check(duplicateDone.await(5, TimeUnit.SECONDS) && duplicateFailure.get() == null)
            val report = runtime.drain(java.time.Duration.ofSeconds(5))
            check(report is DrainReport.Incomplete && report.cleanupFailure === failure)
            check(rollback.get() == 2 && stores.get() == 1) { "queued canceled callable must not reacquire" }
            check(!checkNotNull(managerThread.get()).isAlive)
        } finally {
            release.countDown()
            callers.shutdown()
            check(callers.awaitTermination(5, TimeUnit.SECONDS))
            runtime.drain(java.time.Duration.ofSeconds(5))
        }
        check(native.engineIdentity === LinuxNative.snapshot().engineIdentity)
    }

    private fun legacyClaimed338(directory: Path) {
        val source = Files.writeString(directory.resolve("ClaimedOperation.java"), "package fixture; class ClaimedOperation { public int call() { return 42; } }")
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", directory.toString(), source.toString()) == 0)
        val jar = directory.resolve("claimed.jar")
        JarOutputStream(Files.newOutputStream(jar)).use {
            it.putNextEntry(JarEntry("fixture/ClaimedOperation.class"))
            it.write(Files.readAllBytes(directory.resolve("fixture/ClaimedOperation.class")))
            it.closeEntry()
        }
        URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(jar).build()
            val operation = PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("fixture.ClaimedOperation"), emptySet())
            val environment = io.mazewall.enforcer.api.InstallationEnvironment
                .capture(io.mazewall.enforcer.api.Platform.FallbackBehavior.FAIL)
            check(operation.native !== environment.native && operation.native.engineIdentity === environment.native.engineIdentity)
            val binding = io.mazewall.enforcer.api.OperationBinding(
                "operation",
                io.mazewall.enforcer.api.OperationAdapter.CALLABLE,
                operation.implementationSha256,
                operation.resourceGeneration,
                mapOf("entryPoint" to "call()I"),
            )
            val worker = CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, policy)
            operation.claim(binding, worker, environment.native, environment.platform.capabilities.matrix.landlockAbiVersion)
            val shell = PreparedLandlock.allocateOwnedAcquisition(operation, environment)
            var rulesetClosed = false
            var retirementAttempted = false
            try {
                check(PreparedLandlock.prepareOwnedAcquisitionWithPostStore(policy.definition, environment, shell) {} === shell)
                shell.close()
                rulesetClosed = true
                check(operation.resources.grants.all { it.path.descriptor.isLiveForIo() }) { "shell must not retire claimed aggregate" }
                retirementAttempted = true
                operation.retire()
                check(operation.resources.grants.all { !it.path.descriptor.isLiveForIo() })
            } finally {
                if (rulesetClosed && !retirementAttempted) operation.retire()
            }
        }
    }

    private fun directBindingRefusal(
        directory: Path,
        spring: Boolean,
        inherited: Boolean = false,
        missing: Boolean = false,
        abi2: Boolean = false,
        writeGrant: Boolean = false,
        mismatch: String? = null,
    ) {
        val source = Files.writeString(directory.resolve("BindingOwner.java"), "package fixture; public class BindingOwner { public int call() { return 42; } }")
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", directory.toString(), source.toString()) == 0)
        val jar = directory.resolve("binding-owner.jar")
        JarOutputStream(Files.newOutputStream(jar)).use {
            it.putNextEntry(JarEntry("fixture/BindingOwner.class"))
            it.write(Files.readAllBytes(directory.resolve("fixture/BindingOwner.class")))
            it.closeEntry()
        }
        val original = LinuxNative.snapshot()
        check(original.engineIdentity === io.mazewall.ffi.internal.RealNativeEngine)
        val providerField = io.mazewall.enforcer.api.Platform::class.java.getDeclaredField("provider").apply { isAccessible = true }
        val originalProvider = providerField.get(null) as io.mazewall.enforcer.api.PlatformProvider
        val createCalls = java.util.concurrent.atomic
            .AtomicInteger()
        val closes = java.util.Collections.synchronizedList(mutableListOf<FileDescriptor<*, FdState.Open, FdOwnership.Owned>>())
        val closeSuccess = java.util.Collections.synchronizedMap(java.util.IdentityHashMap<FileDescriptor<*, FdState.Open, FdOwnership.Owned>, Boolean>())
        val engine = object : NativeEngine by original {
            override val raw = object : io.mazewall.platform.native.RawSyscallOperations by original.raw {
                override fun syscall(
                    nr: Long,
                    a1: io.mazewall.platform.syscall.NativeArg,
                    a2: io.mazewall.platform.syscall.NativeArg,
                    a3: io.mazewall.platform.syscall.NativeArg,
                    a4: io.mazewall.platform.syscall.NativeArg,
                    a5: io.mazewall.platform.syscall.NativeArg,
                    a6: io.mazewall.platform.syscall.NativeArg,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (nr == NativeConstants.LANDLOCK_CREATE_RULESET_NR && a1 is io.mazewall.platform.syscall.NativeArg.MemoryArg && a1.value != io.mazewall.ffi.memory.ManagedSegment.NULL) {
                        createCalls.incrementAndGet()
                        return LinuxNative.SyscallResult.Error(NativeConstants.EACCES, -1L)
                    }
                    return original.raw.syscall(nr, a1, a2, a3, a4, a5, a6)
                }
            }
            override val fileSystem = object : NativeFileSystem by original.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    val result = original.fileSystem.close(fd)
                    closes.add(fd)
                    closeSuccess[fd] = result is LinuxNative.SyscallResult.Success
                    return result
                }
            }
        }
        var owner: DirectResolutionCapture? = null
        var runtime: ManagedRuntime? = null
        LinuxNative.setEngine(engine)
        try {
            if (abi2) {
                io.mazewall.enforcer.api.Platform.setProvider(object : io.mazewall.enforcer.api.PlatformProvider by originalProvider {
                    override fun getLandlockAbiVersion(): Int = 2

                    override fun getLandlockCapability(): io.mazewall.enforcer.api.KernelCapability<Int> =
                        io.mazewall.enforcer.api.KernelCapability
                        .Available(2)
                })
            }
            URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
                val writable = if (writeGrant) Files.createDirectory(directory.resolve("writable")) else null
                val builder = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(jar)
                if (writable != null) builder.allowFsReadWrite(writable)
                val policy = builder.build()
                if (writeGrant) check(policy.allowedFsWritePaths.any { Path.of(it.value) == writable })
                val selected = loader.loadClass("fixture.BindingOwner")
                if (missing) check(selected.methods.none { it.name == "missing" && it.parameterCount == 0 })
                if (inherited) {
                    val method = selected.getMethod("hashCode")
                    check(method.returnType == Int::class.javaPrimitiveType && method.declaringClass === Any::class.java)
                    check(method.declaringClass.classLoader !== selected.classLoader)
                }
                val actualOwner = DirectResolutionCapture.create()
                owner = actualOwner
                val operation = actualOwner.prepare(RuntimeProfile.HOTSPOT_JIT, policy, selected, if (abi2 && !writeGrant) setOf(jar) else emptySet())
                val captured = actualOwner.javaClass
                    .getDeclaredField("captured")
                    .apply { isAccessible = true }
                    .get(actualOwner)
                val artifact = captured.javaClass
                    .getDeclaredField("root")
                    .apply { isAccessible = true }
                    .get(captured) as PreparedPath
                val resources = captured.javaClass
                    .getDeclaredField("resources")
                    .apply { isAccessible = true }
                    .get(captured) as PreparedResourceSet

                @Suppress("UNCHECKED_CAST")
                val strictPaths = PreparedResourceSet::class.java
                    .getDeclaredField("strict")
                    .apply { isAccessible = true }
                    .get(resources) as List<PreparedPath>
                check(strictPaths.size == if (abi2 && !writeGrant) 1 else 0)
                if (writeGrant) check(resources.grants.any { it.write && Files.isSameFile(it.path.resolved, requireNotNull(writable)) })
                val paths = resources.grants.map { it.path.descriptor to it.path.resolved } + strictPaths.map { it.descriptor to it.resolved } + (artifact.descriptor to artifact.resolved)
                val owned = paths.map { it.first }
                val reached = java.util.concurrent.atomic
                    .AtomicInteger()
                val hook = actualOwner.javaClass.getDeclaredField("afterValidationPermit").apply { isAccessible = true }
                hook.set(actualOwner, {
                    reached.incrementAndGet()
                    Unit
                })
                val adapter = when {
                    inherited || missing || abi2 || mismatch != null -> io.mazewall.enforcer.api.OperationAdapter.CALLABLE
                    spring -> io.mazewall.enforcer.api.OperationAdapter.SPRING_METHOD
                    else -> io.mazewall.enforcer.api.OperationAdapter.KOOG_TOOL
                }
                val identity = when {
                    inherited -> mapOf("entryPoint" to "hashCode()I")
                    missing -> mapOf("entryPoint" to "missing()I")
                    abi2 || mismatch != null -> mapOf("entryPoint" to "call()I")
                    spring -> mapOf("beanName" to "bean", "className" to "fixture.Other", "methodDescriptor" to "call()I")
                    else -> mapOf("toolName" to "tool", "contractId" to "contract")
                }
                val digest = if (mismatch == "digest") {
                    if (operation.implementationSha256 == "a".repeat(64)) "b".repeat(64) else "a".repeat(64)
                } else {
                    operation.implementationSha256
                }
                val generation = if (mismatch == "generation") operation.resourceGeneration + "-other" else operation.resourceGeneration
                if (mismatch == "digest") check(digest != operation.implementationSha256 && generation == operation.resourceGeneration)
                if (mismatch == "generation") check(digest == operation.implementationSha256 && generation != operation.resourceGeneration)
                val binding = io.mazewall.enforcer.api
                    .OperationBinding("operation", adapter, digest, generation, identity)
                val document = io.mazewall.enforcer.api
                    .OperationPolicyDocument(binding, CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, policy), emptySet(), emptyMap())
                val reviewed = io.mazewall.enforcer.api.ReviewedOperationArtifact.load(
                    document.toJson(),
                    io.mazewall.enforcer.api
                        .TrustedPolicyReview("operator", document.sha256(), emptySet()),
                    binding,
                )
                val environment = io.mazewall.enforcer.api.InstallationEnvironment
                    .capture(io.mazewall.enforcer.api.Platform.FallbackBehavior.FAIL)
                if (abi2) check(environment.native.engineIdentity === engine && environment.platform.capabilities.matrix.landlockAbiVersion == 2)
                val ctor = ManagedRuntime::class.java
                    .getDeclaredConstructor(
                    RuntimeProfile::class.java,
                    io.mazewall.enforcer.api.InstallationEnvironment::class.java,
                    java.time.Duration::class.java,
                    java.time.Duration::class.java,
                ).apply {
                    isAccessible =
                    true
                }
                val actualRuntime = ctor.newInstance(RuntimeProfile.HOTSPOT_JIT, environment, java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(5))
                runtime = actualRuntime
                val failure = runCatching { actualRuntime.prepare(reviewed, DomainLimits(1, 1), operation) }.exceptionOrNull()
                val expected = when {
                    abi2 -> "Read-only/read-write recipes require Landlock ABI 3+ for truncate coverage"
                    mismatch != null -> "Reviewed implementation/resource generation does not match actual acquisitions"
                    missing -> "Required value was null."
                    spring || inherited -> "Failed requirement."
                    else -> "Koog actual tool binding belongs to the process adapter"
                }
                check(failure is IllegalArgumentException && failure.message == expected) { "Unexpected binding refusal: $failure" }
                check(reached.get() == if (abi2 || mismatch != null) 0 else 1)
                check(createCalls.get() == 0)
                if (mismatch != null) {
                    check(reviewed.binding.implementationSha256 == digest && reviewed.binding.resourceGeneration == generation)
                    val retryBinding = io.mazewall.enforcer.api.OperationBinding(
                        "operation",
                        io.mazewall.enforcer.api.OperationAdapter.KOOG_TOOL,
                        operation.implementationSha256,
                        operation.resourceGeneration,
                        mapOf("toolName" to "tool", "contractId" to "contract"),
                    )
                    val retryDocument = io.mazewall.enforcer.api.OperationPolicyDocument(
                        retryBinding,
                        CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, policy),
                        emptySet(),
                        emptyMap(),
                    )
                    val retryReview = io.mazewall.enforcer.api.ReviewedOperationArtifact.load(
                        retryDocument.toJson(),
                        io.mazewall.enforcer.api
                            .TrustedPolicyReview("operator", retryDocument.sha256(), emptySet()),
                        retryBinding,
                    )
                    val retryFailure = runCatching { actualRuntime.prepare(retryReview, DomainLimits(1, 1), operation) }.exceptionOrNull()
                    check(retryFailure is IllegalArgumentException && retryFailure.message == "Koog actual tool binding belongs to the process adapter")
                    check(reached.get() == 1 && createCalls.get() == 0)
                }
                check(owned.all { it.isLiveForIo() && synchronized(closes) { closes.none { closed -> closed === it } } })
                check(paths.all { (fd, path) -> Files.isSameFile(Path.of("/proc/self/fd/${fd.value}"), path) })
                val domains = ManagedRuntime::class.java
                    .getDeclaredField("domains")
                    .apply { isAccessible = true }
                    .get(actualRuntime) as Map<*, *>
                check(domains.isEmpty()) { "No worker domain may be created" }
                check(actualRuntime.drain(java.time.Duration.ofSeconds(5)) == DrainReport.Complete)
                val retired = actualOwner.retire() as DirectResolutionCapture.Retirement.Complete
                check(retired.history == null)
                check(owned.all { fd -> !fd.isLiveForIo() && closeSuccess[fd] == true && synchronized(closes) { closes.count { it === fd } == 1 } })
                check(owned.all { fd -> !Files.exists(Path.of("/proc/self/fd/${fd.value}")) })
                check(actualOwner.retire() == retired)
                check(owned.all { fd -> synchronized(closes) { closes.count { it === fd } == 1 } })
            }
        } finally {
            try {
                runtime?.drain(java.time.Duration.ofSeconds(5))
            } finally {
                try {
                    owner?.close()
                } finally {
                    try {
                        io.mazewall.enforcer.api.Platform
                            .setProvider(originalProvider)
                    } finally {
                        LinuxNative.setEngine(original.engineIdentity as NativeEngine)
                    }
                }
            }
        }
    }

    private fun directAddRule338(directory: Path) {
        val source = Files.writeString(directory.resolve("AddRuleOwner.java"), "package fixture; public class AddRuleOwner { public int call() { return 42; } }")
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", directory.toString(), source.toString()) == 0)
        val jar = directory.resolve("add-rule-owner.jar")
        JarOutputStream(Files.newOutputStream(jar)).use {
            it.putNextEntry(JarEntry("fixture/AddRuleOwner.class"))
            it.write(Files.readAllBytes(directory.resolve("fixture/AddRuleOwner.class")))
            it.closeEntry()
        }
        val original = LinuxNative.snapshot()
        check(original.engineIdentity === io.mazewall.ffi.internal.RealNativeEngine) { "Real native engine required" }
        val stored = java.util.concurrent.atomic
            .AtomicReference<FileDescriptor<*, FdState.Open, FdOwnership.Owned>?>()
        val seamFailure = java.util.concurrent.atomic
            .AtomicReference<Throwable?>()
        val addCalls = java.util.concurrent.atomic
            .AtomicInteger()
        val closeCalls = java.util.concurrent.atomic
            .AtomicInteger()
        val sameRuleset = java.util.concurrent.atomic
            .AtomicBoolean()
        val restrictCalls = java.util.concurrent.atomic
            .AtomicInteger()
        val realCloseSuccess = java.util.concurrent.atomic
            .AtomicBoolean()
        val engine = object : NativeEngine by original {
            override val raw = object : io.mazewall.platform.native.RawSyscallOperations by original.raw {
                override fun syscall(
                    nr: Long,
                    a1: io.mazewall.platform.syscall.NativeArg,
                    a2: io.mazewall.platform.syscall.NativeArg,
                    a3: io.mazewall.platform.syscall.NativeArg,
                    a4: io.mazewall.platform.syscall.NativeArg,
                    a5: io.mazewall.platform.syscall.NativeArg,
                    a6: io.mazewall.platform.syscall.NativeArg,
                ): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (nr == NativeConstants.LANDLOCK_RESTRICT_SELF_NR) {
                        restrictCalls.incrementAndGet()
                        return LinuxNative.SyscallResult.Error(NativeConstants.EACCES, -1L)
                    }
                    if (nr == NativeConstants.LANDLOCK_ADD_RULE_NR) {
                        try {
                            addCalls.incrementAndGet()
                            val fd = (a1 as io.mazewall.platform.syscall.NativeArg.FdArg).fd
                            sameRuleset.set(fd === stored.get() && fd.isLiveForIo())
                        } catch (problem: Throwable) {
                            seamFailure.compareAndSet(null, problem)
                        }
                        return LinuxNative.SyscallResult.Error(NativeConstants.EACCES, -1L)
                    }
                    return original.raw.syscall(nr, a1, a2, a3, a4, a5, a6)
                }
            }
            override val fileSystem = object : NativeFileSystem by original.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    val target = fd === stored.get()
                    if (target) closeCalls.incrementAndGet()
                    val result = original.fileSystem.close(fd)
                    if (target) realCloseSuccess.set(result is LinuxNative.SyscallResult.Success)
                    return result
                }
            }
        }
        var owner: DirectResolutionCapture? = null
        var runtime: ManagedRuntime? = null
        LinuxNative.setEngine(engine)
        try {
            URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
                val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(jar).build()
                val selected = loader.loadClass("fixture.AddRuleOwner")
                val actualOwner = DirectResolutionCapture.create()
                owner = actualOwner
                val operation = actualOwner.prepare(RuntimeProfile.HOTSPOT_JIT, policy, selected, emptySet())
                val binding = io.mazewall.enforcer.api.OperationBinding(
                    "operation",
                    io.mazewall.enforcer.api.OperationAdapter.CALLABLE,
                    operation.implementationSha256,
                    operation.resourceGeneration,
                    mapOf("entryPoint" to "call()I"),
                )
                val document = io.mazewall.enforcer.api
                    .OperationPolicyDocument(binding, CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, policy), emptySet(), emptyMap())
                val reviewed = io.mazewall.enforcer.api.ReviewedOperationArtifact.load(
                    document.toJson(),
                    io.mazewall.enforcer.api
                        .TrustedPolicyReview("operator", document.sha256(), emptySet()),
                    binding,
                )
                lateinit var actualRuntime: ManagedRuntime
                val onStore: () -> Unit = {
                    try {
                        val control = ManagedRuntime::class.java
                            .getDeclaredField("legacyControl")
                            .apply { isAccessible = true }
                            .get(actualRuntime)
                        val active = control.javaClass
                            .getDeclaredField("active")
                            .apply { isAccessible = true }
                            .get(control)
                        val shell = active.javaClass
                            .getDeclaredField("landlock")
                            .apply { isAccessible = true }
                            .get(active)

                        @Suppress("UNCHECKED_CAST")
                        val fd = PreparedLandlock::class.java
                            .getDeclaredField("acquiredRuleset")
                            .apply { isAccessible = true }
                            .get(shell) as FileDescriptor<*, FdState.Open, FdOwnership.Owned>
                        stored.set(fd)
                    } catch (problem: Throwable) {
                        seamFailure.compareAndSet(null, problem)
                    }
                }
                val effectsClass = Class.forName("io.mazewall.enforcer.api.execution.LegacyAcquisitionEffects")
                val effects = effectsClass
                    .getDeclaredConstructor(kotlin.jvm.functions.Function0::class.java, kotlin.jvm.functions.Function0::class.java)
                    .apply { isAccessible = true }
                    .newInstance(onStore, {})
                val environment = io.mazewall.enforcer.api.InstallationEnvironment
                    .capture(io.mazewall.enforcer.api.Platform.FallbackBehavior.FAIL)
                val ctor = ManagedRuntime::class.java
                    .getDeclaredConstructor(
                    RuntimeProfile::class.java,
                    io.mazewall.enforcer.api.InstallationEnvironment::class.java,
                    java.time.Duration::class.java,
                    java.time.Duration::class.java,
                    effectsClass,
                ).apply {
                    isAccessible =
                    true
                }
                actualRuntime = ctor.newInstance(RuntimeProfile.HOTSPOT_JIT, environment, java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(5), effects)
                runtime = actualRuntime
                val failure = runCatching { actualRuntime.prepare(reviewed, DomainLimits(1, 1), operation) }.exceptionOrNull()
                checkNotNull(stored.get()) { "Real ruleset creation and typed storage required" }
                check(seamFailure.get() == null) { "Ruleset observation failed: ${seamFailure.get()}" }
                check(addCalls.get() == 1 && sameRuleset.get()) { "Expected one denied add-rule on original ruleset" }
                check(failure is IllegalStateException && failure.message.orEmpty().contains("errno=${NativeConstants.EACCES}")) { "Expected exact EACCES refusal: $failure" }
                check(restrictCalls.get() == 0) { "restrict_self must not be dispatched" }
                check(actualRuntime.drain(java.time.Duration.ofSeconds(5)) == DrainReport.Complete)
                check(closeCalls.get() == 1 && realCloseSuccess.get() && !checkNotNull(stored.get()).isLiveForIo())
                check(actualRuntime.drain(java.time.Duration.ofSeconds(5)) == DrainReport.Complete)
                check(closeCalls.get() == 1) { "Ruleset close replayed" }
            }
        } finally {
            try {
                runtime?.drain(java.time.Duration.ofSeconds(5))
            } finally {
                try {
                    owner?.close()
                } finally {
                    LinuxNative.setEngine(original.engineIdentity as NativeEngine)
                }
            }
        }
    }

    private fun legacyClaimedManager338(
        directory: Path,
        direct: Boolean,
        unknown: Boolean = false,
        pendingSlot: Boolean = false,
    ) {
        val source = Files.writeString(directory.resolve("ClaimedManager.java"), "package fixture; public class ClaimedManager { public int call() { return 42; } }")
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", directory.toString(), source.toString()) == 0)
        val jar = directory.resolve("claimed-manager.jar")
        JarOutputStream(Files.newOutputStream(jar)).use {
            it.putNextEntry(JarEntry("fixture/ClaimedManager.class"))
            it.write(Files.readAllBytes(directory.resolve("fixture/ClaimedManager.class")))
            it.closeEntry()
        }
        val originalNative = LinuxNative.snapshot()
        val observed = mutableListOf<FileDescriptor<*, FdState.Open, FdOwnership.Owned>>()
        val unknownRuleset = java.util.concurrent.atomic
            .AtomicReference<FileDescriptor<*, FdState.Open, FdOwnership.Owned>>()
        val targetSlot = java.util.concurrent.atomic
            .AtomicReference<FileDescriptor<*, FdState.Open, FdOwnership.Owned>>()
        val pendingFailure = java.util.concurrent.atomic
            .AtomicReference<DirectPendingException>()
        var beforeRulesetClose: () -> Unit = {}
        val engine = object : NativeEngine by originalNative {
            override val fileSystem = object : NativeFileSystem by originalNative.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    synchronized(observed) { observed.add(fd) }
                    if (fd === unknownRuleset.get()) beforeRulesetClose()
                    val result = originalNative.fileSystem.close(fd)
                    if (pendingSlot && fd === targetSlot.get()) {
                        check(result is LinuxNative.SyscallResult.Success)
                        throw checkNotNull(pendingFailure.get())
                    }
                    if (unknown && fd === unknownRuleset.get()) {
                        check(result is LinuxNative.SyscallResult.Success)
                        throw IllegalStateException("uncertain direct ruleset close")
                    }
                    return result
                }
            }
        }
        LinuxNative.setEngine(engine)
        try {
            URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
                val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(jar).build()
                val selected = loader.loadClass("fixture.ClaimedManager")
                val directOwner = if (direct) DirectResolutionCapture.create() else null
                val operation = directOwner?.prepare(RuntimeProfile.HOTSPOT_JIT, policy, selected, emptySet())
                    ?: PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, selected, emptySet())
                val capture = directOwner
                    ?.javaClass
                    ?.getDeclaredField("captured")
                    ?.apply { isAccessible = true }
                    ?.get(directOwner)
                val artifactPath = if (capture != null) {
                    capture.javaClass
                        .getDeclaredField("root")
                        .apply { isAccessible = true }
                        .get(capture) as PreparedPath
                } else {
                    val artifact = operation.javaClass
                        .getDeclaredField("artifact")
                        .apply { isAccessible = true }
                        .get(operation)
                    artifact.javaClass
                        .getDeclaredField("path")
                        .apply { isAccessible = true }
                        .get(artifact) as PreparedPath
                }
                val artifactFd = artifactPath.descriptor
                targetSlot.set(artifactFd)
                val grants = if (capture != null) {
                    (
                        capture.javaClass
                        .getDeclaredField("resources")
                        .apply { isAccessible = true }
                        .get(capture) as PreparedResourceSet
                    ).grants.map { it.path.descriptor }
                } else {
                    operation.resources.grants.map { it.path.descriptor }
                }
                if (pendingSlot) {
                    val pendingOwner = DirectResolutionCapture.create()
                    try {
                        val pendingOperation = pendingOwner.prepare(RuntimeProfile.HOTSPOT_JIT, policy, selected, emptySet())
                        val entered = java.util.concurrent.CountDownLatch(1)
                        val release = java.util.concurrent.CountDownLatch(1)
                        val validationFailure = java.util.concurrent.atomic
                            .AtomicReference<Throwable?>()
                        val hook = pendingOwner.javaClass.getDeclaredField("afterValidationPermit").apply { isAccessible = true }
                        val previous = hook.get(pendingOwner)
                        hook.set(pendingOwner, {
                            entered.countDown()
                            check(release.await(5, TimeUnit.SECONDS))
                        })
                        val validator = Thread.ofPlatform().start {
                            try {
                                pendingOperation.validate()
                            } catch (failure: Throwable) {
                                validationFailure.set(failure)
                            }
                        }
                        try {
                            check(entered.await(5, TimeUnit.SECONDS))
                            pendingFailure.set(runCatching { pendingOperation.close() }.exceptionOrNull() as DirectPendingException)
                        } finally {
                            release.countDown()
                            validator.join(5000)
                            hook.set(pendingOwner, previous)
                            check(!validator.isAlive)
                            check(validationFailure.get() == null)
                        }
                    } finally {
                        pendingOwner.close()
                    }
                }
                val binding = io.mazewall.enforcer.api.OperationBinding(
                    "operation",
                    io.mazewall.enforcer.api.OperationAdapter.CALLABLE,
                    operation.implementationSha256,
                    operation.resourceGeneration,
                    mapOf("entryPoint" to "call()I"),
                )
                val document = io.mazewall.enforcer.api
                    .OperationPolicyDocument(binding, CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, policy), emptySet(), emptyMap())
                val reviewed = io.mazewall.enforcer.api.ReviewedOperationArtifact.load(
                    document.toJson(),
                    io.mazewall.enforcer.api
                        .TrustedPolicyReview("operator", document.sha256(), emptySet()),
                    binding,
                )
                val original = AssertionError("after stored ruleset")
                val preDispatch = AssertionError("before dispatch")
                var before = 0
                var ruleset: FileDescriptor<*, FdState.Open, FdOwnership.Owned>? = null
                lateinit var runtime: ManagedRuntime
                val onStore: () -> Unit = {
                    val control = ManagedRuntime::class.java
                        .getDeclaredField("legacyControl")
                        .apply { isAccessible = true }
                        .get(runtime)
                    val owner = control.javaClass
                        .getDeclaredField("active")
                        .apply { isAccessible = true }
                        .get(control)
                    val shell = owner.javaClass
                        .getDeclaredField("landlock")
                        .apply { isAccessible = true }
                        .get(owner)

                    @Suppress("UNCHECKED_CAST")
                    val actual = PreparedLandlock::class.java
                        .getDeclaredField("acquiredRuleset")
                        .apply { isAccessible = true }
                        .get(shell) as FileDescriptor<*, FdState.Open, FdOwnership.Owned>
                    ruleset = actual
                    unknownRuleset.set(actual)
                    throw original
                }
                val onBefore: () -> Unit = { if (++before == 1 && !unknown && !pendingSlot) throw preDispatch }
                val effectsClass = Class.forName("io.mazewall.enforcer.api.execution.LegacyAcquisitionEffects")
                val effects = effectsClass
                    .getDeclaredConstructor(
                    kotlin.jvm.functions.Function0::class.java,
                    kotlin.jvm.functions.Function0::class.java,
                ).apply { isAccessible = true }
                    .newInstance(onStore, onBefore)
                val environment = io.mazewall.enforcer.api.InstallationEnvironment
                    .capture(io.mazewall.enforcer.api.Platform.FallbackBehavior.FAIL)
                val ctor = ManagedRuntime::class.java
                    .getDeclaredConstructor(
                    RuntimeProfile::class.java,
                    io.mazewall.enforcer.api.InstallationEnvironment::class.java,
                    java.time.Duration::class.java,
                    java.time.Duration::class.java,
                    effectsClass,
                ).apply {
                    isAccessible =
                    true
                }
                runtime = ctor.newInstance(RuntimeProfile.HOTSPOT_JIT, environment, java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(5), effects)
                if (direct) {
                    beforeRulesetClose = {
                        check(runCatching { operation.retireDirect(DirectClaimAction.ROLLBACK_RULESET) }.isFailure)
                        check((grants + artifactFd).all { it.isLiveForIo() })
                    }
                }
                val prepareFailure = runCatching { runtime.prepare(reviewed, DomainLimits(1, 1), operation) }.exceptionOrNull()
                if (direct && prepareFailure !== original) {
                    runtime.drain(java.time.Duration.ofSeconds(5))
                    directOwner?.close()
                    error("direct manager failed before retained ruleset: $prepareFailure")
                }
                check(prepareFailure === original)
                val owned = grants + artifactFd
                if (pendingSlot) {
                    check(grants.all { it.isLiveForIo() })
                    val reused = mutableListOf<PreparedPath>()
                    try {
                        repeat(128) {
                            if (reused.none { it.descriptor.value == artifactFd.value }) reused.add(PreparedPath.open(jar, originalNative))
                        }
                        val replacement = checkNotNull(reused.firstOrNull { it.descriptor.value == artifactFd.value })
                        repeat(2) {
                            val report = runtime.drain(java.time.Duration.ofSeconds(5)) as DrainReport.Incomplete
                            check(report.cleanupFailure === original && report.liveWorkers == 0 && report.unfinishedInvocations == 0)
                        }
                        val control = ManagedRuntime::class.java
                            .getDeclaredField("legacyControl")
                            .apply { isAccessible = true }
                            .get(runtime)
                        val record = control.javaClass
                            .getDeclaredField("active")
                            .apply { isAccessible = true }
                            .get(control)
                        val step = record.javaClass
                            .getDeclaredField("resourcesStep")
                            .apply { isAccessible = true }
                            .get(record)
                        check(step.toString() == "UNKNOWN")
                        val manager = ManagedRuntime::class.java
                            .getDeclaredField("managerThread")
                            .apply { isAccessible = true }
                            .get(runtime) as java.util.concurrent.atomic.AtomicReference<*>
                        check((manager.get() as Thread).isAlive.not())
                        replacement.validate()
                        check(grants.all { it.isLiveForIo() })
                        check(synchronized(observed) { observed.count { it === artifactFd } } == 1)
                        operation.close()
                        directOwner?.close()
                        replacement.validate()
                    } finally {
                        reused.forEach { it.close() }
                    }
                    return
                }
                if (unknown) {
                    check(owned.all { it.isLiveForIo() })
                    val reused = mutableListOf<PreparedPath>()
                    try {
                        repeat(128) {
                            if (reused.none { it.descriptor.value == checkNotNull(ruleset).value }) reused.add(PreparedPath.open(jar, originalNative))
                        }
                        val replacement = checkNotNull(reused.firstOrNull { it.descriptor.value == checkNotNull(ruleset).value })
                        repeat(2) { check(runtime.drain(java.time.Duration.ofSeconds(5)) is DrainReport.Incomplete) }
                        check(replacement.descriptor.isLiveForIo() && owned.all { it.isLiveForIo() })
                        replacement.validate()
                        check(synchronized(observed) { observed.count { it === ruleset } } == 1)
                        operation.close()
                        directOwner?.close()
                        check(replacement.descriptor.isLiveForIo())
                    } finally {
                        reused.forEach { it.close() }
                    }
                    return
                }
                check(owned.all { target -> synchronized(observed) { observed.none { it === target } } })
                check(runtime.drain(java.time.Duration.ofSeconds(5)) is DrainReport.Incomplete)
                val snapshot = synchronized(observed) { observed.toList() }
                val ruleIndex = snapshot.indexOfFirst { it === ruleset }
                check(ruleIndex >= 0 && snapshot.count { it === ruleset } == 1)
                check(owned.all { target -> snapshot.count { it === target } == 1 && snapshot.indexOfFirst { it === target } > ruleIndex })
                operation.close()
                runtime.drain(java.time.Duration.ofSeconds(5))
                check(synchronized(observed) { observed.size } == snapshot.size)
            }
        } finally {
            LinuxNative.setEngine(originalNative.engineIdentity as NativeEngine)
        }
    }

    private fun legacyProvenance338(directory: Path) {
        val path = Files.writeString(directory.resolve("source"), "source")
        val other = Files.writeString(directory.resolve("other"), "other")
        val original = LinuxNative.snapshot()
        val different = object : NativeEngine by original {}
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(path).build()
        val wrongPolicy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(other).build()
        val environment = io.mazewall.enforcer.api.InstallationEnvironment
            .capture(io.mazewall.enforcer.api.Platform.FallbackBehavior.FAIL)
        val local = PreparedResourceSet.capture(policy.definition, environment.native)
        val foreign = PreparedResourceSet.capture(policy.definition, different)
        try {
            var stores = 0
            val afterStore: () -> Unit = { stores++ }
            val wrongPolicyShell = PreparedLandlock.allocateOwnedAcquisition(local, environment)
            check(runCatching { PreparedLandlock.prepareOwnedAcquisitionWithPostStore(wrongPolicy.definition, environment, wrongPolicyShell, afterStore) }.isFailure)
            val wrongEngineShell = PreparedLandlock.allocateOwnedAcquisition(foreign, environment)
            check(runCatching { PreparedLandlock.prepareOwnedAcquisitionWithPostStore(policy.definition, environment, wrongEngineShell, afterStore) }.isFailure)
            check(stores == 0 && local.grants.all { it.path.descriptor.isLiveForIo() } && foreign.grants.all { it.path.descriptor.isLiveForIo() })
        } finally {
            local.close()
            foreign.close()
        }
    }

    private class NoSuppressionFatal : Error("suppression-disabled original fatal", null, false, true)

    private fun legacyAcquisition338(
        directory: Path,
        unknown: Boolean,
        confined: Boolean,
        concurrent: Boolean,
        suppressionDisabled: Boolean = false,
    ) {
        val path = Files.writeString(directory.resolve("legacy-338"), "resource")
        val original: Error = if (suppressionDisabled) NoSuppressionFatal() else AssertionError("after actual ruleset store")
        val beforeDispatch = AssertionError("before rollback dispatch")
        var stored = 0
        var before = 0
        val enteredSweep = java.util.concurrent.CountDownLatch(1)
        val releaseSweep = java.util.concurrent.CountDownLatch(1)
        val originalNative = LinuxNative.snapshot()
        val token = java.util.concurrent.atomic
            .AtomicReference<FileDescriptor<*, FdState.Open, FdOwnership.Owned>>()
        val manager = java.util.concurrent.atomic
            .AtomicReference<Thread>()
        val closes = java.util.concurrent.atomic
            .AtomicInteger()
        val engine = object : NativeEngine by originalNative {
            override val fileSystem = object : NativeFileSystem by originalNative.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    if (fd === token.get()) {
                        check(Thread.currentThread() === manager.get()) { "ruleset close must use original manager" }
                        closes.incrementAndGet()
                        if (unknown) {
                            originalNative.fileSystem.close(fd).getOrThrow("actual ruleset close")
                            throw beforeDispatch
                        }
                    }
                    return originalNative.fileSystem.close(fd)
                }
            }
        }
        LinuxNative.setEngine(engine)
        val effectsClass = Class.forName("io.mazewall.enforcer.api.execution.LegacyAcquisitionEffects")
        val effectsConstructor = effectsClass.getDeclaredConstructor(kotlin.jvm.functions.Function0::class.java, kotlin.jvm.functions.Function0::class.java)
        effectsConstructor.isAccessible = true
        lateinit var runtime: ManagedRuntime
        val onStore: () -> Unit = {
            stored++
            manager.set(Thread.currentThread())
            val control = ManagedRuntime::class.java
                .getDeclaredField("legacyControl")
                .apply { isAccessible = true }
                .get(runtime)
            val owner = control.javaClass
                .getDeclaredField("active")
                .apply { isAccessible = true }
                .get(control)
            val shell = owner.javaClass
                .getDeclaredField("landlock")
                .apply { isAccessible = true }
                .get(owner)

            @Suppress("UNCHECKED_CAST")
            val actual = PreparedLandlock::class.java
                .getDeclaredField("acquiredRuleset")
                .apply { isAccessible = true }
                .get(shell) as FileDescriptor<*, FdState.Open, FdOwnership.Owned>
            token.set(actual)
            throw original
        }
        val onBefore: () -> Unit = {
            before++
            if (!unknown && before == 1) throw beforeDispatch
            if (concurrent && before == 2) {
                enteredSweep.countDown()
                check(releaseSweep.await(5, TimeUnit.SECONDS)) { "bounded sweep barrier" }
            }
        }
        val effects = effectsConstructor.newInstance(onStore, onBefore)
        val environment = io.mazewall.enforcer.api.InstallationEnvironment
            .capture(io.mazewall.enforcer.api.Platform.FallbackBehavior.FAIL)
        val constructor = ManagedRuntime::class.java.getDeclaredConstructor(
            RuntimeProfile::class.java,
            io.mazewall.enforcer.api.InstallationEnvironment::class.java,
            java.time.Duration::class.java,
            java.time.Duration::class.java,
            effectsClass,
        )
        constructor.isAccessible = true
        runtime = constructor.newInstance(RuntimeProfile.HOTSPOT_JIT, environment, java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(5), effects)
        try {
            val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(path).build()
            val carrierClass = Class.forName("io.mazewall.enforcer.api.execution.LegacyAcquisition")
            val carrierConstructor = carrierClass.declaredConstructors.single().apply { isAccessible = true }
            val runtimeLock = ManagedRuntime::class.java
                .getDeclaredField("lock")
                .apply { isAccessible = true }
                .get(runtime)
            val executor = ManagedRuntime::class.java
                .getDeclaredField("manager")
                .apply { isAccessible = true }
                .get(runtime)
            val legacy = io.mazewall.enforcer.internal.execution.FrozenStackScoping
                .Legacy(io.mazewall.enforcer.api.DefaultStacktraceScopingPolicy)
            val requestClass = Class.forName("io.mazewall.enforcer.api.execution.LegacyAcquisitionRequest")
            val requestConstructor = requestClass.declaredConstructors.single().apply { isAccessible = true }
            val forgedRequest = requestConstructor.newInstance(
                policy.definition,
                DomainLimits(1, 1),
                null,
                null,
                legacy,
                null,
                java.util.concurrent.atomic
                .AtomicReference<Any>(),
                    "forged",
            )
            val forged = runCatching {
                val control = ManagedRuntime::class.java
                    .getDeclaredField("legacyControl")
                    .apply { isAccessible = true }
                    .get(runtime)
                carrierConstructor.newInstance(
                    runtime,
                    control,
                    Any(),
                    runtimeLock,
                    executor,
                    environment,
                    effects,
                    forgedRequest,
                )
            }.exceptionOrNull()
            check(forged is java.lang.reflect.InvocationTargetException && forged.cause is IllegalStateException && stored == 0) { "forged key must fail before acquisition" }
            val controlInterface = Class.forName("io.mazewall.enforcer.api.execution.LegacyControlOps")
            val fakeControl = java.lang.reflect.Proxy
                .newProxyInstance(controlInterface.classLoader, arrayOf(controlInterface)) { _, _, _ -> error("forged control must not be invoked") }
            val realKey = ManagedRuntime::class.java
                .getDeclaredField("legacyOwnershipKey")
                .apply { isAccessible = true }
                .get(runtime)
            val foreignControl = runCatching { carrierConstructor.newInstance(runtime, fakeControl, realKey, runtimeLock, executor, environment, effects, forgedRequest) }.exceptionOrNull()
            check(foreignControl is java.lang.reflect.InvocationTargetException && foreignControl.cause is IllegalStateException && stored == 0) { "foreign control must fail before acquisition" }
            val failure = runCatching {
                if (!confined) {
                    runtime.prepare(policy, DomainLimits(1, 1))
                } else {
                    val child = ProcessBuilder("/bin/true").start()
                    try {
                        check(child.waitFor(5, TimeUnit.SECONDS))
                        val context = io.mazewall.enforcer.internal.supervisor
                            .SupervisorContext("", directory, child, Thread())
                        val configuration = io.mazewall.enforcer.internal.execution.ConfinedHelperConfiguration(
                            CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, policy),
                            context,
                            emptyList(),
                            HelperSupportLifetime.NoWriters,
                            HelperSecurityObservation(),
                        )
                        val legacy = io.mazewall.enforcer.internal.execution.FrozenStackScoping
                            .Legacy(io.mazewall.enforcer.api.DefaultStacktraceScopingPolicy)
                        val method = ManagedRuntime::class.java
                            .getDeclaredMethod(
                            "prepareDefinition",
                            io.mazewall.enforcer.api.PolicyDefinition::class.java,
                            DomainLimits::class.java,
                            io.mazewall.enforcer.api.ReviewedOperationArtifact::class.java,
                            PreparedOperationResources::class.java,
                            legacy.javaClass,
                            io.mazewall.enforcer.internal.execution.ConfinedHelperConfiguration::class.java,
                        ).apply { isAccessible = true }
                        try {
                            method.invoke(runtime, policy.definition, DomainLimits(1, 1), null, null, legacy, configuration)
                        } catch (failure: java.lang.reflect.InvocationTargetException) {
                            throw checkNotNull(failure.cause)
                        }
                    } finally {
                        if (child.isAlive) child.destroyForcibly()
                        check(child.waitFor(5, TimeUnit.SECONDS))
                    }
                }
            }.exceptionOrNull()
            check(failure === original) { "actual manager must propagate the exact acquisition Error: $failure" }
            check(stored == 1 && before == 1) { "actual stored token and rollback boundary must be reached" }
            check(closes.get() == if (unknown) 1 else 0) { "unexpected automatic ruleset close count" }
            val control = ManagedRuntime::class.java
                .getDeclaredField("legacyControl")
                .apply { isAccessible = true }
                .get(runtime)
            val slot = control.javaClass
                .getDeclaredField("active")
                .apply { isAccessible = true }
                .get(control)
            check(slot != null) { "actual runtime must retain the original failed acquisition" }
            val rejected = runCatching { runtime.prepare(policy, DomainLimits(1, 1)) }.exceptionOrNull()
            check(rejected is IllegalStateException && stored == 1) { "retained original acquisition must reject later claims" }
            val direct = runCatching { carrierClass.getDeclaredMethod("acquireAndCommit").invoke(slot) }.exceptionOrNull()
            check(direct is java.lang.reflect.InvocationTargetException && direct.cause is IllegalStateException && closes.get() == if (unknown) 1 else 0) {
                "foreign thread cannot invoke original carrier effects"
            }
            val immediate = runtime.drain(java.time.Duration.ZERO)
            check(immediate is DrainReport.Incomplete && immediate.liveWorkers == 1 && immediate.unfinishedInvocations == 0 && immediate.cleanupFailure === original) {
                "zero-deadline drain must count the physically live original manager without inventing an unfinished invocation: $immediate"
            }
            if (!unknown) {
                val await = carrierClass.getDeclaredMethod("awaitCleanup", io.mazewall.enforcer.internal.execution.Deadline::class.java, Any::class.java).apply { isAccessible = true }
                check(
                    await.invoke(
                        slot,
                        io.mazewall.enforcer.internal.execution
                        .Deadline(java.time.Duration.ofSeconds(1)),
                        Any(),
                    ) == false,
                ) { "direct carrier call without a live drain must not reserve cleanup" }
                check(before == 1 && closes.get() == 0) { "expired drain must not dispatch a retry" }
            }
            val pins = slot.javaClass
                .getDeclaredField("localResources")
                .apply { isAccessible = true }
                .get(slot) as PreparedResourceSet
            check(pins.grants.all { it.path.descriptor.isLiveForIo() }) { "original pins must remain owned until ruleset close is confirmed" }
            val reused = mutableListOf<PreparedPath>()
            try {
            if (unknown) {
                repeat(128) {
                    if (reused.none { it.descriptor.value == checkNotNull(token.get()).value }) reused.add(PreparedPath.open(path, originalNative))
                }
                check(reused.any { it.descriptor.value == checkNotNull(token.get()).value }) { "test requires a real newly owned descriptor to reuse the old number" }
            } else {
                check(checkNotNull(token.get()).isLiveForIo()) { "no close was dispatched before explicit drain" }
            }
            val result = if (concurrent) {
                val pool = java.util.concurrent.Executors
                    .newFixedThreadPool(2)
                try {
                    val first = pool.submit<DrainReport> { runtime.drain(java.time.Duration.ofSeconds(5)) }
                    check(enteredSweep.await(5, TimeUnit.SECONDS))
                    val secondStarted = java.util.concurrent.CountDownLatch(1)
                    val second = pool.submit<DrainReport> {
                        secondStarted.countDown()
                        runtime.drain(java.time.Duration.ofSeconds(5))
                    }
                    check(secondStarted.await(5, TimeUnit.SECONDS))
                    val forgedAwait = carrierClass.getDeclaredMethod("awaitCleanup", io.mazewall.enforcer.internal.execution.Deadline::class.java, Any::class.java).apply { isAccessible = true }
                    check(
                        forgedAwait.invoke(
                            slot,
                            io.mazewall.enforcer.internal.execution
                            .Deadline(java.time.Duration.ofSeconds(10)),
                                Any(),
                        ) == false,
                    ) { "foreign deadline and permit cannot enter a live drain cohort" }
                    check(before == 2 && closes.get() == 0)
                    releaseSweep.countDown()
                    val firstResult = first.get(5, TimeUnit.SECONDS)
                    check(second.get(5, TimeUnit.SECONDS) is DrainReport.Incomplete)
                    firstResult
                } finally {
                    releaseSweep.countDown()
                    pool.shutdown()
                    check(pool.awaitTermination(5, TimeUnit.SECONDS))
                }
            } else {
                runtime.drain(java.time.Duration.ofSeconds(5))
            }
            check(result is DrainReport.Incomplete && result.cleanupFailure === original) { "drain must preserve original fatal history" }
            if (suppressionDisabled) {
                val retainedCleanup =
                    slot.javaClass
                        .getDeclaredField("rawCleanupFailure")
                        .apply { isAccessible = true }
                        .get(slot)
                check(retainedCleanup === beforeDispatch) { "actual distinct close Error must remain on the original carrier" }
                check(original.suppressed.none { it === beforeDispatch }) { "suppression-disabled original must not invent attachment" }
            }
            check(result.liveWorkers == 0 && result.unfinishedInvocations == 0) { "physical retirement must not retain fabricated live counts: $result" }
            val snapshot = carrierClass.getDeclaredMethod("retirementSnapshot").apply { isAccessible = true }.invoke(slot) as io.mazewall.enforcer.internal.execution.DomainDrainSnapshot
            check(snapshot.retired == !unknown) { "only authoritative completion may mark the retained owner retired" }
            check(closes.get() == 1) { "exactly one ruleset close must ever dispatch" }
            if (!unknown) check(before == 2 && !checkNotNull(token.get()).isLiveForIo()) { "explicit drain must perform exactly the first close" }
            check(!checkNotNull(manager.get()).isAlive) { "original manager must physically terminate after settlement" }
            check(runtime.drain(java.time.Duration.ofSeconds(5)) is DrainReport.Incomplete && closes.get() == 1)
            if (unknown) check(pins.grants.all { it.path.descriptor.isLiveForIo() }) { "unknown ruleset close must retain every original pin" }
            } finally {
                reused.forEach { it.close() }
            }
        } finally {
            releaseSweep.countDown()
            runtime.drain(java.time.Duration.ofSeconds(5))
            LinuxNative.setEngine(originalNative.engineIdentity as NativeEngine)
        }
    }

    private fun plan(directory: Path) {
        val application = Files.createDirectory(directory.resolve("application"))
        val support = Files.writeString(directory.resolve("runtime.jar"), "reviewed-runtime")
        val real = LinuxNative.snapshot()
        val failure = IllegalStateException("before support close")
        var armed = false
        var selected: FileDescriptor<*, FdState.Open, FdOwnership.Owned>? = null
        val attempts = mutableListOf<Int>()
        val native = object : NativeEngine by real {
            override val fileSystem = object : NativeFileSystem by real.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    attempts.add(fd.value)
                    if (armed) {
                        armed = false
                        selected = fd
                        throw failure
                    }
                    return real.fileSystem.close(fd)
                }
            }
        }
        val policy = CanonicalPolicyArtifact.worker(
            RuntimeProfile.HOTSPOT_JIT,
            WorkerPolicyBuilder
                .denyList(RuntimeProfile.HOTSPOT_JIT)
                .allowFsRead(application)
                .allowFsRead(support)
                .build(),
        )
        val observation = HelperSecurityObservation().observeAncestry(Pid(ProcessHandle.current().pid().toInt()), native.process.gettid(), HelperSecurityObservation.Lineage.Bootstrap)
        val resources = PreparedResourceSet.capture(policy.definition, native)
        val borrowed = resources.grants.map { it.path.descriptor }

        fun sha(bytes: ByteArray) = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        val binding = HelperBinding(observation.identity.pid, observation.identity.startTimeTicks, 1, resources.generation, sha(policy.toJson().toByteArray()))
        val plan = PreparedHelperReadPlan.capture(
            policy,
            resources,
            binding,
            listOf(ReviewedHelperSupport(support, sha("reviewed-runtime".toByteArray()))),
            HelperSupportLifetime.NoWriters,
            native,
            observation.ancestry,
        )
        armed = true
        check(runCatching { plan.close() }.exceptionOrNull() === failure)
        val retained = checkNotNull(selected)
        check(retained.isLiveForIo())
        check(borrowed.all { it.isLiveForIo() })
        plan.close()
        check(!retained.isLiveForIo()) { "plan must retry the retained support owner" }
        check(attempts.count { it == retained.value } == 2)
        check(borrowed.all { it.isLiveForIo() })
        resources.close()
    }

    private fun operation(
        directory: Path,
        unknown: Boolean,
    ) {
        val source = Files.writeString(directory.resolve("Operation.java"), "package fixture; public class Operation { public int call() { return 42; } }")
        check(ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", directory.toString(), source.toString()) == 0)
        val jar = directory.resolve("operation.jar")
        JarOutputStream(Files.newOutputStream(jar)).use {
            it.putNextEntry(JarEntry("fixture/Operation.class"))
            it.write(Files.readAllBytes(directory.resolve("fixture/Operation.class")))
            it.closeEntry()
        }
        val real = LinuxNative.snapshot()
        val failure = IllegalStateException(if (unknown) "grant close returned no result" else "before grant close")
        var armed = false
        var selected: FileDescriptor<*, FdState.Open, FdOwnership.Owned>? = null
        val attempts = mutableListOf<Int>()
        val native = object : NativeEngine by real {
            override val fileSystem = object : NativeFileSystem by real.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    attempts.add(fd.value)
                    if (armed) {
                        armed = false
                        selected = fd
                        if (unknown) real.fileSystem.close(fd).getOrThrow("fixture grant close")
                        throw failure
                    }
                    return real.fileSystem.close(fd)
                }
            }
        }
        LinuxNative.setEngine(native)
        try {
            URLClassLoader(arrayOf(jar.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
                val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(jar).build()
                val owner = PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, policy, loader.loadClass("fixture.Operation"), emptySet())
                armed = true
                check(runCatching { owner.retire() }.exceptionOrNull() === failure)
                val retained = checkNotNull(selected)
                check(attempts.size > 1) { "independent grants and artifact must be attempted" }
                if (unknown) {
                    check(!retained.isLiveForIo())
                    check(runCatching { owner.retire() }.exceptionOrNull() === failure) { "second retirement must retain the original close failure" }
                    check(runCatching { owner.close() }.exceptionOrNull() === failure) { "public close must retain the original close failure" }
                    check(attempts.count { it == retained.value } == 1) { "the dispatched grant close must never replay" }
                } else {
                    check(retained.isLiveForIo())
                    owner.retire()
                    check(!retained.isLiveForIo()) { "operation owner must retry the retained grant" }
                    check(attempts.count { it == retained.value } == 2)
                }
            }
        } finally {
            LinuxNative.resetToDefault()
        }
    }

    private fun landlock(directory: Path) {
        val path = Files.writeString(directory.resolve("grant"), "grant")
        val real = LinuxNative.snapshot()
        val failure = IllegalStateException("before ruleset close")
        var target = -1
        var armed = false
        val attempts = mutableListOf<Int>()
        val native = object : NativeEngine by real {
            override val fileSystem = object : NativeFileSystem by real.fileSystem {
                override fun close(fd: FileDescriptor<*, FdState.Open, FdOwnership.Owned>): LinuxNative.SyscallResult<Long, LinuxNative.SyscallHandledState.Unhandled> {
                    attempts.add(fd.value)
                    if (armed && fd.value == target) {
                        armed = false
                        throw failure
                    }
                    return real.fileSystem.close(fd)
                }
            }
        }
        val outcome = NativeArena.ofConfined().use { arena ->
            with(arena) { Landlock.tryCreateRuleset(Landlock.LANDLOCK_ACCESS_FS_READ_FILE, 0L, 1, native) }
        }
        check(outcome is LandlockFdOutcome.Ok) { "Landlock ruleset unavailable: $outcome" }
        val ruleset = LandlockRuleset<RulesetState.Building>(outcome.fd, native)
        target = outcome.fd.value
        val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(path).build()
        val resources = PreparedResourceSet.capture(policy.definition, native)
        val constructor = PreparedLandlock::class.java.getDeclaredConstructor(PreparedResourceSet::class.java, LandlockRuleset::class.java)
        constructor.isAccessible = true
        val owner = constructor.newInstance(resources, ruleset)
        armed = true
        check(runCatching { owner.close() }.exceptionOrNull() === failure)
        check(outcome.fd.isLiveForIo()) { "predispatch ruleset close must retain its owner" }
        check(resources.grants.all { !it.path.descriptor.isLiveForIo() })
        owner.close()
        check(!outcome.fd.isLiveForIo())
        check(attempts.count { it == target } == 2)
    }
}
