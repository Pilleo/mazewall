package io.mazewall

import io.mazewall.core.ProcessLauncher
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute

public open class MockProcessLauncher(
    public var mockProcess: Process = MockProcess(9999L)
) : ProcessLauncher {
    public var startProcessCalled: Boolean = false
    public var lastArgs: List<String>? = null
    public val shutdownHooks: MutableList<Thread> = mutableListOf()

    override fun startProcess(args: List<String>, redirectErrorStream: Boolean): Process {
        startProcessCalled = true
        lastArgs = args
        return mockProcess
    }

    override fun addShutdownHook(hook: Thread) {
        shutdownHooks.add(hook)
    }

    override fun removeShutdownHook(hook: Thread) {
        shutdownHooks.remove(hook)
    }

    override fun createTempDirectory(prefix: String, vararg attrs: FileAttribute<*>): Path {
        return java.nio.file.Paths.get("/tmp/mock-dir")
    }

    override fun createTempDirectory(dir: Path, prefix: String, vararg attrs: FileAttribute<*>): Path {
        return java.nio.file.Paths.get("/tmp/fallback-mock-dir")
    }

    override fun deleteIfExists(path: Path): Boolean = true
    override fun exists(path: Path): Boolean = true
}
