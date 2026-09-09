package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.core.JavaAgentSelection
import io.mazewall.core.JvmChildProcess
import io.mazewall.core.JvmChildSpec
import io.mazewall.ffi.memory.ManagedSegment
import io.mazewall.ffi.memory.NativeArena
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class SocketHelperOwnerDeathTest {
    @Test
    fun `helper refuses a missing original parent before opening IPC`() {
        val command = JvmChildProcess.commandLine(
            JvmChildSpec(SocketHelperMain::class.java.name, listOf("/nonexistent/helper.sock", "0"), javaAgents = JavaAgentSelection.None),
        )
        val helper = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        try {
            assertTrue(helper.waitFor(10, TimeUnit.SECONDS))
            assertEquals(125, helper.exitValue())
        } finally {
            helper.destroyForcibly()
        }
    }

    @Test
    fun `killing owner exits helper blocked in accept and releases imported listener`() {
        val command = JvmChildProcess.commandLine(JvmChildSpec(SocketHelperOwnerFixture::class.java.name, javaAgents = JavaAgentSelection.None))
        val owner = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        var helper: ProcessHandle? = null
        try {
            val identity = java.util.concurrent.CompletableFuture
                .supplyAsync { owner.inputReader().readLine() }
                .get(10, TimeUnit.SECONDS)
                .split(" ")
            helper = ProcessHandle.of(identity[0].toLong()).orElseThrow()
            val helperPath = Path.of("/proc", helper.pid().toString())
            val inode = identity[1]
            await("helper owns imported listener") { Files.list(helperPath.resolve("fd")).use { paths -> paths.anyMatch { Files.readSymbolicLink(it).toString() == inode } } }
            await("helper blocked in native accept") {
                Files.list(helperPath.resolve("task")).use { paths ->
                    paths.anyMatch { Files.readString(it.resolve("wchan")).contains("accept") }
                }
            }
            owner.destroyForcibly()
            assertTrue(owner.waitFor(5, TimeUnit.SECONDS))
            await("helper exited independently of blocked accept") { !Files.exists(helperPath) || Files.readString(helperPath.resolve("stat")).substringAfterLast(") ").startsWith("Z") }
            val socketInode = inode.removePrefix("socket:[").removeSuffix("]")
            await("imported listener released") { Files.readAllLines(Path.of("/proc/net/tcp")).none { it.trim().split(Regex("\\s+")).contains(socketInode) } }
        } finally {
            owner.destroyForcibly()
            owner.waitFor(5, TimeUnit.SECONDS)
            helper?.destroyForcibly()
        }
    }

    private fun await(
        description: String,
        condition: () -> Boolean,
    ) {
        io.mazewall.testing.awaitCondition(java.time.Duration.ofSeconds(8), description, condition)
    }
}

/** Fresh owner JVM intentionally killed while its child holds a blocked accept. */
object SocketHelperOwnerFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        SocketHelperProcess.start().use { helper ->
            val listener = (SupervisorDescriptorResult.from(LinuxNative.networking.socket(2, 1 or 524288, 0), LinuxNative) as SupervisorDescriptorResult.Opened).descriptor
            listener.use {
                NativeArena.ofConfined().use { arena ->
                    val address = arena.allocate(16)
                    ManagedSegment.copy(byteArrayOf(2, 0, 0, 0, 127, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0), 0, address, 0, 16)
                    check(LinuxNative.networking.bind(listener.handle, address, 16) is LinuxNative.SyscallResult.Success)
                    check(LinuxNative.networking.listen(listener.handle, 1) is LinuxNative.SyscallResult.Success)
                }
                val pid = ProcessHandle
                    .current()
                    .children()
                    .findFirst()
                    .orElseThrow()
                    .pid()
                val inode = Files.readSymbolicLink(Path.of("/proc/self/fd", listener.handle.value.toString()))
                println("$pid $inode")
                helper.execute(SocketOperation.Accept(0), listener)
            }
        }
    }
}
