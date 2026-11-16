package io.mazewall.enforcer.internal.supervisor

import io.mazewall.platform.process.ChildStdoutPump
import io.mazewall.platform.process.RealProcessLauncher
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Test-only isolated owner; it returns normally so the JVM starts the original hook. */
internal object ConfinedRealHookChild {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3)
        val directory = Path.of(args[0]).toAbsolutePath()
        val token = args[1]
        val mode = args[2]
        require(mode == "natural" || mode == "stop")
        require(
            System
                .getProperty("java.class.path")
                .split(java.io.File.pathSeparator)
                .all {
                    val path = Path.of(it)
                    path.isAbsolute && Files.isRegularFile(path)
                },
        )
        val manager = SupervisorDaemonManager(processLauncher = RealProcessLauncher)
        val context = manager.prepareConfinedReadBootstrap()
        val helper = checkNotNull(context.confinedHelper)
        val resourcesField = manager.javaClass.getDeclaredField("ownedResources").apply { isAccessible = true }
        val resources = checkNotNull(resourcesField.get(manager))
        val pumpField = resources.javaClass.getDeclaredField("pump").apply { isAccessible = true }
        val pump = (checkNotNull(pumpField.get(resources)) as ChildStdoutPump).thread
        val hook = context.shutdownHook
        val hookFailure = AtomicReference<Throwable?>()
        hook.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, failure -> hookFailure.compareAndSet(null, failure) }
        val daemonPid = context.daemonProcess.pid()
        val helperPid = helper.identity
            .pid
            .value
            .toLong()
        val daemonStart = ProofReceipt.startTicks(daemonPid)
        val helperStart = ProofReceipt.startTicks(helperPid)
        check(helperStart == helper.identity.startTimeTicks)
        if (mode == "natural") {
            Runtime.getRuntime().addShutdownHook(
                Thread({
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                    pump.join(20_000)
                    check(pump.state == Thread.State.TERMINATED) { "pump did not terminate" }
                    check(hook.state != Thread.State.NEW) { "original hook did not start" }
                    val remaining = deadline - System.nanoTime()
                    if (hook.isAlive && remaining > 0) hook.join(maxOf(1L, TimeUnit.NANOSECONDS.toMillis(remaining)))
                    check(hook.state == Thread.State.TERMINATED) { "original hook did not terminate" }
                    ProofReceipt.write(
                        directory,
                        "witness",
                        mapOf(
                            "version" to "1",
                            "token" to token,
                            "mode" to mode,
                            "hookId" to hook.threadId().toString(),
                            "hookState" to hook.state.name,
                            "hookFailure" to (hookFailure.get()?.javaClass?.name ?: "none"),
                            "pumpId" to pump.threadId().toString(),
                            "pumpState" to pump.state.name,
                        ),
                    )
                }, "confined-hook-test-witness"),
            )
        }
        val release = ProofReceipt.watch(directory).use { watcher ->
            ProofReceipt.write(
                directory,
                "ready",
                mapOf(
                    "version" to "1",
                    "token" to token,
                    "mode" to mode,
                    "ownerPid" to ProcessHandle.current().pid().toString(),
                    "ownerStart" to ProofReceipt.startTicks(ProcessHandle.current().pid()).toString(),
                    "daemonPid" to daemonPid.toString(),
                    "daemonStart" to daemonStart.toString(),
                    "helperPid" to helperPid.toString(),
                    "helperStart" to helperStart.toString(),
                    "socket" to context.socketPath,
                    "directory" to context.socketDir.toString(),
                    "hookId" to hook.threadId().toString(),
                    "pumpId" to pump.threadId().toString(),
                ),
            )
            ProofReceipt.await(directory.resolve("release"), 30, watcher)
        }
        ProofReceipt.requireKeys(release, setOf("version", "token", "mode"), token)
        check(release["mode"] == mode)
        if (mode == "stop") {
            manager.stop()
            val removedByProbe = Runtime.getRuntime().removeShutdownHook(hook)
            pump.join(10_000)
            check(pump.state == Thread.State.TERMINATED) { "pump did not terminate" }
            ProofReceipt.write(
                directory,
                "stopped",
                mapOf(
                    "version" to "1",
                    "token" to token,
                    "mode" to mode,
                    "hookId" to hook.threadId().toString(),
                    "hookState" to hook.state.name,
                    "hookFailure" to (hookFailure.get()?.javaClass?.name ?: "none"),
                    "pumpId" to pump.threadId().toString(),
                    "pumpState" to pump.state.name,
                    "removedByProbe" to removedByProbe.toString(),
                    "stopReturned" to "true",
                ),
            )
        }
    }
}

internal object ProofReceipt {
    fun startTicks(pid: Long): Long {
        val row = Files.readString(Path.of("/proc", pid.toString(), "stat"))
        val end = row.lastIndexOf(')')
        require(end >= 0)
        return row
            .substring(end + 1)
            .trim()
            .split(Regex("\\s+"))[19]
            .toLong()
    }

    fun write(
        directory: Path,
        name: String,
        fields: Map<String, String>,
    ) {
        require(fields.all { (key, value) -> key.isNotEmpty() && '=' !in key && '\n' !in value && '\r' !in value })
        val bytes = fields.entries
            .joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" }
            .toByteArray(StandardCharsets.UTF_8)
        val temporary = directory.resolve("$name.tmp")
        Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        Files.createLink(directory.resolve(name), temporary)
    }

    fun watch(directory: Path): java.nio.file.WatchService {
        val watcher = directory.fileSystem.newWatchService()
        try {
            directory.register(watcher, java.nio.file.StandardWatchEventKinds.ENTRY_CREATE)
            return watcher
        } catch (failure: Throwable) {
            try {
                watcher.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    fun await(
        path: Path,
        seconds: Long,
        watcher: java.nio.file.WatchService? = null,
    ): Map<String, String> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        if (!Files.exists(path) && seconds > 0) {
            val owned = watcher ?: watch(path.parent)
            try {
                while (!Files.exists(path)) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) break
                    val key = owned.poll(remaining, TimeUnit.NANOSECONDS) ?: break
                    key.pollEvents()
                    check(key.reset()) { "receipt watch became invalid" }
                }
            } finally {
                if (watcher == null) owned.close()
            }
        }
        check(Files.isRegularFile(path)) { "Missing receipt $path" }
        val result = linkedMapOf<String, String>()
        Files.readAllLines(path).forEach { line ->
            val split = line.indexOf('=')
            require(split > 0 && result.put(line.substring(0, split), line.substring(split + 1)) == null)
        }
        return result
    }

    fun requireKeys(
        fields: Map<String, String>,
        keys: Set<String>,
        token: String,
    ) {
        require(fields.keys == keys && fields["version"] == "1" && fields["token"] == token)
    }
}
