package io.mazewall

import io.mazewall.core.Syscall
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("Security Baseline Documentation Specs")
class SecurityBaselineSpecTest {
    @Nested
    @DisplayName("Given PURE_COMPUTE preset")
    inner class PureCompute {
        private val policy = Policy.PURE_COMPUTE

        @Test
        @DisplayName("It should block all outbound network communication")
        fun blocksNetwork() {
            assertFalse(policy.isSyscallAllowed(Syscall.CONNECT))
            assertFalse(policy.isSyscallAllowed(Syscall.SENDTO))
        }

        @Test
        @DisplayName("It should block shell execution (execve)")
        fun blocksExec() {
            assertFalse(policy.isSyscallAllowed(Syscall.EXECVE))
            assertFalse(policy.isSyscallAllowed(Syscall.EXECVEAT))
        }

        @Test
        @DisplayName("It should allow JVM Classpath reads to prevent NoClassDefFoundError deadlocks")
        fun allowsClasspath() {
            assertTrue(policy.enforceLandlock)
            val javaHome = System.getProperty("java.home")
            if (!javaHome.isNullOrEmpty()) {
                val normalizedHome = java.nio.file.Paths.get(javaHome).toAbsolutePath().normalize().toString()
                assertTrue(policy.allowedFsReadPaths.map { it.value }.contains(normalizedHome))
            }
        }
    }

    @Nested
    @DisplayName("Given NO_EXEC preset")
    inner class NoExec {
        private val policy = Policy.NO_EXEC

        @Test
        @DisplayName("It should block execution but allow network and filesystem")
        fun blocksExecOnly() {
            assertFalse(policy.isSyscallAllowed(Syscall.EXECVE))
            assertTrue(policy.isSyscallAllowed(Syscall.CONNECT))
            assertTrue(policy.isSyscallAllowed(Syscall.OPEN))
        }

        @Test
        @DisplayName("It should block executable memory allocation (mmap PROT_EXEC) by default")
        fun blocksMmapExec() {
            assertFalse(policy.allowMmapExec)
        }
    }
}
