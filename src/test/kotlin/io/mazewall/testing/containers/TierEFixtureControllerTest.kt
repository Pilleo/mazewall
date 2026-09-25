package io.mazewall.testing.containers

import io.mazewall.testing.awaitCondition
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class TierEFixtureControllerTest {
    @Test
    fun `controller termination waits for in flight named creation before returning`() {
        val directory = Files.createTempDirectory("tier-e-create-barrier-")
        try {
            val lock = directory.resolve("create.lock")
            val entered = directory.resolve("entered")
            val release = directory.resolve("release.fifo")
            assertTrue(runContainerCommand(listOf("mkfifo", release.toString())).exitCode == 0)
            val controller = ProcessBuilder(
                "bash",
                "-c",
                "flock -x \"\$1\" bash -c 'touch \"\$1\"; read -r reply < \"\$2\"' -- \"\$2\" \"\$3\"",
                "--",
                lock.toString(),
                entered.toString(),
                release.toString(),
            ).start()
            val finished = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            try {
                awaitCondition(Duration.ofSeconds(5), "creation lock entered") { Files.exists(entered) }
                val reaper = Thread {
                    try {
                        TierEFixtureController.awaitCreationQuiescence(controller, lock, Duration.ofSeconds(10))
                    } catch (error: Throwable) {
                        failure.set(error)
                    } finally {
                        finished.countDown()
                    }
                }
                reaper.start()
                assertTrue(!finished.await(150, TimeUnit.MILLISECONDS), "cannot reconcile names while creation owns lock")
                Files.writeString(release, "ready\n")
                assertTrue(finished.await(10, TimeUnit.SECONDS))
                assertTrue(failure.get() == null, "creation settlement failed: ${failure.get()}")
            } finally {
                controller.destroyForcibly()
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
