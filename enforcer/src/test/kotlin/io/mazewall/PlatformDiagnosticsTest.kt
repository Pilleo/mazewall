package io.mazewall

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Isolated
import java.io.File
import kotlin.test.assertEquals

@Isolated
class PlatformDiagnosticsTest {
    @TempDir
    lateinit var tempDir: File

    private var originalYamaPath: String = ""

    @BeforeEach
    fun setUp() {
        originalYamaPath = RealPlatformProvider.yamaPath
    }

    @AfterEach
    fun tearDown() {
        RealPlatformProvider.yamaPath = originalYamaPath
        Platform.resetToDefault()
    }

    @Test
    fun `test yama scope parsing values`() {
        val yamaFile = File(tempDir, "ptrace_scope")
        RealPlatformProvider.yamaPath = yamaFile.absolutePath

        // Classic (0)
        yamaFile.writeText("0\n")
        assertEquals(YamaPtraceScope.Classic, RealPlatformProvider.getYamaPtraceScope())

        // Restricted (1)
        yamaFile.writeText("1")
        assertEquals(YamaPtraceScope.Restricted, RealPlatformProvider.getYamaPtraceScope())

        // AdminOnly (2)
        yamaFile.writeText("  2  ")
        assertEquals(YamaPtraceScope.AdminOnly, RealPlatformProvider.getYamaPtraceScope())

        // Disabled (3)
        yamaFile.writeText("3")
        assertEquals(YamaPtraceScope.Disabled, RealPlatformProvider.getYamaPtraceScope())

        // Unknown (99)
        yamaFile.writeText("99")
        assertEquals(YamaPtraceScope.Unknown(99), RealPlatformProvider.getYamaPtraceScope())

        // Invalid content
        yamaFile.writeText("not-a-number")
        assertEquals(YamaPtraceScope.Unavailable, RealPlatformProvider.getYamaPtraceScope())
    }

    @Test
    fun `test yama scope unavailable when file missing`() {
        RealPlatformProvider.yamaPath = File(tempDir, "does-not-exist").absolutePath
        assertEquals(YamaPtraceScope.Unavailable, RealPlatformProvider.getYamaPtraceScope())
    }
}
