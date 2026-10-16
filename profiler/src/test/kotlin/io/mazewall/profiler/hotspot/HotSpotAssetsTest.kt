package io.mazewall.profiler.hotspot

import io.mazewall.profiler.internal.hotspot.HotSpotAssetTimings
import io.mazewall.profiler.internal.hotspot.HotSpotAssets
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class HotSpotAssetsTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `measured admission preserves the resolved asset contract`() {
        fixture()
        val timings = HotSpotAssetTimings()
        val measured = HotSpotAssets.load(directory, timings)
        assertEquals(HotSpotAssets.load(directory), measured)
        assertEquals(setOf("manifest", "checksums", "oci_graph", "collector"), timings.snapshot().keys)
        assertTrue(timings.snapshot().values.all { it >= 0L })
    }

    @Test
    fun `measured admission still refuses a tampered helper`() {
        fixture()
        Files.writeString(directory.resolve("mw-hotspot-collector"), "tampered")
        assertFailsWith<IllegalArgumentException> { HotSpotAssets.load(directory, HotSpotAssetTimings()) }
    }

    @Test
    fun `phase timing preserves failure and owns immutable snapshots`() {
        var tick = 0L
        val timings = HotSpotAssetTimings { tick++.times(10) }
        val failure = IllegalArgumentException("original validation failure")
        assertEquals(failure, assertFailsWith<IllegalArgumentException> { timings.measure("manifest") { throw failure } })
        val snapshot = timings.snapshot()
        assertEquals(10L, snapshot["manifest"])
        timings.measure("checksums") { Unit }
        assertEquals(setOf("manifest"), snapshot.keys)
        assertFailsWith<UnsupportedOperationException> { (snapshot as MutableMap)["manifest"] = 0L }
    }

    @Test
    fun `verified bundle resolves executable and local OCI store`() {
        fixture()
        val assets = HotSpotAssets.load(directory)
        assertEquals(directory.resolve("mw-hotspot-collector"), assets.collector)
        assertEquals(directory.resolve("oci"), assets.store)
        assertEquals("ghcr.io/inspektor-gadget/gadget/mw-hotspot-openat@sha256:$blobHash", assets.image)
    }

    @Test
    fun `incompatible schema version platform and capture ABI are refused`() {
        for ((field, replacement) in listOf("schema_version" to "2", "collector_version" to "\"stale\"", "capture_abi" to "1", "wire_schema" to "1", "platform" to "\"linux-arm64\"")) {
            fixture()
            val manifest = directory.resolve("manifest.json")
            val text = Files.readString(manifest)
            Files.writeString(manifest, text.replace(Regex("\"$field\"\\s*:\\s*(\"[^\"]*\"|[0-9]+)"), "\"$field\":$replacement"))
            assertFailsWith<IllegalArgumentException>(field) { HotSpotAssets.load(directory) }
        }
    }

    @Test
    fun `tampered executable or OCI blob is refused`() {
        for (name in listOf("mw-hotspot-collector", "oci/blobs/sha256/$blobHash")) {
            fixture()
            Files.writeString(directory.resolve(name), "tampered")
            assertFailsWith<IllegalArgumentException>(name) { HotSpotAssets.load(directory) }
        }
    }

    @Test
    fun `missing executable OCI index and required blob are refused`() {
        for (name in listOf("mw-hotspot-collector", "oci/index.json", "oci/blobs/sha256/$blobHash")) {
            fixture()
            Files.delete(directory.resolve(name))
            assertFailsWith<IllegalArgumentException>(name) { HotSpotAssets.load(directory) }
        }
    }

    @Test
    fun `non executable helper and incomplete manifest are refused`() {
        fixture()
        directory.resolve("mw-hotspot-collector").toFile().setExecutable(false)
        assertFailsWith<IllegalArgumentException> { HotSpotAssets.load(directory) }
        fixture()
        Files.writeString(directory.resolve("manifest.json"), "{}")
        assertFailsWith<IllegalArgumentException> { HotSpotAssets.load(directory) }
    }

    @Test
    fun `escaping and symbolic link asset paths are refused`() {
        fixture()
        val manifest = directory.resolve("manifest.json")
        Files.writeString(manifest, Files.readString(manifest).replace("oci/index.json", "../outside"))
        assertFailsWith<IllegalArgumentException> { HotSpotAssets.load(directory) }
        fixture()
        val helper = directory.resolve("mw-hotspot-collector")
        Files.move(helper, directory.resolve("other"))
        Files.createSymbolicLink(helper, Path.of("other"))
        assertFailsWith<IllegalArgumentException> { HotSpotAssets.load(directory) }
    }

    @Test
    fun `oversized manifest is refused before parsing`() {
        fixture()
        Files.writeString(directory.resolve("manifest.json"), " ".repeat(65537))
        assertFailsWith<IllegalArgumentException> { HotSpotAssets.load(directory) }
    }

    @Test
    fun `unlisted transitive BPF data is refused even when root digest matches`() {
        fixture()
        val manifest = directory.resolve("manifest.json")
        val entry = "\"oci/blobs/sha256/$layerHash\":\"$layerHash\","
        Files.writeString(manifest, Files.readString(manifest).replace(entry, ""))
        assertFailsWith<IllegalArgumentException> { HotSpotAssets.load(directory) }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MW_HOTSPOT_ASSET_TEST_DIR", matches = ".+")
    fun `installed native bundle passes Kotlin admission`() {
        val installed = Path.of(requireNotNull(System.getenv("MW_HOTSPOT_ASSET_TEST_DIR")))
        val assets = HotSpotAssets.load(installed)
        assertEquals(installed.resolve("mw-hotspot-collector"), assets.collector)
        assertEquals(installed.resolve("oci"), assets.store)
    }

    private fun fixture() {
        val contents = mapOf(
            "mw-hotspot-collector" to "collector fixture",
            "oci/oci-layout" to "{\"imageLayoutVersion\":\"1.0.0\"}",
            "oci/index.json" to "{\"schemaVersion\":2,\"manifests\":[$rootDescriptor]}",
            "oci/blobs/sha256/$layerHash" to layer,
            "oci/blobs/sha256/$configHash" to config,
                "oci/blobs/sha256/$blobHash" to blob,
        )
        for ((name, body) in contents) {
            val path = directory.resolve(name)
            Files.createDirectories(path.parent)
            Files.deleteIfExists(path)
            Files.writeString(path, body)
        }
        directory.resolve("mw-hotspot-collector").toFile().setExecutable(true)
        val files = contents.entries.joinToString(",") { (name, body) -> "\"$name\":\"${hash(body)}\"" }
        Files.writeString(
            directory.resolve("manifest.json"),
            """
            {"schema_version":1,"collector_version":"2","capture_abi":2,"wire_schema":2,
             "platform":"linux-amd64","gadget_revision":"7097db144ccd8dab15373f27ec98d2c3d22e4588",
             "profiler_revision":"ef6cf29492c8a980f889052903fa2dd0c0765cc0",
             "patch_sha256":"618c3d67d71e86dfb0befb13f73498402af6110f1f2fceb3784c53785b0da00c",
             "gadget_digest":"sha256:$blobHash","files":{$files}}
        """.trimIndent(),
        )
    }

    private fun hash(text: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))

    private val layer = "native BPF fixture"
    private val layerHash = hash(layer)
    private val config = "{}"
    private val configHash = hash(config)
    private val blob = """
        {"schemaVersion":2,"config":{"digest":"sha256:$configHash","size":${config.length},"mediaType":"application/vnd.gadget.config.v1+json"},
        "layers":[{"digest":"sha256:$layerHash","size":${layer.length},"mediaType":"application/vnd.gadget.ebpf.program.v1+binary"}]}
    """.trimIndent()
    private val blobHash = hash(blob)
    private val rootDescriptor = """{"digest":"sha256:$blobHash","size":${blob.length},"mediaType":"application/vnd.oci.image.manifest.v1+json","platform":{"os":"linux","architecture":"amd64"}}"""
}
