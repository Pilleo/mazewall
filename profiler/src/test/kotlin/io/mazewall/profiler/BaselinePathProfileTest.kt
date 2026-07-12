package io.mazewall.profiler

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BaselinePathProfileTest {

    @Test
    fun `test matches with exact paths`() {
        val profile = BaselinePathProfile(exactPaths = setOf("/etc/passwd", "/etc/hosts"))
        assertTrue(profile.matches("/etc/passwd"))
        assertTrue(profile.matches("/etc/hosts"))
        assertFalse(profile.matches("/etc/shadow"))
    }

    @Test
    fun `test matches with path prefixes`() {
        val profile = BaselinePathProfile(pathPrefixes = setOf("/proc/", "/sys/"))
        assertTrue(profile.matches("/proc/self/status"))
        assertTrue(profile.matches("/sys/kernel/debug"))
        assertFalse(profile.matches("/etc/passwd"))
    }

    @Test
    fun `test matches with combined criteria`() {
        val profile = BaselinePathProfile(
            exactPaths = setOf("/etc/ld.so.cache"),
            pathPrefixes = setOf("/lib/")
        )
        assertTrue(profile.matches("/etc/ld.so.cache"))
        assertTrue(profile.matches("/lib/libc.so.6"))
        assertFalse(profile.matches("/etc/passwd"))
    }

    @Test
    fun `jvmBootstrapNoise contains expected defaults`() {
        val noise = JvmBaselineProfiles.jvmBootstrapNoise()
        assertTrue(noise.matches("/etc/ld.so.cache"))
        assertTrue(noise.matches("/proc/self/maps"))
        assertTrue(noise.matches("/lib/x86_64-linux-gnu/libc.so.6"))

        val javaHome = System.getProperty("java.home")
        if (javaHome != null) {
            assertTrue(noise.matches("$javaHome/lib/modules"))
        }
    }
}
