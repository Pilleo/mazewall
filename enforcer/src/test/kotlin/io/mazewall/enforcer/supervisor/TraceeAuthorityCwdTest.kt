package io.mazewall.enforcer.supervisor

import io.mazewall.LinuxNative
import io.mazewall.core.OpenFlags
import io.mazewall.core.Tid
import io.mazewall.ffi.memory.NativeArena
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class TraceeAuthorityCwdTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `relative open uses tracee cwd and preserves that directory after rename`() {
        val cwd = Files.createDirectory(directory.resolve("cwd"))
        Files.writeString(cwd.resolve("authority-target"), "tracee contents")
        val tracee = ProcessBuilder("sleep", "30").directory(cwd.toFile()).start()
        try {
            Files.move(cwd, directory.resolve("moved"))
            NativeArena.ofConfined().use { arena ->
                with(arena) {
                    val effect = OpenRouteExecutor(LinuxNative, TraceeDescriptorImporter(LinuxNative)).execute(
                        SupervisedOpen.OpenAt(TraceeDirFd.CurrentWorkingDirectory, "authority-target", OpenFlags(0)),
                        Tid(tracee.pid().toInt()),
                    )
                    val opened = assertInstanceOf(SupervisorReplyEffect.AddFdAndReply::class.java, effect)
                    opened.descriptor.use {
                        assertEquals("tracee contents", Files.readString(Path.of("/proc/self/fd/${it.handle.value}")))
                    }
                }
            }
        } finally {
            tracee.destroyForcibly()
            assertTrue(tracee.waitFor(5, TimeUnit.SECONDS))
        }
    }
}
