package io.mazewall.verification

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class ConsumerProcessEvidenceTest {
    @TempDir
    private lateinit var root: Path

    @Test
    fun `fast successful exit drains delayed actual readers before stream cleanup`() = assertDelayedReaders(0)

    @Test
    fun `fast nonzero exit drains delayed actual readers before stream cleanup`() = assertDelayedReaders(37)

    @Test
    fun `interrupted normal drain restores waiter interrupt after pending real pump cleanup`() {
        val normalDrain = CountDownLatch(1)
        val finalDrain = CountDownLatch(1)
        val releaseReaders = CountDownLatch(1)
        val readers = mutableListOf<Thread>()
        var failure: Throwable? = null
        var restoredInterrupt = false
        val waiter = Thread {
            try {
                ConsumerProcessEvidence.run(listOf("sh", "-c", "printf out; printf err >&2"), root, "interrupted", startPump = { reader ->
                    val pump = object : CompletableFuture<Void>() {
                        private var joins = 0

                        override fun get(
                            timeout: Long,
                            unit: TimeUnit,
                        ): Void? {
                            if (joins++ == 0) {
                                normalDrain.countDown()
                            } else {
                                // Probe the still-pending future before releasing readers: restored interrupts cannot race completion.
                                try {
                                    try {
                                        super.get(0, TimeUnit.NANOSECONDS)
                                    } catch (_: TimeoutException) {
                                        // A non-interrupted pending join proceeds to its original bounded wait.
                                    }
                                } finally {
                                    finalDrain.countDown()
                                }
                            }
                            return super.get(timeout, unit)
                        }
                    }
                    readers += Thread {
                        val read = runCatching {
                            check(releaseReaders.await(10, TimeUnit.SECONDS)) { "Reader barrier was not released" }
                            reader.run()
                        }
                        read.fold({ pump.complete(null) }, { pump.completeExceptionally(it) })
                    }.also { it.start() }
                    pump
                })
            } catch (caught: Throwable) {
                failure = caught
            } finally {
                restoredInterrupt = Thread.currentThread().isInterrupted
            }
        }
        waiter.start()
        try {
            assertTrue(normalDrain.await(5, TimeUnit.SECONDS), "Waiter did not enter normal drain")
            val running = Json.parseToJsonElement(Files.readString(root.resolve("interrupted.command.json"))).jsonObject
            val pid = running
                .getValue("pid")
                .jsonPrimitive.content
                .toLong()
            assertTrue(ProcessHandle.of(pid).map { !it.isAlive }.orElse(true), "Normal drain preceded actual child exit")
            waiter.interrupt()
            assertTrue(finalDrain.await(5, TimeUnit.SECONDS), "Pending pump did not reach final cleanup")
            releaseReaders.countDown()
            waiter.join(5000)
            assertTrue(!waiter.isAlive, "Interrupted waiter did not finish bounded cleanup")
            assertTrue(failure is IllegalStateException, "Original lifecycle interruption was replaced: $failure")
            assertTrue(failure?.cause is InterruptedException, "Original interruption cause was lost: $failure")
            assertTrue(failure!!.suppressed.none { it is java.util.concurrent.ExecutionException }, "Real pump failed: $failure")
            assertEquals("out", Files.readString(root.resolve("interrupted.stdout.log")))
            assertEquals("err", Files.readString(root.resolve("interrupted.stderr.log")))
            val receipt = Json.parseToJsonElement(Files.readString(root.resolve("interrupted.command.json"))).jsonObject
            assertEquals("true", receipt.getValue("reaped").jsonPrimitive.content)
            assertEquals("false", receipt.getValue("timedOut").jsonPrimitive.content)
            assertTrue(ProcessHandle.of(pid).map { !it.isAlive }.orElse(true), "Owned child was not retired")
            assertTrue(restoredInterrupt, "Outer pump cleanup consumed the waiter's interrupt")
        } finally {
            releaseReaders.countDown()
            waiter.interrupt()
            waiter.join(5000)
            readers.forEach { it.join(5000) }
            assertTrue(!waiter.isAlive && readers.none { it.isAlive }, "Fixture threads did not retire")
        }
    }

    @Test
    fun `stderr pump startup failure still joins the acquired stdout pump`() {
        val startupFailure = IllegalStateException("stderr startup failed")
        val drainFailure = IllegalStateException("stdout drain failed")
        var starts = 0
        var joins = 0
        val failure = assertThrows<IllegalStateException> {
            ConsumerProcessEvidence.run(listOf("true"), root, "partial-startup", startPump = {
                if (++starts == 2) throw startupFailure
                object : CompletableFuture<Void>() {
                    override fun get(
                        timeout: Long,
                        unit: TimeUnit,
                    ): Void? {
                        joins++
                        val stdout = root.resolve("partial-startup.stdout.log")
                        assertTrue(Files.isRegularFile(stdout), "Evidence disappeared before the join")
                        completeExceptionally(drainFailure)
                        return super.get(timeout, unit)
                    }
                }
            })
        }
        assertSame(startupFailure, failure)
        assertEquals(2, starts)
        assertEquals(1, joins)
        assertSame(drainFailure, failure.suppressed.single().cause)
        val receipt = Json.parseToJsonElement(Files.readString(root.resolve("partial-startup.command.json"))).jsonObject
        assertEquals("true", receipt.getValue("reaped").jsonPrimitive.content)
    }

    private fun assertDelayedReaders(exitCode: Int) {
        // Keep argv ASCII even on hosts whose native encoding cannot carry the UTF-8 test payload.
        val args = listOf("sh", "-c", "printf 'out\\000\\303\\251\\n'; printf 'err\\000\\316\\273\\n' >&2; exit $exitCode")
        val outcome = runCatching {
            ConsumerProcessEvidence.run(args, root, "delayed", startPump = { reader ->
                // Execute the actual reader only at its join, after the real child's exit.
                object : CompletableFuture<Void>() {
                    override fun get(
                        timeout: Long,
                        unit: TimeUnit,
                    ): Void? {
                        if (!isDone) {
                            val receipt = Json.parseToJsonElement(Files.readString(root.resolve("delayed.command.json"))).jsonObject
                            val pid = receipt
                                .getValue("pid")
                                .jsonPrimitive.content
                                .toLong()
                            assertTrue(ProcessHandle.of(pid).map { !it.isAlive }.orElse(true), "Reader ran before actual child exit")
                            val read = runCatching { reader.run() }
                            read.fold({ complete(null) }, { completeExceptionally(it) })
                        }
                        return super.get(timeout, unit)
                    }
                }
            })
        }
        assertTrue(outcome.isSuccess, "Normal exit must drain before stream cleanup: ${outcome.exceptionOrNull()}")
        val result = outcome.getOrThrow()
        assertEquals(exitCode, result.exitCode)
        assertEquals("out\u0000é\n", result.stdout)
        assertEquals("err\u0000λ\n", result.stderr)
        assertArrayEquals("out\u0000é\n".toByteArray(), Files.readAllBytes(root.resolve("delayed.stdout.log")))
        assertArrayEquals("err\u0000λ\n".toByteArray(), Files.readAllBytes(root.resolve("delayed.stderr.log")))
        assertArrayEquals("out\u0000é\nerr\u0000λ\n".toByteArray(), Files.readAllBytes(root.resolve("delayed.log")))
        val receipt = Json.parseToJsonElement(Files.readString(root.resolve("delayed.command.json"))).jsonObject
        assertEquals(args, receipt.getValue("argv").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(root.toString(), receipt.getValue("cwd").jsonPrimitive.content)
        assertEquals(exitCode.toString(), receipt.getValue("exitCode").jsonPrimitive.content)
        assertEquals("false", receipt.getValue("timedOut").jsonPrimitive.content)
        assertEquals("true", receipt.getValue("reaped").jsonPrimitive.content)
        val pid = receipt
            .getValue("pid")
            .jsonPrimitive.content
            .toLong()
        assertEquals(false, ProcessHandle.of(pid).map { it.isAlive }.orElse(false))
    }

    @Test
    fun `actual nonzero child retains full separate streams and command identity`() {
        val args = listOf("sh", "-c", "awk 'BEGIN { for(i=0;i<20000;i++) printf \"out\"; print \"\" }'; awk 'BEGIN { for(i=0;i<20000;i++) printf \"err\"; print \"\" }' >&2; exit 37")
        val result = ConsumerProcessEvidence.run(args, root, "nonzero")
        assertEquals(37, result.exitCode)
        assertEquals("out".repeat(20000) + "\n", result.stdout)
        assertEquals("err".repeat(20000) + "\n", result.stderr)
        val receipt = Json.parseToJsonElement(Files.readString(root.resolve("nonzero.command.json"))).jsonObject
        assertEquals(args, receipt.getValue("argv").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("37", receipt.getValue("exitCode").jsonPrimitive.content)
        assertEquals("false", receipt.getValue("timedOut").jsonPrimitive.content)
        assertEquals("true", receipt.getValue("reaped").jsonPrimitive.content)
    }

    @Test
    fun `timeout preserves output when readers start after child exit`() = assertDelayedTimeoutReaders(false)

    @Test
    fun `forced timeout preserves output when readers start after child exit`() = assertDelayedTimeoutReaders(true)

    private fun assertDelayedTimeoutReaders(ignoreTerm: Boolean) {
        val unrelated = ProcessBuilder("sleep", "30").start()
        var owned: ProcessHandle? = null
        var descendant: ProcessHandle? = null
        val marker = root.resolve("ready")
        val trap = if (ignoreTerm) "trap '' TERM; " else ""
        val args =
            listOf(
                "sh",
                "-c",
                "${trap}sleep 30 & child=\$!; printf 'partial stdout\\n'; printf 'partial stderr\\n' >&2; " +
                    "printf '%s %s' \"\$\$\" \"\$child\" > ready.tmp; mv ready.tmp ready; wait",
            )
        try {
            root.fileSystem.newWatchService().use { watch ->
                root.register(watch, ENTRY_CREATE)
                val failure = assertThrows<ConsumerCommandTimeout> {
                    ConsumerProcessEvidence.run(args, root, "delayed-timeout", Duration.ofSeconds(1), startPump = { reader ->
                        if (owned == null) {
                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                            while (!Files.exists(marker)) {
                                val remaining = deadline - System.nanoTime()
                                check(remaining > 0) { "Child did not publish readiness" }
                                val key = watch.poll(remaining, TimeUnit.NANOSECONDS) ?: error("Child did not publish readiness")
                                key.pollEvents()
                                check(key.reset()) { "Readiness watch invalidated" }
                            }
                            val pids = Files.readString(marker).split(" ").map(String::toLong)
                            owned = ProcessHandle.of(pids[0]).orElseThrow()
                            descendant = ProcessHandle.of(pids[1]).orElseThrow()
                        }
                        object : CompletableFuture<Void>() {
                            override fun get(
                                timeout: Long,
                                unit: TimeUnit,
                            ): Void? {
                                if (!isDone) {
                                    owned!!.onExit().get(timeout, unit)
                                    val read = runCatching {
                                        check(!descendant!!.isAlive) { "Descendant still holds the child pipes" }
                                        reader.run()
                                    }
                                    read.fold({ complete(null) }, { completeExceptionally(it) })
                                }
                                return super.get(timeout, unit)
                            }
                        }
                    })
                }
                assertEquals(args, failure.arguments)
                val receipt = Json.parseToJsonElement(Files.readString(root.resolve("delayed-timeout.command.json"))).jsonObject
                val transitions = receipt.getValue("transitions").jsonArray.map { it.jsonPrimitive.content }
                assertTrue(transitions.indexOf("timed-out") in 0 until transitions.indexOf("reaped"))
                assertEquals("true", receipt.getValue("timedOut").jsonPrimitive.content)
                assertEquals("true", receipt.getValue("reaped").jsonPrimitive.content)
                assertTrue(owned != null && !owned!!.isAlive, "Owned child survived")
                assertTrue(descendant != null && !descendant!!.isAlive, "Owned descendant survived")
                assertTrue(unrelated.isAlive, "Unrelated process was signalled")
                assertEquals("partial stdout\n", Files.readString(root.resolve("delayed-timeout.stdout.log")))
                assertEquals("partial stderr\n", Files.readString(root.resolve("delayed-timeout.stderr.log")))
                assertTrue(failure.suppressed.isEmpty(), "Timeout cleanup failed: ${failure.suppressed.toList()}")
            }
        } finally {
            unrelated.destroyForcibly()
            assertTrue(unrelated.waitFor(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `timeout is persisted before signalling and retiring only acquired ancestry`() {
        val unrelated = ProcessBuilder("sleep", "30").start()
        val args = listOf("sh", "-c", "trap '' TERM; printf 'partial stdout\\n'; printf 'partial stderr\\n' >&2; sleep 30 & wait")
        try {
            val failure = assertThrows<ConsumerCommandTimeout> { ConsumerProcessEvidence.run(args, root, "timeout", Duration.ofSeconds(1)) }
            assertEquals(args, failure.arguments)
            val receipt = Json.parseToJsonElement(Files.readString(root.resolve("timeout.command.json"))).jsonObject
            val transitions = receipt.getValue("transitions").jsonArray.map { it.jsonPrimitive.content }
            assertTrue(transitions.indexOf("timed-out") < transitions.indexOf("reaped"))
            assertEquals("true", receipt.getValue("timedOut").jsonPrimitive.content)
            assertEquals("true", receipt.getValue("reaped").jsonPrimitive.content)
            assertEquals("partial stdout\n", Files.readString(root.resolve("timeout.stdout.log")))
            assertEquals("partial stderr\n", Files.readString(root.resolve("timeout.stderr.log")))
            assertTrue(unrelated.isAlive, "Unrelated process was signalled")
        } finally {
            unrelated.destroyForcibly()
            assertTrue(unrelated.waitFor(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `launch failure retains argv cwd and original failure identity`() {
        val args = listOf(root.resolve("no-such-executable").toString())
        assertThrows<java.io.IOException> { ConsumerProcessEvidence.run(args, root, "missing") }
        val receipt = Json.parseToJsonElement(Files.readString(root.resolve("missing.command.json"))).jsonObject
        assertEquals("launch-failed", receipt.getValue("state").jsonPrimitive.content)
        assertEquals("null", receipt.getValue("pid").jsonPrimitive.content)
        assertEquals(root.toString(), receipt.getValue("cwd").jsonPrimitive.content)
    }

    @Test
    fun `existing or aliased evidence is preserved and child cannot launch`() {
        for (kind in listOf("file", "symlink", "hardlink")) {
            val directory = Files.createDirectory(root.resolve(kind))
            val operator = Files.writeString(directory.resolve("operator"), "unknown evidence")
            val output = directory.resolve("owned.stdout.log")
            when (kind) {
                "file" -> Files.copy(operator, output)
                "symlink" -> Files.createSymbolicLink(output, operator)
                "hardlink" -> Files.createLink(output, operator)
            }
            assertThrows<java.nio.file.FileAlreadyExistsException> { ConsumerProcessEvidence.run(listOf("touch", "launched"), directory, "owned") }
            assertEquals("unknown evidence", Files.readString(operator))
            assertEquals("unknown evidence", Files.readString(output))
            assertTrue(!Files.exists(directory.resolve("launched")))
        }
    }
}
