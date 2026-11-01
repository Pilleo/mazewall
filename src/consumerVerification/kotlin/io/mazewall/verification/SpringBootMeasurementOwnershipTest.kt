package io.mazewall.verification

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.util.concurrent.TimeUnit

@Tag("consumer-native")
class SpringBootMeasurementOwnershipTest {
    private enum class Alias { EXISTING, SYMLINK, HARDLINK }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES, threadMode = Timeout.ThreadMode.SAME_THREAD)
    fun `actual executable refuses all twenty one measurement collisions without changing unknown files`() {
        val workspace = Path.of(BootFixture.required("consumerWorkspace"))
        SharedVerificationLock.acquire(SharedVerificationLock.resolve(workspace, System.getProperty("verificationLock"))).use {
            val evidence = BootFixture.evidence("measurement-ownership")
            val jar = Path.of(BootFixture.required("consumerBootJar"))
            BootFixture.inspectJar(jar, Path.of(BootFixture.required("consumerRepository")), BootFixture.required("consumerVersion"))
            Files.writeString(evidence.resolve("source-identity.json"), BootFixture.sourceIdentity(workspace, BootFixture.required("consumerRevision"), evidence).toString())
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            var cases = 0
            for ((mode, names) in listOf(
                    "measure" to listOf("candidate.json", "candidate-generation.txt"),
                    "measure-report" to listOf("candidate.json", "candidate-generation.txt"),
                    "measure-unsupported" to listOf("unsupported-candidate.json", "unsupported-generation.txt"),
                )) {
                for (name in names) {
                    for (kind in Alias.entries) {
                    val root = BootFixture.deployment(jar, evidence)
                    val operator = Files.writeString(root.resolve("operator-original.txt"), "operator owned: must survive")
                    val output = root.resolve(name)
                    when (kind) {
                        Alias.EXISTING -> Files.copy(operator, output)
                        Alias.SYMLINK -> Files.createSymbolicLink(output, operator)
                        Alias.HARDLINK -> Files.createLink(output, operator)
                    }
                    val key = Files.readAttributes(output, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
                    val result = ConsumerProcessEvidence.run(
                        listOf(java, "--enable-native-access=ALL-UNNAMED", "-jar", "application.jar", mode, root.toString()),
                        root,
                        "measurement-check",
                        Duration.ofSeconds(30),
                    )
                    assertTrue(result.exitCode != 0, "$mode $name $kind unexpectedly succeeded: $root")
                    assertEquals("operator owned: must survive", Files.readString(operator))
                    assertEquals("operator owned: must survive", Files.readString(output))
                    assertEquals(key, Files.readAttributes(output, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey())
                    assertFalse(Files.exists(root.resolve(names.first { it != name }), NOFOLLOW_LINKS), "Unexpected partial measurement pair")
                    cases++
                }
                }
                val root = BootFixture.deployment(jar, evidence)
                val alias = Files.createSymbolicLink(root.resolve("deployment-alias"), root)
                val result = ConsumerProcessEvidence.run(
                    listOf(java, "--enable-native-access=ALL-UNNAMED", "-jar", "application.jar", mode, alias.toString()),
                    root,
                    "directory-alias-check",
                    Duration.ofSeconds(30),
                )
                assertTrue(result.exitCode != 0 && "symlink/non-directory alias" in result.stderr)
                assertTrue(names.none { Files.exists(root.resolve(it), NOFOLLOW_LINKS) })
                cases++
            }
            assertEquals(21, cases)
            println("MEASUREMENT_OWNERSHIP_PASS $cases existing/symlink/hardlink/directory-alias cases; evidence=$evidence")
        }
    }
}
