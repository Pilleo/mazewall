package io.mazewall.lockdown

import io.mazewall.enforcer.api.NeedsFreshJvm
import io.mazewall.lockdown.internal.ffi.NativeConstants
import io.mazewall.lockdown.internal.platform.sys.LinuxNative
import io.mazewall.lockdown.internal.platform.syscall.Arch
import io.mazewall.lockdown.internal.platform.syscall.NativeArg
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@NeedsFreshJvm
@Tag("needs-fresh-jvm")
class MazewallLockdownNativeImageIntegrationTest {
    private companion object {
        private const val SUBPROCESS_TIMEOUT_SECONDS = 30L
    }

    @Test
    fun `NATIVE_IMAGE lockdown denies PROT_EXEC mmap and mprotect plus execve while rejecting HOTSPOT_JIT relaxation`() {
        val javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process =
            ProcessBuilder(
                javaBin,
                "-Xint",
                "--enable-native-access=ALL-UNNAMED",
                "-cp",
                System.getProperty("java.class.path"),
                "io.mazewall.lockdown.NativeImageLockdownProbeMain",
            ).redirectErrorStream(true)
                .start()
        val output =
            process.inputStream
                .bufferedReader()
                .readText()
        assertTrue(
            process.waitFor(SUBPROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS),
            "NativeImageLockdownProbeMain timed out. Output:\n$output",
        )
        assertEquals(0, process.exitValue(), "NativeImageLockdownProbeMain failed:\n$output")
        assertTrue(output.contains("NATIVE_IMAGE_PROBE_OK"), "Missing probe marker in output:\n$output")
    }
}

object NativeImageLockdownProbeMain {
    private const val PAGE_BYTES = 4096L
    private const val PROT_READ = 1L
    private const val PROT_WRITE = 2L
    private const val PROT_EXEC = 4L
    private const val MAP_PRIVATE = 2L
    private const val MAP_ANONYMOUS = 0x20L
    private val ERRNO_EPERM_PATTERN = Regex("""\b(?:error|errno)[=:]\s*1\b""")

    @JvmStatic
    fun main(args: Array<String>) {
        val arch = Arch.current()
        val rwResult =
            LinuxNative.raw.syscall(
                arch.mmap.toLong(),
                NativeArg.LongArg(0L),
                NativeArg.LongArg(PAGE_BYTES),
                NativeArg.LongArg(PROT_READ or PROT_WRITE),
                NativeArg.LongArg(MAP_PRIVATE or MAP_ANONYMOUS),
                NativeArg.LongArg(-1L),
                NativeArg.LongArg(0L),
            )
        val rwPage = assertIs<LinuxNative.SyscallResult.Success<Long, *>>(rwResult, "Pre-install RW mmap must succeed").value
        assertTrue(rwPage != 0L)

        val echo = ProcessBuilder("/bin/echo", "pre-native-image").start()
        assertEquals(0, echo.waitFor())
        assertFailsWith<IOException> {
            ProcessBuilder("/nonexistent-mazewall-prewarm").start()
        }

        try {
            val installation =
                Mazewall.installOnProcess(ProcessPolicy.denyProcessCreation(RuntimeProfile.NATIVE_IMAGE))
            assertEquals(1, installation.filterDepth)

            val execMmap =
                LinuxNative.raw.syscall(
                    arch.mmap.toLong(),
                    NativeArg.LongArg(0L),
                    NativeArg.LongArg(PAGE_BYTES),
                    NativeArg.LongArg(PROT_READ or PROT_EXEC),
                    NativeArg.LongArg(MAP_PRIVATE or MAP_ANONYMOUS),
                    NativeArg.LongArg(-1L),
                    NativeArg.LongArg(0L),
                )
            val execMmapErr = assertIs<LinuxNative.SyscallResult.Error<*>>(execMmap, "mmap(PROT_EXEC) must fail")
            assertEquals(NativeConstants.EPERM, execMmapErr.errno)

            val execMprotect =
                LinuxNative.raw.syscall(
                    arch.mprotect.toLong(),
                    NativeArg.LongArg(rwPage),
                    NativeArg.LongArg(PAGE_BYTES),
                    NativeArg.LongArg(PROT_READ or PROT_EXEC),
                    NativeArg.LongArg(0L),
                    NativeArg.LongArg(0L),
                    NativeArg.LongArg(0L),
                )
            val execMprotectErr = assertIs<LinuxNative.SyscallResult.Error<*>>(execMprotect, "mprotect(PROT_EXEC) must fail")
            assertEquals(NativeConstants.EPERM, execMprotectErr.errno)

            val execFailure =
                assertFailsWith<IOException>("execve must fail under NATIVE_IMAGE") {
                    ProcessBuilder("/bin/echo", "blocked-native-image").start()
                }
            val messages =
                generateSequence<Throwable>(execFailure) { it.cause }
                    .mapNotNull { it.message }
                    .toList()
            assertTrue(
                messages.any { msg ->
                    msg.contains("Operation not permitted", ignoreCase = true) ||
                        ERRNO_EPERM_PATTERN.containsMatchIn(msg)
                },
                "Expected EPERM in cause chain: $messages",
            )

            assertFailsWith<MazewallInstallationException> {
                Mazewall.installOnProcess(ProcessPolicy.denyProcessCreation(RuntimeProfile.HOTSPOT_JIT))
            }
            println("NATIVE_IMAGE_PROBE_OK")
        } finally {
            LinuxNative.raw.syscall(
                arch.munmap.toLong(),
                NativeArg.LongArg(rwPage),
                NativeArg.LongArg(PAGE_BYTES),
                NativeArg.LongArg(0L),
                NativeArg.LongArg(0L),
                NativeArg.LongArg(0L),
                NativeArg.LongArg(0L),
            )
        }
    }
}
