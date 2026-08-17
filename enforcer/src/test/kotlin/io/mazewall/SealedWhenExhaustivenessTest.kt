package io.mazewall

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Kotlin still emits a synthetic default for exhaustive `when`. This scans the
 * transition machines so a new `else ->` cannot hide an unhandled sealed variant.
 */
class SealedWhenExhaustivenessTest {

    @Test
    fun `pure machines do not use else branches`() {
        val roots = listOf(
            Path.of("src/main/kotlin/io/mazewall/landlock/LandlockApplyResult.kt"),
            Path.of("enforcer/src/main/kotlin/io/mazewall/landlock/LandlockApplyResult.kt"),
            Path.of("../platform/src/main/kotlin/io/mazewall/platform/daemon/UnixListenDaemonMachine.kt"),
            Path.of("platform/src/main/kotlin/io/mazewall/platform/daemon/UnixListenDaemonMachine.kt"),
            Path.of("../platform/src/main/kotlin/io/mazewall/ffi/networking/SeccompConnectionMachine.kt"),
            Path.of("platform/src/main/kotlin/io/mazewall/ffi/networking/SeccompConnectionMachine.kt"),
        )
        val files = roots.filter { it.isRegularFile() }.distinct()
        assertTrue(files.size >= 3, "expected machine sources on disk, found $files")
        for (file in files) {
            val text = file.readText()
            assertTrue(
                !text.contains("else ->"),
                "${file.fileName} contains else -> ; sealed when must list every variant",
            )
        }
    }
}
