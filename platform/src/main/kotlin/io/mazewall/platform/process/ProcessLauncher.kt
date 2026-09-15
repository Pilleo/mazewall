package io.mazewall.platform.process

import java.io.IOException
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute

/**
 * Interface for OS process and filesystem operations.
 * Decoupling this allows for mocking process spawning and file operations in tests.
 */
internal interface ProcessLauncher {
    fun startProcess(
        args: List<String>,
        redirectErrorStream: Boolean = true,
    ): Process

    fun addShutdownHook(hook: Thread)

    fun removeShutdownHook(hook: Thread)

    fun createTempDirectory(
        prefix: String,
        vararg attrs: FileAttribute<*>,
    ): Path

    fun createTempDirectory(
        dir: Path,
        prefix: String,
        vararg attrs: FileAttribute<*>,
    ): Path

    fun deleteIfExists(path: Path): Boolean

    fun exists(path: Path): Boolean
}

/**
 * Real implementation of [ProcessLauncher] using standard Java APIs.
 */
internal object RealProcessLauncher : ProcessLauncher {
    override fun startProcess(
        args: List<String>,
        redirectErrorStream: Boolean,
    ): Process {
        val pb = ProcessBuilder(args)
        pb.redirectErrorStream(redirectErrorStream)
        JvmChildProcess.stripInheritedJvmOptions(pb.environment())
        return pb.start()
    }

    override fun addShutdownHook(hook: Thread) {
        Runtime.getRuntime().addShutdownHook(hook)
    }

    override fun removeShutdownHook(hook: Thread) {
        Runtime.getRuntime().removeShutdownHook(hook)
    }

    override fun createTempDirectory(
        prefix: String,
        vararg attrs: FileAttribute<*>,
    ): Path {
        return java.nio.file.Files
            .createTempDirectory(prefix, *attrs)
    }

    override fun createTempDirectory(
        dir: Path,
        prefix: String,
        vararg attrs: FileAttribute<*>,
    ): Path {
        return java.nio.file.Files
            .createTempDirectory(dir, prefix, *attrs)
    }

    override fun deleteIfExists(path: Path): Boolean {
        return java.nio.file.Files
            .deleteIfExists(path)
    }

    override fun exists(path: Path): Boolean {
        return java.nio.file.Files
            .exists(path)
    }
}
