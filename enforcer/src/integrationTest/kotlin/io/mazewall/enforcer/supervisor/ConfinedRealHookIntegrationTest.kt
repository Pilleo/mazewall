package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.IsolatedProcessTester
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.JvmChildProcess
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Observes the actual owner and its original hook before the parent cleanup boundary. */
internal class ConfinedRealHookIntegrationTest {
    @Test
    fun `natural shutdown runs the original confined hook before parent cleanup`() = runProof("natural")

    @Test
    fun `explicit stop deregisters the original confined hook before parent cleanup`() = runProof("stop")

    private fun runProof(mode: String) {
        val root = Files.createDirectories(Path.of("build/confined-hook-proof")).toAbsolutePath()
        val directory = Files.createTempDirectory(root, "proof-")
        val token = UUID.randomUUID().toString()
        val classpath = prepareClasspath(directory)
        val log = directory.resolve("owner.log")
        val command = listOf(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx128m",
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:-EnableJVMCI",
            "-XX:-UseJVMCICompiler",
            "-Djdk.lang.Process.launchMechanism=FORK",
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            classpath.joinToString(File.pathSeparator),
            ConfinedRealHookChild::class.java.name,
            directory.toString(),
            token,
            mode,
        )
        println("REAL_HOOK_ARGV ${command.joinToString(" ")}")
        val builder = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile())
        JvmChildProcess.stripInheritedJvmOptions(builder.environment())
        val readyWatch = ProofReceipt.watch(directory)
        val owner = try {
            builder.start()
        } catch (failure: Throwable) {
            try {
                readyWatch.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
        lateinit var ready: Map<String, String>
        lateinit var daemonExit: CompletableFuture<ProcessHandle>
        lateinit var helperExit: CompletableFuture<ProcessHandle>
        val observed = linkedMapOf<ProcessHandle, Long>()
        var originalFailure: Throwable? = null
        try {
            IsolatedProcessTester.awaitOwnedProcess(
                owner,
                Duration.ofSeconds(90),
                beforeWait = {
                    ready = awaitReady(owner, directory.resolve("ready"), observed, readyWatch)
                    ProofReceipt.requireKeys(
                        ready,
                        setOf(
                            "version",
                            "token",
                            "mode",
                            "ownerPid",
                            "ownerStart",
                            "daemonPid",
                            "daemonStart",
                            "helperPid",
                            "helperStart",
                            "socket",
                            "directory",
                            "hookId",
                            "pumpId",
                        ),
                        token,
                    )
                    check(ready["mode"] == mode)
                    val ownerPid = checkNotNull(ready["ownerPid"]).toLong()
                    val daemonPid = checkNotNull(ready["daemonPid"]).toLong()
                    val helperPid = checkNotNull(ready["helperPid"]).toLong()
                    check(owner.pid() == ownerPid && owner.isAlive)
                    val daemon = ProcessHandle.of(daemonPid).orElseThrow()
                    val helper = ProcessHandle.of(helperPid).orElseThrow()
                    check(daemon.isAlive && helper.isAlive)
                    check(ProofReceipt.startTicks(ownerPid) == checkNotNull(ready["ownerStart"]).toLong())
                    val daemonStart = checkNotNull(ready["daemonStart"]).toLong()
                    val helperStart = checkNotNull(ready["helperStart"]).toLong()
                    check(ProofReceipt.startTicks(daemonPid) == daemonStart)
                    check(ProofReceipt.startTicks(helperPid) == helperStart)
                    observed.putIfAbsent(daemon, daemonStart)
                    observed.putIfAbsent(helper, helperStart)
                    daemonExit = daemon.onExit()
                    helperExit = helper.onExit()
                    println("REAL_HOOK_READY $ready")
                    ProofReceipt.write(
                        directory,
                        "release",
                        mapOf("version" to "1", "token" to token, "mode" to mode),
                    )
                },
                waitCompleted = { completed ->
                    check(completed) { "owner did not exit naturally within the deadline" }
                    assertEquals(0, owner.exitValue())
                    val receiptName = if (mode == "natural") "witness" else "stopped"
                    val witness = ProofReceipt.await(directory.resolve(receiptName), 5)
                    val keys = mutableSetOf("version", "token", "mode", "hookId", "hookState", "hookFailure", "pumpId", "pumpState")
                    if (mode == "stop") keys.addAll(setOf("removedByProbe", "stopReturned"))
                    ProofReceipt.requireKeys(witness, keys, token)
                    assertEquals(mode, witness["mode"])
                    assertEquals(ready["hookId"], witness["hookId"])
                    assertEquals(ready["pumpId"], witness["pumpId"])
                    assertEquals(if (mode == "natural") "TERMINATED" else "NEW", witness["hookState"])
                    assertEquals("none", witness["hookFailure"])
                    assertEquals("TERMINATED", witness["pumpState"])
                    if (mode == "stop") {
                        assertEquals("true", witness["stopReturned"])
                        assertEquals("false", witness["removedByProbe"])
                    }
                    assertFalse(daemonExit.get(10, TimeUnit.SECONDS).isAlive)
                    assertFalse(helperExit.get(10, TimeUnit.SECONDS).isAlive)
                    observed.forEach { (handle, _) ->
                        check(!handle.isAlive) { "Observed descendant ${handle.pid()} remains alive before cleanup" }
                    }
                    val socket = Path.of(checkNotNull(ready["socket"]))
                    val endpoint = Path.of(checkNotNull(ready["directory"]))
                    check(socket.parent == endpoint)
                    assertFalse(Files.exists(socket))
                    assertFalse(Files.exists(endpoint))
                    println(
                        "REAL_HOOK_BEFORE_CLEANUP " +
                            "witness=$witness daemonExited=true helperExited=true socketAbsent=true directoryAbsent=true",
                    )
                },
            )
        } catch (failure: Throwable) {
            originalFailure = failure
            throw failure
        } finally {
            val interrupted = AtomicBoolean(Thread.interrupted())
            val failures = cleanupObserved(observed, interrupted)
            try {
                readyWatch.close()
            } catch (failure: Throwable) {
                failures.add(failure)
            }
            try {
                if (Files.isRegularFile(log)) println("REAL_HOOK_OWNER_LOG\n${Files.readString(log)}")
            } catch (failure: Throwable) {
                failures.add(failure)
            }
            if (interrupted.get()) Thread.currentThread().interrupt()
            val original = originalFailure ?: failures.firstOrNull()
            if (original != null) {
                failures.filter { it !== original }.forEach { failure ->
                    try {
                        original.addSuppressed(failure)
                    } catch (metadataFailure: Throwable) {
                        System.err.println("REAL_HOOK_CLEANUP_METADATA_FAILURE ${metadataFailure.javaClass.name}")
                    }
                }
                if (originalFailure == null) throw original
            }
        }
    }

    private fun awaitReady(
        owner: Process,
        receipt: Path,
        observed: MutableMap<ProcessHandle, Long>,
        watcher: java.nio.file.WatchService,
    ): Map<String, String> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)

