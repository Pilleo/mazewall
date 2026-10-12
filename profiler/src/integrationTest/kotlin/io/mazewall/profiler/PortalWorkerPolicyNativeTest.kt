package io.mazewall.profiler.api

import io.mazewall.enforcer.api.BaseIntegrationTest
import io.mazewall.enforcer.api.CanonicalPolicyArtifact
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.enforcer.api.NeedsFreshJvm
import io.mazewall.enforcer.api.Platform
import io.mazewall.enforcer.api.Policy
import io.mazewall.enforcer.api.PolicyDefinition
import io.mazewall.enforcer.api.PolicyScope
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.Uncompiled
import io.mazewall.enforcer.api.execution.PreparedOperationResources
import io.mazewall.enforcer.engine.JvmFloorPresets
import io.mazewall.enforcer.internal.seccomp.BpfFilter
import io.mazewall.ffi.NativeConstants
import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.platform.fd.ResourceRetirement
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.ChildStartupTransaction
import io.mazewall.platform.process.JavaAgentSelection
import io.mazewall.platform.process.JvmChildProcess
import io.mazewall.platform.process.JvmChildSpec
import io.mazewall.platform.process.RealProcessLauncher
import io.mazewall.platform.syscall.Arch
import io.mazewall.platform.syscall.Syscall
import io.mazewall.seccomp.BpfSimulator
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real collection and all-thread installation, never a synthetic complete result. */
@NeedsFreshJvm
class PortalWorkerPolicyNativeTest : BaseIntegrationTest() {
    private enum class Stage { PROFILE, INSTALL }

    @Test
    fun `actual profile installs exact selected policies before target JIT and lazy work`(
        @TempDir temporary: Path,
    ) {
        val directory = System.getProperty("mazewall.task5a.evidence")?.let(Path::of) ?: temporary
        Files.createDirectories(directory)
        val profiled = runOwnedChild(directory, Stage.PROFILE)
        assertTrue(profiled.any { it.startsWith("TASK5A_PROFILE_ENDED ") })
        val launch = profiled.single { it.startsWith("INFO: Spawning ProfilerDaemon:") }
        val socket = Path.of(launch.substringAfterLast(' '))
        assertFalse(Files.exists(socket), "The profiler infrastructure owner must release its actual endpoint before enforcement")
        assertFalse(Files.exists(socket.parent), "The actual private profiler directory must retire too")
        val policies = (0..3).map { Files.readString(directory.resolve("component-$it.json")) }
        val output = runOwnedChild(directory, Stage.INSTALL)
        assertEquals(policies, (0..3).map { Files.readString(directory.resolve("component-$it.json")) }, "Exact retained component bytes must survive installation")
        val installed = output.indexOfFirst { it.contains("TASK5A_INSTALLED ") }
        assertTrue(installed >= 0, output.joinToString("\n"))
        val compilation = output.indices.filter { output[it].contains("Task5aJitTarget::hot") && !output[it].contains("made not") }
        assertTrue(compilation.isNotEmpty(), "Must observe actual target compilation, not an MXBean total")
        assertTrue(compilation.all { it > installed }, "Target compilation must occur AFTER required installation")
        assertFalse(output.take(installed).any { it.contains("Task5aJitTarget") }, "Target must not be prewarmed")
        val log = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(directory.resolve("target-compilation.xml").toFile())
        val methods = log.getElementsByTagName("nmethod")
        val targetCode = (0 until methods.length)
            .map { methods.item(it) as Element }
            .filter { it.getAttribute("method") == "$TARGET hot (I)I" }
        assertTrue(targetCode.isNotEmpty(), "A queued compilation is insufficient: require actual installed target nmethod evidence")
        assertTrue(targetCode.all { it.getAttribute("size").toInt() > 0 && it.getAttribute("compiler") in setOf("c1", "c2") })
        Files.writeString(
            directory.resolve("target-nmethods.txt"),
            targetCode.joinToString("\n") {
            "method=${it.getAttribute("method")} compileId=${it.getAttribute("compile_id")} compiler=${it.getAttribute("compiler")} size=${it.getAttribute("size")} stamp=${it.getAttribute("stamp")}"
        } +
            "\n",
        )
        assertTrue(output.any { it.contains("TASK5A_RESULT ") })
        Files.writeString(directory.resolve("compilation-order.txt"), "installedLine=$installed\n" + compilation.joinToString("\n") { "$it: ${output[it]}" } + "\n")
    }

