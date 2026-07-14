package io.mazewall.core

import java.io.IOException
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute

/**
 * Interface for OS process and filesystem operations.
 * Decoupling this allows for mocking process spawning and file operations in tests.
 */
public interface ProcessLauncher {
    public fun startProcess(args: List<String>, redirectErrorStream: Boolean = true): Process

    public fun addShutdownHook(hook: Thread)

    public fun removeShutdownHook(hook: Thread)

    public fun createTempDirectory(prefix: String, vararg attrs: FileAttribute<*>): Path

    public fun deleteIfExists(path: Path): Boolean

    public fun exists(path: Path): Boolean
}

/**
 * Real implementation of [ProcessLauncher] using standard Java APIs.
 */
public object RealProcessLauncher : ProcessLauncher {
    override fun startProcess(args: List<String>, redirectErrorStream: Boolean): Process {
        val pb = ProcessBuilder(args)
        pb.redirectErrorStream(redirectErrorStream)
        return pb.start()
    }

    override fun addShutdownHook(hook: Thread) {
        Runtime.getRuntime().addShutdownHook(hook)
    }

    override fun removeShutdownHook(hook: Thread) {
        Runtime.getRuntime().removeShutdownHook(hook)
    }

    override fun createTempDirectory(prefix: String, vararg attrs: FileAttribute<*>): Path {
        return java.nio.file.Files.createTempDirectory(prefix, *attrs)
    }

    override fun deleteIfExists(path: Path): Boolean {
        return java.nio.file.Files.deleteIfExists(path)
    }

    override fun exists(path: Path): Boolean {
        return java.nio.file.Files.exists(path)
    }
}
