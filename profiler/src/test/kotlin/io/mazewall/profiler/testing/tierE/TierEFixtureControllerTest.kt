package io.mazewall.profiler.testing.tierE

import io.mazewall.profiler.tierE.tools.controller.TierEFixtureController
import io.mazewall.testing.awaitCondition
import io.mazewall.testing.containers.runContainerCommand
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
            val admission = directory.resolve("create.lock.state")
            Files.writeString(admission, "open\n")
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
                assertTrue(Files.readString(admission).trim() == "closed", "creation admission must stay closed after lock release")
                val late = directory.resolve("late-created")
                val queued = runContainerCommand(
                    listOf(
                        "flock",
                        "-x",
                        lock.toString(),
                        "bash",
                        "-c",
                    "if [[ \$(cat \"\$1\") == open ]]; then touch \"\$2\"; fi",
                        "--",
                        admission.toString(),
                        late.toString(),
                    ),
                )
                assertTrue(queued.exitCode == 0 && !Files.exists(late), "late creator must refuse a closed run")
            } finally {
                controller.destroyForcibly()
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
