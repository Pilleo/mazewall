package io.contained

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import java.net.Socket
import kotlin.test.assertTrue

@EnabledOnOs(OS.LINUX)
class SyscallProfilingTest {

    @Test
    fun `profiles file operations`() {
        if (!LibseccompNative.isAvailable) return

        val syscalls = SBoBGenerator.profile {
            val temp = File.createTempFile("sbob", "test")
            temp.writeText("hello world")
            temp.readText()
            temp.delete()
        }

        println("Discovered file syscalls: $syscalls")
        
        // Assert critical syscalls are present
        assertTrue(syscalls.contains("openat") || syscalls.contains("open"), "Should contain open/openat")
        assertTrue(syscalls.contains("write"), "Should contain write")
        assertTrue(syscalls.contains("read"), "Should contain read")
        assertTrue(syscalls.contains("close"), "Should contain close")
    }

    @Test
    fun `profiles network operations`() {
        if (!LibseccompNative.isAvailable) return

        val syscalls = SBoBGenerator.profile {
            try {
                val socket = Socket("example.com", 80)
                socket.close()
            } catch (e: Exception) {
                // Ignore connection errors, we just want to see the syscalls
            }
        }

        println("Discovered network syscalls: $syscalls")
        
        assertTrue(syscalls.contains("socket"), "Should contain socket")
        assertTrue(syscalls.contains("connect"), "Should contain connect")
    }
}
