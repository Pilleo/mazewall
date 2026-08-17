package io.mazewall

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach

/**
 * Base class for all integration tests requiring a live Linux environment
 * with Seccomp and Landlock support enabled.
 *
 * Tag a class with [NeedsFreshJvm] if it installs filters on the JUnit worker
 * (process-wide, current-thread, or a shared USER_NOTIF daemon). Untagged
 * tests share one JVM (`forkEvery = 0`).
 */
abstract class BaseIntegrationTest {
    @BeforeEach
    fun checkLinuxAndSupported() {
        Assumptions.assumeTrue(
            System.getProperty("os.name").equals("Linux", ignoreCase = true),
            "Only supported on Linux",
        )
        Assumptions.assumeTrue(
            Platform.isSupported(),
            "Platform/Kernel not supported (Seccomp/Landlock missing)",
        )
    }

    protected fun assumeLandlockAbiAtLeast(version: Int) {
        Assumptions.assumeTrue(
            io.mazewall.landlock.Landlock
                .isSupported(),
            "Landlock not supported",
        )
        Assumptions.assumeTrue(
            io.mazewall.landlock.Landlock
                .getAbiVersion() >= version,
            "Landlock ABI version must be at least $version (current: ${io.mazewall.landlock.Landlock.getAbiVersion()})",
        )
    }
}
