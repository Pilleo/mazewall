package io.mazewall.portal

import io.mazewall.platform.process.ProcessLauncher
import io.mazewall.platform.process.RealProcessLauncher
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StableWorkerCacheGenerationTest {
    @TempDir var directory: Path? = null

    @Test
    fun `stable replacement implementation with identical real service metadata rejects before launch`() {
        val directory = requireNotNull(directory) { "JUnit must initialize the temporary directory" }
        val manifest = PortalServiceManifest(
            PortalServiceId("owned-echo"),
            OwnedEchoService::class.java.name,
            "fixture.OwnedEchoImpl",
            "fixture.OwnedEchoPortalDispatcher",
            "builtin-echo-fixture",
            "owned-echo",
            PortalInstanceMode.STATELESS,
            "restricted",
            "worker",
        )
        val original = ownedEchoWorkerFixture(Files.createDirectory(directory.resolve("original")), manifest)
        val replacement = ownedEchoWorkerFixture(Files.createDirectory(directory.resolve("replacement")), manifest, "\"replacement implementation\"")
        assertFalse(Files.readAllBytes(original).contentEquals(Files.readAllBytes(replacement)))
        val bundle = directory.resolve("worker.zip")
        ZipOutputStream(Files.newOutputStream(bundle)).use { zip ->
            (System.getProperty("io.mazewall.portal.worker.classpath").split(java.io.File.pathSeparator).map(Path::of) + listOf(original)).forEachIndexed { index, path ->
                zip.putNextEntry(ZipEntry("lib/$index-${path.fileName}"))
                Files.copy(path, zip)
                zip.closeEntry()
            }
        }
        val digest = java.util.HexFormat
            .of()
            .formatHex(
                java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(Files.readAllBytes(bundle)),
            )
        val artifact = PortalWorkerArtifact(bundle, digest)
        val first = WorkerBundleMaterializer.materialize(artifact, directory.resolve("cache"))
        val cp = first.classpath.joinToString(java.io.File.pathSeparator)
        assertEquals(listOf(manifest), PortalWorkerGeneration.readManifests(cp))
        WorkerBundleMaterializer.verifyLaunch(artifact, cp, PortalMeasurementBudget())
        val cachedImplementation = first.classpath.single { it.fileName.toString().endsWith("owned-echo-fixture.jar") }
        Files.copy(replacement, cachedImplementation, StandardCopyOption.REPLACE_EXISTING)
        assertEquals(listOf(manifest), PortalWorkerGeneration.readManifests(cp), "valid service metadata and schema are unchanged")
        assertEquals(digest, Files.readString(first.directory.resolve(".mazewall-worker-bundle")))
        val launches = AtomicInteger()
        val launcher = object : ProcessLauncher by RealProcessLauncher {
            override fun startProcess(
                args: List<String>,
                redirectErrorStream: Boolean,
                preparation: io.mazewall.platform.process.JvmLaunchPreparation,
            ): Process {
                launches.incrementAndGet()
                error("stable cache replacement must reject before child acquisition")
            }
        }
        ProcessBroker(launcher = launcher, workerClasspath = cp, workerLaunch = PortalWorkerLaunch(listOf(manifest), artifact)).use { broker ->
            assertThrows(IllegalArgumentException::class.java) { broker.start() }
            assertEquals(0, launches.get())
            assertEquals(0, broker.trackedWorkers())
        }
        assertEquals(
            digest,
            java.util.HexFormat
            .of()
            .formatHex(
                java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(Files.readAllBytes(bundle)),
            ),
                    "configured original ZIP remains unchanged",
        )
    }
}
