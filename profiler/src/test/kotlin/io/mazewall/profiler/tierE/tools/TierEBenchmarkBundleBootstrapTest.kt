package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TierEBenchmarkBundleBootstrapTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `successful isolated bootstrap execs installed builder with exact native package arguments`() {
        val wrapper = directory.resolve("gradlew")
        Files.writeString(wrapper, "#!/bin/sh\nexit 0\n")
        assertTrue(wrapper.toFile().setExecutable(true))
        val captured = directory.resolve("builder-arguments")
        val launcher = Files.createDirectories(directory.resolve("profiler/build/install/tier-e-daemon/bin")).resolve("tier-e-tools")
        Files.writeString(launcher, "#!/bin/sh\nprintf '%s\\n' \"\$@\" > \"\$TEST_BUILDER_ARGS\"\n")
        assertTrue(launcher.toFile().setExecutable(true))
        val bundle = directory.resolve("new bundle")
        val native = directory.resolve("verified native package")
        val child = ProcessBuilder(TierEBenchmarkBootstrap.command(directory, bundle, true, native))
            .apply {
                environment()["MAZEWALL_TIER_E_BUILD_CACHE_ROOT"] = directory.resolve("cache").toString()
                environment()["TEST_BUILDER_ARGS"] = captured.toString()
            }.redirectErrorStream(true)
            .redirectOutput(directory.resolve("success.log").toFile())
            .start()
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS))
            assertEquals(0, child.exitValue())
            assertEquals(listOf("bundle-build", directory.toString(), bundle.toString(), "true", "false", native.toString()), Files.readAllLines(captured))
        } finally {
            if (child.isAlive) {
                child.destroyForcibly()
                check(child.waitFor(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `cold bootstrap failure propagates and cannot execute installed bundle builder`() {
        val args = directory.resolve("arguments")
        val wrapper = directory.resolve("gradlew")
        Files.writeString(wrapper, "#!/bin/sh\nprintf '%s\\n' \"\$@\" > \"\$TEST_BOOTSTRAP_ARGS\"\nexit 23\n")
        assertTrue(wrapper.toFile().setExecutable(true))
        val invoked = directory.resolve("builder-invoked")
        val launcher = Files.createDirectories(directory.resolve("profiler/build/install/tier-e-daemon/bin")).resolve("tier-e-tools")
        Files.writeString(launcher, "#!/bin/sh\ntouch \"\$TEST_BUILDER_INVOKED\"\n")
        assertTrue(launcher.toFile().setExecutable(true))
        val cache = directory.resolve("owned cache")
        val child = ProcessBuilder(TierEBenchmarkBootstrap.command(directory, directory.resolve("bundle"), false, null))
            .apply {
                environment()["MAZEWALL_TIER_E_BUILD_CACHE_ROOT"] = cache.toString()
                environment()["TEST_BOOTSTRAP_ARGS"] = args.toString()
                environment()["TEST_BUILDER_INVOKED"] = invoked.toString()
            }.redirectErrorStream(true)
            .redirectOutput(directory.resolve("bootstrap.log").toFile())
            .start()
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS))
            assertEquals(23, child.exitValue())
            assertFalse(Files.exists(invoked))
            val actual = Files.readAllLines(args)
            assertTrue("--project-cache-dir" in actual)
            assertTrue(cache.resolve("gradle-bootstrap-project-cache").toString() in actual)
            assertTrue(":profiler:installDist" in actual)
        } finally {
            if (child.isAlive) {
                child.destroyForcibly()
                check(child.waitFor(5, TimeUnit.SECONDS))
            }
        }
    }
}
