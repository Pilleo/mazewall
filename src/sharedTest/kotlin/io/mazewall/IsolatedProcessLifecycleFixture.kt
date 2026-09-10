package io.mazewall

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.LockSupport

/** Child-only fixture for lifecycle regressions in [IsolatedProcessTester]. */
object IsolatedProcessLifecycleFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        val mode = args[0]
        val root = Path.of(args[1])
        Files.writeString(root.resolve("parent.pid"), ProcessHandle.current().pid().toString())
        if (mode == "exit") return
        val child = ProcessBuilder("/bin/sh", "-c", "trap '' TERM; echo \$\$ > '${root.resolve("child.pid")}'; while :; do sleep 1; done").start()
        Files.writeString(root.resolve("ready"), child.pid().toString())
        while (true) LockSupport.parkNanos(1_000_000_000L)
    }
}