        fun observe() {
            observeDescendants(owner.toHandle(), observed)
            observed.keys.toList().forEach { observeDescendants(it, observed) }
        }

        observe()
        while (!Files.exists(receipt) && System.nanoTime() < deadline) {
            check(owner.isAlive) { "owner exited before ready receipt" }
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break
            val key = watcher.poll(remaining, TimeUnit.NANOSECONDS) ?: break
            key.pollEvents()
            check(key.reset()) { "ready watch became invalid" }
            observe()
        }
        observe()
        println("REAL_HOOK_EARLY_DESCENDANTS ${observed.map { (handle, ticks) -> "${handle.pid()}:$ticks" }}")
        return ProofReceipt.await(receipt, 0)
    }

    private fun observeDescendants(
        owner: ProcessHandle,
        observed: MutableMap<ProcessHandle, Long>,
    ) {
        owner.descendants().use { descendants ->
            descendants.forEach { handle ->
                if (handle.isAlive && handle !in observed) observed[handle] = ProofReceipt.startTicks(handle.pid())
            }
        }
    }

    private fun cleanupObserved(
        observed: Map<ProcessHandle, Long>,
        interrupted: AtomicBoolean,
    ): MutableList<Throwable> {
        val failures = mutableListOf<Throwable>()
        observed.entries.toList().asReversed().forEach { (handle, ticks) ->
            if (handle.isAlive) {
                failures.add(IllegalStateException("Observed descendant ${handle.pid()} needed parent cleanup"))
                try {
                    check(ProofReceipt.startTicks(handle.pid()) == ticks) { "Observed descendant identity changed" }
                    handle.destroy()
                } catch (failure: Throwable) {
                    failures.add(failure)
                }
                awaitObserved(handle, interrupted, failures)
                if (handle.isAlive) {
                    try {
                        check(ProofReceipt.startTicks(handle.pid()) == ticks) { "Observed descendant identity changed" }
                        handle.destroyForcibly()
                    } catch (failure: Throwable) {
                        failures.add(failure)
                    }
                    awaitObserved(handle, interrupted, failures)
                }
                if (handle.isAlive) failures.add(IllegalStateException("Observed descendant did not retire"))
            }
        }
        return failures
    }

    private fun awaitObserved(
        handle: ProcessHandle,
        interrupted: AtomicBoolean,
        failures: MutableList<Throwable>,
    ) {
        try {
            handle.onExit().get(2, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            // The caller will attempt forced cleanup of this exact observed handle.
        } catch (failure: InterruptedException) {
            interrupted.set(true)
            Thread.interrupted()
            failures.add(failure)
        } catch (failure: Throwable) {
            failures.add(failure)
        }
    }

    private fun prepareClasspath(directory: Path): List<Path> {
        val types = listOf(
            ConfinedRealHookChild::class.java,
            SupervisorDaemon::class.java,
            SocketHelperMain::class.java,
            LinuxNative::class.java,
            Unit::class.java,
            kotlinx.serialization.KSerializer::class.java,
            kotlinx.serialization.json.Json::class.java,
            kotlinx.coroutines.CoroutineScope::class.java,
            org.slf4j.LoggerFactory::class.java,
        )
        val origins =
            types
                .map { type ->
                    val location = type.protectionDomain.codeSource.location
                    Path.of(location.toURI()).toRealPath()
                }.distinct()
        val entries = origins.mapIndexed { index, origin ->
            if (Files.isDirectory(origin)) {
                val jar = directory.resolve("compiled-$index.jar")
                JarOutputStream(Files.newOutputStream(jar)).use { output ->
                    Files.walk(origin).use { paths ->
                        paths.filter(Files::isRegularFile).sorted().forEach { path ->
                            val entry = JarEntry(origin.relativize(path).toString().replace(File.separatorChar, '/'))
                            entry.time = 0
                            output.putNextEntry(entry)
                            Files.copy(path, output)
                            output.closeEntry()
                        }
                    }
                }
                jar.toRealPath()
            } else {
                check(Files.isRegularFile(origin))
                origin
            }
        }
        val critical = listOf(
            "io/mazewall/enforcer/internal/supervisor/ConfinedRealHookChild.class",
            "io/mazewall/enforcer/internal/supervisor/SupervisorDaemon.class",
            "io/mazewall/enforcer/internal/supervisor/SocketHelperMain.class",
            "io/mazewall/platform/native/LinuxNative.class",
            "kotlin/Unit.class",
        )
        critical.forEach { name ->
            assertEquals(
                1,
                entries.count { entry -> JarFile(entry.toFile()).use { it.getEntry(name) != null } },
                "ambiguous class $name",
            )
        }
        entries.forEachIndexed { index, path ->
            assertTrue(path.isAbsolute && Files.isRegularFile(path))
            val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
            println("REAL_HOOK_CLASSPATH index=$index path=$path sha256=$digest")
        }
        return entries
    }
}
