package io.mazewall.verification

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class PackagedConsumerMatrixHarnessTest {
    @TempDir
    private lateinit var directory: Path

    @Test
    fun `packaged consumer method owns a ten minute same thread deadline`() {
        val method = PackagedConsumerMatrixTest::class.java.declaredMethods.single { it.isAnnotationPresent(Test::class.java) }
        val timeout = method.getAnnotation(Timeout::class.java)

        assertNotNull(timeout, "Packaged consumer inherits the ordinary two-minute deadline")
        assertEquals(600L, timeout.unit.toSeconds(timeout.value))
        assertEquals(Timeout.ThreadMode.SAME_THREAD, timeout.threadMode)
        assertEquals("2 m", System.getProperty("junit.jupiter.execution.timeout.default"))
    }

    @Test
    fun `command selects JDK 25 once and keeps both language checks`() {
        val arguments = packagedConsumerArguments(directory, directory, "0.1.0-test", "2.4.20", writeLocks = false)
        assertEquals(listOf("-PconsumerJdk=25"), arguments.filter { it.startsWith("-PconsumerJdk=") })
        assertTrue(
            arguments.containsAll(
                listOf(
                    "--no-daemon",
                    "--no-build-cache",
                    "--no-configuration-cache",
                    ":java-api:clean",
                    ":kotlin-api:clean",
                    ":java-api:run",
                    ":kotlin-api:run",
                    ":java-api:verifyRejectedApi",
                    ":kotlin-api:verifyRejectedApi",
                    ":java-api:verifyCallableEntry",
                    ":java-api:verifyScopeBoundaries",
                    ":kotlin-api:verifyScopeBoundaries",
                    "resolveIntegrationBaselines",
                ),
            ),
        )
        assertFalse(arguments.contains("--write-locks"))
        assertTrue(packagedConsumerArguments(directory, directory, "0.1.0-test", "2.4.20", writeLocks = true).contains("--write-locks"))
    }

    @Test
    fun `completed and failed commands retain JDK 25 evidence`() {
        val root = directory.toRealPath()
        val passed = Files.createDirectory(root.resolve("passing"))
        runPackagedConsumer(root, passed, listOf("sh", "-c", "printf 'consumer ran'"), Duration.ofSeconds(5))
        assertEquals("consumer ran", Files.readString(passed.resolve("jdk25.log")))
        assertEquals("0", receipt(passed).getValue("exitCode").jsonPrimitive.content)

        val failed = Files.createDirectory(root.resolve("failing"))
        val failure = assertThrows(IllegalStateException::class.java) {
            runPackagedConsumer(root, failed, listOf("sh", "-c", "exit 17"), Duration.ofSeconds(5))
        }
        assertEquals("jdk25 failed with exit 17: ${failed.resolve("jdk25.log")}", failure.message)
        assertEquals("17", receipt(failed).getValue("exitCode").jsonPrimitive.content)
    }

    @Test
    fun `timed out command records timeout before reaping its owned tree`() {
        val root = directory.toRealPath()
        val descendant = root.resolve("descendant.pid")
        val arguments = listOf("sh", "-c", "sleep 30 & printf '%s' \"${'$'}!\" > \"${'$'}1\"; wait", "fixture", descendant.toString())

        assertThrows(ConsumerCommandTimeout::class.java) { runPackagedConsumer(root, root, arguments, Duration.ofSeconds(1)) }

        val receipt = receipt(root)
        assertEquals("true", receipt.getValue("timedOut").jsonPrimitive.content)
        assertEquals("true", receipt.getValue("reaped").jsonPrimitive.content)
        val transitions = receipt.getValue("transitions").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(transitions.indexOf("timed-out") in 0 until transitions.indexOf("reaped"))
        val launcherPid = receipt
            .getValue("pid")
            .jsonPrimitive.content
            .toLong()
        val ownedPids = listOf(launcherPid, Files.readString(descendant).toLong())
        for (pid in ownedPids) assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "Owned PID $pid survived")
    }

    @Test
    fun `contended lock fails before consumer launch`() {
        val root = directory.toRealPath()
        val lock = root.resolve("verification.lock")
        SharedVerificationLock.acquire(lock).use {
            val failure = assertThrows(IllegalStateException::class.java) {
                SharedVerificationLock.acquire(lock, waitSeconds = 1).use {
                    runPackagedConsumer(root, root, listOf("true"), Duration.ofSeconds(5))
                }
            }
            assertTrue(failure.message.orEmpty().contains("Verification lock acquisition failed"))
            assertFalse(Files.exists(root.resolve("jdk25.argv")))
        }
    }

    private fun receipt(evidence: Path) = Json.parseToJsonElement(Files.readString(evidence.resolve("jdk25.command.json"))).jsonObject
}
