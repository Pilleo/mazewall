package io.mazewall.verification

import io.mazewall.enforcer.api.IsolatedProcessTester
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal class ConsumerCommandTimeout(
    val arguments: List<String>,
) : TimeoutException("Owned consumer command timed out: $arguments")

internal data class ConsumerCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
)

internal object ConsumerProcessEvidence {
    private enum class Phase(
        val wire: String,
    ) {
        BEFORE_LAUNCH("before-launch"),
        RUNNING("running"),
        TIMED_OUT("timed-out"),
        REAPED("reaped"),
        LAUNCH_FAILED("launch-failed"),
    }

    fun run(
        arguments: List<String>,
        directory: Path,
        label: String,
        timeout: Duration = Duration.ofSeconds(90),
        evidence: Path = directory,
        startPump: (Runnable) -> CompletableFuture<Void> = { CompletableFuture.runAsync(it) },
    ): ConsumerCommandResult {
        require(arguments.isNotEmpty() && Regex("[A-Za-z0-9_.-]+").matches(label)) { "Invalid consumer command/evidence label" }
        require(!timeout.isNegative && !timeout.isZero) { "Consumer command requires a positive timeout" }
        require(directory.isAbsolute && directory.toRealPath() == directory.normalize()) { "Consumer working directory must be absolute and unaliased" }
        require(evidence.isAbsolute && evidence.toRealPath() == evidence.normalize()) { "Consumer evidence directory must be absolute and unaliased" }
        ReceiptFile(evidence.resolve("$label.stdout.log")).use { stdout ->
            ReceiptFile(evidence.resolve("$label.stderr.log")).use { stderr ->
                ReceiptFile(evidence.resolve("$label.log")).use { combined ->
                    ReceiptFile(evidence.resolve("$label.command.json")).use { metadata ->
                        var child: Process? = null
                        var timedOut = false
                        var reaped = false
                        var failure: Throwable? = null
                        var pumps = emptyList<CompletableFuture<Void>>()
                        val transitions = mutableListOf<String>()

                        fun persist(phase: Phase) {
                            transitions.add(phase.wire)
                            metadata.replace(
                                buildJsonObject {
                                put("argv", buildJsonArray { arguments.forEach { add(JsonPrimitive(it)) } })
                                put("cwd", directory.toString())
                                put("pid", child?.pid()?.let(::JsonPrimitive) ?: JsonNull)
                                put("exitCode", child?.takeUnless { it.isAlive }?.exitValue()?.let(::JsonPrimitive) ?: JsonNull)
                                put("timedOut", timedOut)
                                put("reaped", reaped)
                                put("state", phase.wire)
                                put("transitions", buildJsonArray { transitions.forEach { add(JsonPrimitive(it)) } })
                            }.toString().toByteArray(),
                            )
                        }
                        persist(Phase.BEFORE_LAUNCH)
                        try {
                            val builder = ProcessBuilder(arguments).directory(directory.toFile())
                            gitLocalVariables.forEach(builder.environment()::remove)
                            val acquired = builder.start()
                            child = acquired
                            IsolatedProcessTester.awaitOwnedProcess(acquired, timeout, beforeWait = {
                                pumps = listOf(stdout.copy(acquired.inputStream, startPump), stderr.copy(acquired.errorStream, startPump))
                                acquired.outputStream.close()
                                persist(Phase.RUNNING)
                            }, waitCompleted = { completed ->
                                if (!completed) {
                                    timedOut = true
                                    persist(Phase.TIMED_OUT)
                                    throw ConsumerCommandTimeout(arguments)
                                }
                                // Normal-exit cleanup closes process streams, so drain evidence first.
                                pumps.forEach { it.get(5, TimeUnit.SECONDS) }
                            })
                        } catch (caught: Throwable) {
                            failure = caught
                        } finally {
                            fun attempt(action: () -> Unit) {
                                try {
                                    action()
                                } catch (cleanup: Throwable) {
                                    if (failure == null) {
                                        failure = cleanup
                                    } else if (failure !== cleanup) {
                                        failure!!.addSuppressed(cleanup)
                                    }
                                }
                            }
                            pumps.forEach { pump -> attempt { pump.get(5, TimeUnit.SECONDS) } }
                            reaped = child?.let { !it.isAlive } == true
                            attempt { combined.replace(stdout.bytes() + stderr.bytes()) }
                            attempt { persist(if (reaped) Phase.REAPED else Phase.LAUNCH_FAILED) }
                        }
                        failure?.let { throw it }
                        return ConsumerCommandResult(child!!.exitValue(), String(stdout.bytes(), Charsets.UTF_8), String(stderr.bytes(), Charsets.UTF_8))
                    }
                }
            }
        }
    }

    private val gitLocalVariables: List<String> by lazy {
        val names = ProcessBuilder("git", "rev-parse", "--local-env-vars").start()
        try {
            check(names.waitFor(30, TimeUnit.SECONDS) && names.exitValue() == 0) { "Cannot identify Git-local variables" }
            names.inputStream.bufferedReader().use { it.readLines() }
        } finally {
            if (names.isAlive) {
                names.destroyForcibly()
                check(names.waitFor(5, TimeUnit.SECONDS)) { "Git-local-variable child did not retire" }
            }
        }
    }

    /** Retain exclusive descriptors; child streams never reopen evidence pathnames. */
    private class ReceiptFile(
        private val path: Path,
    ) : AutoCloseable {
        private val channel = FileChannel.open(path, setOf(CREATE_NEW, READ, WRITE, NOFOLLOW_LINKS), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        private val key = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()

        private fun validate() {
            val named = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            check(named.isRegularFile && named.fileKey() == key && Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) == 1) { "Replaced/aliased evidence: $path" }
        }

        fun copy(
            input: InputStream,
            startPump: (Runnable) -> CompletableFuture<Void>,
        ): CompletableFuture<Void> =
            startPump(Runnable {
            input.use {
                val bytes = ByteArray(65536)
                while (true) {
                    val count = it.read(bytes)
                    if (count < 0) break
                    validate()
                    val buffer = ByteBuffer.wrap(bytes, 0, count)
                    while (buffer.hasRemaining()) channel.write(buffer)
                }
            }
        })

        fun replace(bytes: ByteArray) {
            validate()
            channel.position(0)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.truncate(bytes.size.toLong())
            channel.force(true)
        }

        fun bytes(): ByteArray {
            validate()
            require(channel.size() <= 64 * 1024 * 1024) { "Consumer evidence exceeds 64 MiB: $path" }
            val result = ByteBuffer.allocate(channel.size().toInt())
            var position = 0L
            while (result.hasRemaining()) {
                val count = channel.read(result, position)
                check(count > 0) { "Owned evidence shrank: $path" }
                position += count
            }
            return result.array()
        }

        override fun close() = channel.close()
    }
}
