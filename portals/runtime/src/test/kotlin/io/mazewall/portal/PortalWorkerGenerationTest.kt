package io.mazewall.portal

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PortalWorkerGenerationTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `actual classpath bytes artifact and manifest group each change generation`() {
        val classpath = Files.createDirectory(directory.resolve("classes"))
        val file = Files.writeString(classpath.resolve("worker.class"), "revision-one")
        val bundle = Files.writeString(directory.resolve("worker.zip"), "artifact-one")
        val manifest =
            PortalServiceManifest(PortalServiceId("service"), "fixture.Service", "fixture.Impl", "fixture.Dispatcher", "schema", "group", PortalInstanceMode.STATELESS, "baseline", "artifact")
        val expected = PortalWorkerGeneration.capture(classpath.toString(), bundle, listOf(manifest))
        val artifactDigest = java.util.HexFormat
            .of()
            .formatHex(
                java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(Files.readAllBytes(bundle)),
            )
        assertEquals(expected, PortalWorkerGeneration.capture(classpath.toString(), bundle, listOf(manifest), artifactDigest))
        assertEquals(expected, PortalWorkerGeneration.capture(classpath.toString(), bundle, listOf(manifest)))
        Files.writeString(file, "revision-two")
        assertNotEquals(expected.inputs, PortalWorkerGeneration.capture(classpath.toString(), bundle, listOf(manifest)).inputs)
        Files.writeString(file, "revision-one")
        Files.writeString(bundle, "artifact-two")
        assertThrows(IllegalArgumentException::class.java) { PortalWorkerGeneration.capture(classpath.toString(), bundle, listOf(manifest), artifactDigest) }
        assertNotEquals(expected.inputs, PortalWorkerGeneration.capture(classpath.toString(), bundle, listOf(manifest)).inputs)
        assertNotEquals(expected.manifests, PortalWorkerGeneration.capture(classpath.toString(), bundle, listOf(manifest.copy(isolationGroup = "other"))).manifests)
        assertNotEquals(expected.manifests, PortalWorkerGeneration.capture(classpath.toString(), bundle, emptyList()).manifests)
    }

    @Test
    fun `host reads bounded real metadata without constructing guest classes`() {
        val manifest =
            PortalServiceManifest(PortalServiceId("service"), "missing.Service", "missing.Impl", "missing.Dispatcher", "schema", "group", PortalInstanceMode.STATELESS, "baseline", "artifact")
        val root = Files.createDirectories(directory.resolve("META-INF/mazewall/portal/services"))
        Files.writeString(root.parent.resolve("services.index"), "service\n")
        val record = Files.writeString(root.resolve("service"), PortalServiceManifestCodec.encode(manifest))
        assertEquals(listOf(manifest), PortalWorkerGeneration.readManifests(directory.toString()))
        Files.writeString(record, "x".repeat(4097))
        assertThrows(IllegalArgumentException::class.java) { PortalWorkerGeneration.readManifests(directory.toString()) }
        Files.writeString(record, PortalServiceManifestCodec.encode(manifest))
        Files.writeString(root.parent.resolve("services.index"), "service\nservice\n")
        assertThrows(IllegalArgumentException::class.java) { PortalWorkerGeneration.readManifests(directory.toString()) }
        Files.delete(record)
        Files.writeString(root.parent.resolve("services.index"), "service\n")
        assertThrows(IllegalArgumentException::class.java) { PortalWorkerGeneration.readManifests(directory.toString()) }
    }
}
