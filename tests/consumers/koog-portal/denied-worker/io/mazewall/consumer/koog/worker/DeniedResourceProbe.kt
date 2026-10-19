package io.mazewall.consumer.koog.worker

import io.mazewall.consumer.koog.DeniedResourceService
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Test-only protected implementation; never a host fallback or advertised tool. */
public class DeniedResourceProbe : DeniedResourceService {
    override fun read(path: String): String = Files.readString(Path.of(path))

    override fun write(path: String): String {
        Files.writeString(Path.of(path), "WORKER_WRITE")
        return "WROTE"
    }

    override fun connectLoopback(port: Int): String {
        require(port in 1..65_535) { "loopback port must be in 1..65535" }
        Socket().use { socket ->
            socket.soTimeout = 2_000
            socket.connect(InetSocketAddress("127.0.0.1", port), 2_000)
        }
        return "CONNECTED"
    }

    override fun spawnJavaVersion(): String {
        // Discard the fixed version output in a pipe: DISCARD opens /dev/null before spawning.
        val process = ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-version")
            .redirectErrorStream(true)
            .start()
        val execution = runCatching {
            process.inputStream.use {
                process.outputStream.use {
                    check(process.waitFor(5, TimeUnit.SECONDS)) { "worker java -version timed out" }
                    check(process.exitValue() == 0) { "worker java -version exited ${process.exitValue()}" }
                }
            }
        }
        val cleanup = runCatching {
            if (process.isAlive) process.destroyForcibly()
            check(process.waitFor(5, TimeUnit.SECONDS)) { "worker java -version cleanup timed out" }
            check(!process.isAlive) { "worker java -version remains alive" }
        }
        execution.exceptionOrNull()?.let { failure ->
            cleanup.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        cleanup.getOrThrow()
        return "SPAWNED"
    }

    override fun crash(): String {
        Runtime.getRuntime().halt(73)
        error("halt returned")
    }

    override fun waitForever(): String {
        while (true) Thread.sleep(60_000)
    }

    override fun oversizedText(): String = "x".repeat(65_537)

    override fun oversizedWire(): String = "x".repeat(131_073)
}
