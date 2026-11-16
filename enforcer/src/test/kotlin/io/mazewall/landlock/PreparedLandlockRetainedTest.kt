package io.mazewall.landlock

import io.mazewall.enforcer.api.execution.ConfinedHelperRuntimeDrainTest
import io.mazewall.platform.process.JvmChildProcess
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreparedLandlockRetainedTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `transferred native unknown never repeats or settles`() = runProbe("native")

    @Test
    fun `transferred real ruleset close unknown never repeats or settles`() = runProbe("ruleset")

    @Test
    fun `manager real ruleset fatal retains unknown without retry`() = runProbe("manager-ruleset")

    @Test
    fun `transferred native refused retains exact outcome without retry`() = runProbe("refused")

    private fun runProbe(mode: String) {
        val output = directory.resolve("probe-$mode.log")
        val builder = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--enable-native-access=ALL-UNNAMED",
            "-cp",
            System.getProperty("java.class.path"),
            PreparedLandlockRetainedProbe::class.java.name,
            directory.toString(),
            mode,
        ).redirectErrorStream(true).redirectOutput(output.toFile())
        JvmChildProcess.stripInheritedJvmOptions(builder.environment())
        val child = builder.start()
        try {
            assertTrue(child.waitFor(45, TimeUnit.SECONDS), "Retained-owner probe must finish")
            val transcript = Files.readString(output)
            assertEquals(0, child.exitValue(), transcript)
            println(transcript)
        } finally {
            if (child.isAlive) child.destroyForcibly()
            assertTrue(child.waitFor(5, TimeUnit.SECONDS), "Retained-owner probe must be reaped")
        }
    }
}

internal object PreparedLandlockRetainedProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val fixture = ConfinedHelperRuntimeDrainTest()
        fixture.directory = Path.of(args[0])
        try {
            fixture.nativeBoundary()
            when (args[1]) {
                "manager-ruleset" -> fixture.managerRulesetFatalProbe()
                "refused" -> fixture.transferredRefusedProbe()
                else -> fixture.transferredUnknownProbe(args[1] == "ruleset")
            }
            fixture.resetNative()
            // UNKNOWN retains a non-daemon owner by design; child process exit is test teardown only.
            exitProcess(0)
        } catch (failure: Throwable) {
            failure.printStackTrace()
            exitProcess(1)
        }
    }
}
