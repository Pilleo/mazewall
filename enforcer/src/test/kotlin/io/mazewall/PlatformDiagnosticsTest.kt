package io.mazewall

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals

class PlatformDiagnosticsTest {
    @TempDir
    lateinit var tempDir: File

    private var originalYamaPath: String = ""

    @BeforeEach
    fun setUp() {
        originalYamaPath = Platform.yamaPath
    }

    @AfterEach
    fun tearDown() {
        Platform.yamaPath = originalYamaPath
    }

    @Test
    fun `test yama scope parsing values`() {
        val yamaFile = File(tempDir, "ptrace_scope")
        Platform.yamaPath = yamaFile.absolutePath

        // Classic (0)
        yamaFile.writeText("0\n")
        var diag = Platform.diagnose()
        assertEquals(YamaPtraceScope.Classic, diag.yamaPtraceScope)

        // Restricted (1)
        yamaFile.writeText("1")
        diag = Platform.diagnose()
        assertEquals(YamaPtraceScope.Restricted, diag.yamaPtraceScope)

        // AdminOnly (2)
        yamaFile.writeText("  2  ")
        diag = Platform.diagnose()
        assertEquals(YamaPtraceScope.AdminOnly, diag.yamaPtraceScope)

        // Disabled (3)
        yamaFile.writeText("3")
        diag = Platform.diagnose()
        assertEquals(YamaPtraceScope.Disabled, diag.yamaPtraceScope)

        // Unknown (99)
        yamaFile.writeText("99")
        diag = Platform.diagnose()
        assertEquals(YamaPtraceScope.Unknown(99), diag.yamaPtraceScope)

        // Invalid content
        yamaFile.writeText("not-a-number")
        diag = Platform.diagnose()
        assertEquals(YamaPtraceScope.Unavailable, diag.yamaPtraceScope)
    }

    @Test
    fun `test yama scope unavailable when file missing`() {
        Platform.yamaPath = File(tempDir, "does-not-exist").absolutePath
        val diag = Platform.diagnose()
        assertEquals(YamaPtraceScope.Unavailable, diag.yamaPtraceScope)
    }
}
