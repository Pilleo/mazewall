package io.mazewall.verification

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@Tag("consumer-native")
class SpringBootPackagedAcceptanceTest {
    private enum class Scenario { MISSING_REVIEW, STALE_REVIEW, CHANGED_JAR, CHANGED_TEMPLATE, UNSUPPORTED, SUCCESS }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SAME_THREAD)
    fun `external fixture review binds actual finished deployment before required native effects`() {
        val workspace = Path.of(BootFixture.required("consumerWorkspace"))
        SharedVerificationLock.acquire(SharedVerificationLock.resolve(workspace, System.getProperty("verificationLock"))).use {
            val evidence = BootFixture.evidence("boot-acceptance")
            val jar = Path.of(BootFixture.required("consumerBootJar"))
            val repository = Path.of(BootFixture.required("consumerRepository"))
            val version = BootFixture.required("consumerVersion")
            val revision = BootFixture.required("consumerRevision")
            val source = BootFixture.sourceIdentity(workspace, revision, evidence)
            val publications = BootFixture.inspectJar(jar, repository, version)
            Files.writeString(evidence.resolve("source-identity.json"), source.toString(), CREATE_NEW)
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            for (scenario in Scenario.entries) {
                val root = BootFixture.deployment(jar, evidence)

                fun launch(
                    mode: String,
                    success: Boolean = true,
                    label: String = mode,
                ): String {
                    val result = ConsumerProcessEvidence.run(listOf(java, "--enable-native-access=ALL-UNNAMED", "-jar", "application.jar", mode, root.toString()), root, label)
                    val output = result.stdout + result.stderr
                    assertEquals(success, result.exitCode == 0, "$scenario $mode exit=${result.exitCode}; deployment=$root\n$output")
                    return output
                }
                launch("measure")
                if (scenario == Scenario.MISSING_REVIEW) {
                    assertTrue("trusted-review.properties" in launch("verify", false, "missing-review"))
                    continue
                }
                val authority = linkedMapOf(
                    "jarSha256" to BootFixture.hash(root.resolve("application.jar")),
                    "documentSha256" to BootFixture.hash(root.resolve("candidate.json")),
                    "resourceGeneration" to Files.readString(root.resolve("candidate-generation.txt")),
                    "reviewer" to "kotlin-external-private-known-fixture-reviewer",
                    "producerRevision" to revision,
                    "consumerVersion" to version,
                )
                val candidate = Json.parseToJsonElement(Files.readString(root.resolve("candidate.json")))
                assertTrue(authority.getValue("jarSha256") in candidate.toString(), "Candidate must bind the whole finished outer JAR")

                fun approve(
                    name: String,
                    values: Map<String, String>,
                ) {
                    val properties = Properties().also { it.putAll(values) }
                    Files.newOutputStream(root.resolve(name), CREATE_NEW).use { properties.store(it, "External private fixture authority; never application self-approval") }
                }
                approve("trusted-review.properties", if (scenario == Scenario.STALE_REVIEW) authority + ("jarSha256" to "0".repeat(64)) else authority)
                when (scenario) {
                    Scenario.MISSING_REVIEW -> error("Missing review handled before approval")
                    Scenario.STALE_REVIEW -> {
                        val output = launch("stale", false)
                        assertTrue("review" in output.lowercase() || "mismatch" in output.lowercase())
                    }
                    Scenario.CHANGED_JAR -> {
                        val mutated = root.resolve("changed.jar")
                        ZipFile(root.resolve("application.jar").toFile()).use { original ->
                            ZipOutputStream(Files.newOutputStream(mutated, CREATE_NEW)).use { zip ->
                                zip.setComment("private-negative-fixture-changed-finished-jar")
                                for (entry in original.entries().asSequence()) {
                                    val copy = ZipEntry(entry.name)
                                    copy.method = entry.method
                                    if (entry.method == ZipEntry.STORED) {
                                        copy.size = entry.size
                                        copy.crc = entry.crc
                                    }
                                    zip.putNextEntry(copy)
                                    original.getInputStream(entry).use { it.copyTo(zip) }
                                    zip.closeEntry()
                                }
                            }
                        }
                        Files.move(mutated, root.resolve("application.jar"), REPLACE_EXISTING)
                        assertTrue("Actual finished Boot deployment differs from external trusted review" in launch("verify", false, "changed-actual-jar"))
                    }
                    Scenario.CHANGED_TEMPLATE -> {
                        Files.writeString(root.resolve("template.txt"), "unapproved deployment mutation")
                        assertTrue("Actual finished Boot deployment differs from external trusted review" in launch("verify", false, "changed-template"))
                    }
                    Scenario.UNSUPPORTED -> {
                        launch("measure-unsupported")
                        approve(
                            "unsupported-review.properties",
                            authority +
                            mapOf(
                            "documentSha256" to BootFixture.hash(root.resolve("unsupported-candidate.json")),
                            "resourceGeneration" to Files.readString(root.resolve("unsupported-generation.txt")),
                        ),
                        )
                        val output = launch("unsupported", false)
                        assertTrue("UNSUPPORTED_REQUIRED_REJECTED bodyEntries=0" in output)
                        assertTrue("Unproxyable required declaration" in output || "unproxyable method" in output)
                    }
                    Scenario.SUCCESS -> {
                        val output = launch("verify")
                        for (marker in listOf(
                                "BOOT_SECURITY_ABSENT",
                                "BOOT_ACTUATOR_ABSENT",
                                "DIAGNOSTICS_READY",
                                "DIAGNOSTICS_DRAINING",
                                "DIAGNOSTICS_CLOSED",
                                "REQUIRED_UNBOUND_REJECTED",
                                "BOOT_ACTUAL_ORIGIN",
                                "TRUSTED_INIT_JDBC_WARMUP",
                                "VIRTUAL_CALLER_PLATFORM_BODY",
                                "JDBC_COMMITTED",
                                "DENIED_TEMPLATE_MUTATION",
                                "DENIED_SIBLING_READ",
                                "CALLER_DELIVERY_NOT_PHYSICAL_END",
                                "PHYSICAL_WORKER_RETIRED",
                                "BOOT_NATIVE_PASS",
                            )) {
                            assertTrue(marker in output, "Missing native acceptance marker: $marker")
                        }
                        assertEquals("Reviewed quarterly template", Files.readString(root.resolve("template.txt")))
                        assertEquals("Reviewed quarterly template: approved report", Files.readString(root.resolve("reports/quarterly.txt")))
                        assertEquals(authority.getValue("jarSha256"), BootFixture.hash(root.resolve("application.jar")))
                    }
                }
                Files.writeString(
                    root.resolve("identity.json"),
                    buildJsonObject {
                    authority.forEach { (key, value) -> put(key, value) }
                    put("scenario", scenario.name)
                    put("deployment", root.toString())
                    put("sourceJar", jar.toRealPath().toString())
                    put("sidecarSha256", BootFixture.hash(root.resolve("trusted-review.properties")))
                    put("publications", publications)
                    put("producerSourceSha256", source.getValue("producerSourceSha256").jsonPrimitive.content)
                    put("demoAndHarnessSourceSha256", source.getValue("demoAndHarnessSourceSha256").jsonPrimitive.content)
                    put("security", "absent")
                    put("mode", "non-web")
                }.toString(),
                    CREATE_NEW,
                )
            }
            println("BOOT_CONSUMER_PASS evidence=$evidence")
        }
    }
}
