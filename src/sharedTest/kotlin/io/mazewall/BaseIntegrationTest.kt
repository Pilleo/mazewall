package io.mazewall

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeEach

/**
 * Base class for all integration tests requiring a live Linux environment
 * with Seccomp and Landlock support enabled.
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