    private fun runOwnedChild(
        directory: Path,
        stage: Stage,
    ): List<String> {
        val mode = stage.name.lowercase()
        val started = System.nanoTime()
        var ended = 0L
        val output = CopyOnWriteArrayList<String>()
        val descendants = CopyOnWriteArrayList<ProcessHandle>()
        val pumpEnded = java.util.concurrent.CountDownLatch(1)
        val spec = JvmChildSpec(
            mainClass = javaClass.name,
            mainArgs = listOf(stage.name, directory.toString()),
            maxHeap = "128m",
            extraJvmArgs = when (stage) {
                Stage.PROFILE -> emptyList()
                Stage.INSTALL -> listOf(
                    "-XX:+PrintCompilation",
                    "-XX:+UnlockDiagnosticVMOptions",
                    "-XX:+LogCompilation",
                    "-XX:LogFile=${directory.resolve("target-compilation.xml")}",
                    "-Djdk.lang.Process.launchMechanism=FORK",
                )
            },
            javaAgents = JavaAgentSelection.None,
        )
        var child: Process? = null
        var pump: Thread? = null
        var failure: Throwable? = null
        Files.write(directory.resolve("$mode-child.argv"), JvmChildProcess.commandLine(spec))
        try {
            ChildStartupTransaction(RealProcessLauncher, "task5a-", "unused.sock").use { startup ->
                val process = startup.start(spec)
                child = process
                startup.startOutputPump(
                    "TASK5A_READY",
                    { line ->
                        output.add(line)
                        // Keep kernel ancestry scans off the high-volume JDK compilation-output path.
                        if (line.startsWith("TASK5A_") || line.startsWith("INFO: Spawning ProfilerDaemon:")) {
                            process.descendants().use { handles -> handles.forEach { if (it !in descendants) descendants.add(it) } }
                        }
                    },
                    "task5a-owned-output",
                    { pumpEnded.countDown() },
                )
                startup.registerShutdownHook()
                startup.awaitReady(10)
                val owner = startup.commit()
                pump = owner.pump?.thread
                owner.use {
                    process.outputStream.write(1)
                    process.outputStream.flush()
                    assertTrue(process.waitFor(90, TimeUnit.SECONDS), "Actual disposable JVM deadline")
                    ended = System.nanoTime()
                    assertTrue(pumpEnded.await(5, TimeUnit.SECONDS), "Output EOF before physical pump join")
                    assertEquals(0, process.exitValue(), output.joinToString("\n"))
                }
            }
        } catch (caught: Throwable) {
            failure = caught
        } finally {
            // The startup owner reaps the child and joins its pump even when an assertion fails.
            val cleanup = ResourceRetirement()
            cleanup.attempt { Files.write(directory.resolve("$mode-child-output.log"), output) }
            descendants.forEach { handle ->
                cleanup.attempt {
                if (handle.isAlive) {
                    handle.destroyForcibly()
                    handle.onExit().get(5, TimeUnit.SECONDS)
                }
            }
            }
            cleanup.attempt { assertTrue(child?.isAlive != true, "Disposable JVM must physically exit") }
            cleanup.attempt { assertTrue(pump?.isAlive != true, "Actual output pump must physically end") }
            cleanup.attempt { assertTrue(descendants.none(ProcessHandle::isAlive), "Observed daemon/helpers must physically end") }
            cleanup.attempt {
                Files.writeString(
                directory.resolve("$mode-physical-custody.txt"),
                "startedNanos=$started endedNanos=$ended retiredNanos=${System.nanoTime()}\n" +
                    "child=${child?.pid()} exit=${child?.exitValue()} alive=${child?.isAlive}\n" +
                    "pump=${pump?.threadId()} alive=${pump?.isAlive}\nobservedDescendants=${descendants.map {
                    "${it.pid()}:${it.isAlive}"
                }}\n",
            )
            }
            try {
                cleanup.finish()
            } catch (caught: Throwable) {
                failure = CleanupFailures.merge(failure, caught)
            }
        }
        failure?.let { throw it }
        return output.toList()
    }

    companion object {
        private const val TARGET = "io.mazewall.profiler.api.Task5aJitTarget"

        @JvmStatic
        fun main(args: Array<String>) {
            require(args.size == 2)
            val stage = Stage.valueOf(args[0])
            println("TASK5A_READY")
            System.out.flush()
            check(System.`in`.read() == 1)
            val directory = Path.of(args[1])
            when (stage) {
                Stage.PROFILE -> profileAndConvert(directory)
                Stage.INSTALL -> installAndExecute(directory)
            }
        }

        private fun profileAndConvert(directory: Path) {
            val native = LinuxNative.snapshot()
            val nativeIdentity = LinuxNative.engineIdentity
            assertTrue(LinuxNative.isRealEngineActive(), "Actual native kernel authority is required")
            check(Platform.configuredFallback() == Platform.FallbackBehavior.FAIL)
            check(Platform.featureMatrix.landlockTsyncSupported) { "Task5a requires actual Landlock ABI 8+; no thread-only substitution" }
            val worker = AtomicReference<Thread>()
            val priorThreads = Thread.getAllStackTraces().keys
            val collectors = AtomicReference<Map<Thread, Array<StackTraceElement>>>()
            val options = ProfileOptions(strategy = ProfileStrategy.USER_NOTIF, captureStacks = false, processWide = false)
            println("TASK5A_COLLECT_START native=${native.javaClass.name} abi=${Platform.featureMatrix.landlockAbiVersion}")
            val result = MazewallProfiler.open(options).use { profiler ->
                val initial = profiler.snapshot()
                assertFalse(initial.coverage.complete)
                assertFailsWith<IncompleteProfileException> { initial.toDedicatedWorkerPolicies(RuntimeProfile.HOTSPOT_JIT) }
                val measured = profiler.profile {
                    worker.set(Thread.currentThread())
                    collectors.set(observeCollectors(priorThreads))
                    println("TASK5A_WORKLOAD_ENTERED thread=${Thread.currentThread().threadId()}")
                    Files.readString(Path.of("/etc/hostname")) to referenceWork()
                }
                assertEquals(measured.coverage, profiler.snapshot().coverage)
                measured
            }
            Files.writeString(directory.resolve("profile.json"), result.toProfileDocumentJson())
            Files.writeString(directory.resolve("observations.txt"), result.observations.joinToString("\n"))
            assertFalse(worker.get().isAlive, "Delivered profile is not enough: actual worker must have ended")
            assertCollectorsEnded(collectors.get(), directory.resolve("profile-collectors.txt"))
            assertTrue(result.value.first.isNotBlank())
            assertTrue(result.observations.isNotEmpty())
            assertTrue("/etc/hostname" in result.behavior.opens)
            assertTrue(result.coverage.drainComplete)
            assertEquals(0, result.coverage.droppedEvents)
            assertEquals(ProfileStrategy.USER_NOTIF, result.coverage.strategy)
            assertFalse(result.coverage.processWide, "Completeness describes this synchronous observed thread scope")
            println("TASK5A_PROFILE_ENDED worker=${worker.get().threadId()} alive=${worker.get().isAlive} drain=${result.coverage.drainComplete} complete=${result.coverage.complete}")
            // Retain the real public refusal if the selected collector cannot certify this workload.
            val components = result.toDedicatedWorkerPolicies(RuntimeProfile.HOTSPOT_JIT).map(::workerPolicy).toTypedArray()
            val combined = PolicyDefinition.intersection(*components.map { it.definition }.toTypedArray())
            assertTrue(combined.allowMmapExec)
            JvmFloorPresets.fullJvmFloor().forEach { assertTrue(combined.isSyscallAllowed(it), "Missing final JVM floor: $it") }
            listOf(Syscall.EXECVE, Syscall.CONNECT, Syscall.IO_URING_SETUP, Syscall.IO_URING_ENTER).forEach { assertFalse(combined.isSyscallAllowed(it)) }
            val finalPolicy = Policy.restrictFurtherWith(*components)
            assertEquals(combined, finalPolicy.definition)
            components.forEachIndexed { index, policy ->
                Files.writeString(
                    directory.resolve("component-$index.json"),
                    CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, policy).toJson(),
                )
            }
            Files.writeString(directory.resolve("combined.json"), CanonicalPolicyArtifact.worker(RuntimeProfile.HOTSPOT_JIT, finalPolicy).toJson())
            Files.writeString(directory.resolve("expected-result.txt"), result.value.second.toString())
            nativeImageAndIncompleteControls(result, directory)
            Profiler.shutdown()
            assertTrue(LinuxNative.engineIdentity === nativeIdentity, "No ambient native-engine replacement")
            println("TASK5A_CONVERTED components=${components.size} runtime=HOTSPOT_JIT")
        }

        private fun installAndExecute(directory: Path) {
            // Deserialize the exact retained conversion, never reconstruct coverage or add permissions.
            val components = (0..3)
                .map { index ->
                val artifact = CanonicalPolicyArtifact.fromJson(Files.readString(directory.resolve("component-$index.json"))) as CanonicalPolicyArtifact.Worker
                assertEquals(RuntimeProfile.HOTSPOT_JIT, artifact.runtime)
                artifact.policy
            }.toTypedArray()
            val combined = PolicyDefinition.intersection(*components.map { it.definition }.toTypedArray())
            val expectedPolicy = CanonicalPolicyArtifact.fromJson(Files.readString(directory.resolve("combined.json")))
            assertEquals(expectedPolicy.definition, combined)
            assertTrue(combined.allowMmapExec)
            JvmFloorPresets.fullJvmFloor().forEach { assertTrue(combined.isSyscallAllowed(it), "Missing final JVM floor: $it") }
            val expectedResult = Files.readString(directory.resolve("expected-result.txt")).toLong()
            val nativeIdentity = LinuxNative.engineIdentity
            assertTrue(LinuxNative.isRealEngineActive())
            check(Platform.featureMatrix.landlockTsyncSupported)
            // All existing JVM/compiler threads receive the exact converted intersection.
            println("TASK5A_INSTALL_START mappings=${combined.allowMmapExec}")
            ContainedExecutors.installOnDedicatedProcess(*components).use { receipt ->
                receipt.requireInstalled()
                assertEquals(combined, receipt.requestedPolicy)
                assertTrue(receipt.processWide && receipt.installed && receipt.landlockApplied)
                assertEquals(Platform.FallbackBehavior.FAIL, receipt.fallback)
                assertTrue(LinuxNative.engineIdentity === nativeIdentity)
                println(
                    "TASK5A_INSTALLED processWide=${receipt.processWide} seccomp=${receipt.installed} landlock=${receipt.landlockApplied} fallback=${receipt.fallback} timestamp=${receipt.timestampMillis} native=${nativeIdentity.javaClass.name} abi=${Platform.featureMatrix.landlockAbiVersion} progress=${receipt.progress}",
                )
                System.out.flush()
                assertFailsWith<AccessDeniedException> { Files.readString(Path.of("/etc/hostname")) }
                val target = Class.forName(TARGET)
                val value = target.getMethod("run").invoke(null) as Long
                assertEquals(expectedResult, value)
                println("TASK5A_RESULT value=$value lazyClass=$TARGET lazyResource=task5a-lazy.txt receiptStillInstalled=${receipt.installed}")
            }
            println("TASK5A_RECEIPT_CLOSED filtersRemain=true")
        }

        private fun referenceWork(): Long {
            var result = 0L
            repeat(250_000) { value -> result += (value * 31 xor (value ushr 3)).toLong() }
            return result
        }

        private fun nativeImageAndIncompleteControls(
            result: ProfilingResult<*>,
            directory: Path,
        ) {
            val nativeComponents = result.toDedicatedWorkerPolicies(RuntimeProfile.NATIVE_IMAGE).map(::workerPolicy).toTypedArray()
            val nativeCombined = PolicyDefinition.intersection(*nativeComponents.map { it.definition }.toTypedArray())
            assertFalse(nativeCombined.allowMmapExec)
            val arch = Arch.current()
            val program = BpfFilter.build(arch, nativeCombined).instructions
            for (syscall in listOf(Syscall.MMAP, Syscall.MPROTECT)) {
                assertEquals(NativeConstants.SECCOMP_RET_ERRNO or 1, BpfSimulator.simulate(program, syscall.numberFor(arch), arch.audit, longArrayOf(0, 4096, 7, 0, 0, 0)))
                assertEquals(NativeConstants.SECCOMP_RET_ALLOW, BpfSimulator.simulate(program, syscall.numberFor(arch), arch.audit, longArrayOf(0, 4096, 3, 0, 0, 0)))
            }
            val nativePolicy = Policy.restrictFurtherWith(*nativeComponents)
            assertEquals(nativeCombined, nativePolicy.definition)
            Files.writeString(directory.resolve("native-image-combined.json"), CanonicalPolicyArtifact.worker(RuntimeProfile.NATIVE_IMAGE, nativePolicy).toJson())
            val contradiction = assertFailsWith<IllegalArgumentException> {
                PreparedOperationResources.resolve(RuntimeProfile.HOTSPOT_JIT, nativePolicy, PortalWorkerPolicyNativeTest::class.java, emptySet())
            }
            assertTrue(contradiction.message.orEmpty().contains("contradict"))
            val legacy = ProfilingResult(result.value, result.behavior, result.stackProfile)
            assertFailsWith<IncompleteProfileException> { legacy.toDedicatedWorkerPolicies(RuntimeProfile.HOTSPOT_JIT) }
            val log = directory.resolve("recorded.log")
            Files.writeString(log, "kind=uring tid=1 opcode=IORING_OP_NOP\n")
            MazewallProfiler.open(ProfileOptions(strategy = ProfileStrategy.USER_NOTIF, captureStacks = false, ebpfEventLog = log)).use { profiler ->
                val prior = Thread.getAllStackTraces().keys
                val worker = AtomicReference<Thread>()
                val collectors = AtomicReference<Map<Thread, Array<StackTraceElement>>>()
                val recorded = profiler.profile {
                    worker.set(Thread.currentThread())
                    collectors.set(observeCollectors(prior))
                    Files.readString(Path.of("/etc/hostname"))
                }
                assertFalse(worker.get().isAlive)
                assertCollectorsEnded(collectors.get(), directory.resolve("recorded-collectors.txt"))
                Files.writeString(directory.resolve("recorded-profile.json"), recorded.toProfileDocumentJson())
                assertFalse(recorded.coverage.complete || recorded.coverage.drainComplete)
                assertEquals(recorded.coverage, profiler.snapshot().coverage)
                assertFailsWith<IncompleteProfileException> { recorded.toDedicatedWorkerPolicies(RuntimeProfile.HOTSPOT_JIT) }
            }
            println("TASK5A_CONTROLS nativeImageMappings=false contradictionRejected=true legacyRejected=true recordedRejected=true")
        }

        @Suppress("UNCHECKED_CAST") // Both closed policy scopes are ThreadLocalOnly; the public component array is star-projected.
        private fun workerPolicy(policy: Policy<*, Uncompiled>): Policy<PolicyScope.ThreadLocalOnly, Uncompiled> = policy as Policy<PolicyScope.ThreadLocalOnly, Uncompiled>

        private fun observeCollectors(prior: Set<Thread>): Map<Thread, Array<StackTraceElement>> {
            val observed = Thread.getAllStackTraces().filter { (thread, frames) ->
                thread !in prior &&
                    (thread.name.startsWith("trace-listener-") || thread.name.startsWith("trace-collector-")) &&
                    frames.any { it.className.startsWith("io.mazewall.profiler.internal.ProfilerTraceListener") }
            }
            assertEquals(2, observed.size, "Capture the actual per-session listener/collector identities and source stacks")
            return observed
        }

        private fun assertCollectorsEnded(
            observed: Map<Thread, Array<StackTraceElement>>,
            path: Path,
        ) {
            assertTrue(observed.keys.none(Thread::isAlive), "Collected/drained evidence cannot substitute for actual collector end")
            Files.writeString(
                path,
                observed.entries.joinToString("\n") { (thread, frames) -> "id=${thread.threadId()} name=${thread.name} alive=${thread.isAlive}\n${frames.joinToString("\n")}" } + "\n",
            )
        }
    }
}
