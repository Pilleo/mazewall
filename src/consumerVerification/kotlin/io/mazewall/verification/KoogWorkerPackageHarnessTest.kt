package io.mazewall.verification

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class KoogWorkerPackageHarnessTest {
    @TempDir
    private lateinit var root: Path

    @Test
    fun `opaque packaging admits correct closures and rejects real member hash and manifest mutations`() {
        val implementation = "io/mazewall/demo/koog/worker/AsciiTextTransformer.class"
        val dispatcher = "io/mazewall/demo/koog/TextTransformerServicePortalDispatcher.class"
        val denied = "io/mazewall/consumer/koog/worker/DeniedResourceProbe.class"
        val production = setOf(implementation, dispatcher)

        fun host(
            name: String,
            classes: Set<String>,
            services: String = "bounded-text\n",
            format: String = "2",
            badDigest: Boolean = false,
            hostGuest: Boolean = false,
        ): Path {
            val bundle = zip(mapOf("lib/worker.jar" to zip(classes.associateWith { byteArrayOf(1) })))
            val digest = if (badDigest) "0".repeat(64) else MessageDigest.getInstance("SHA-256").digest(bundle).joinToString("") { "%02x".format(it) }
            val resources = mapOf(
                "META-INF/mazewall/portal/services.index" to services.toByteArray(),
                "META-INF/mazewall/portal/worker-artifacts/bounded-text-worker" to "format=$format\nresource=worker.zip\nsha256=$digest\n".toByteArray(),
                "worker.zip" to bundle,
            ) +
                if (hostGuest) mapOf(implementation to byteArrayOf(1)) else emptyMap()
            return Files.write(root.resolve("$name.jar"), zip(resources))
        }
        KoogWorkerPackage.verify(host("production", production), KoogPackageKind.PRODUCTION)
        KoogWorkerPackage.verify(host("fixture", production + denied, "bounded-text\ntest-denied\n"), KoogPackageKind.NATIVE_FIXTURE)
        for ((name, path) in listOf(
            "missing-implementation" to host("missing-implementation", setOf(dispatcher)),
            "missing-dispatcher" to host("missing-dispatcher", setOf(implementation)),
            "koog-leak" to host("koog-leak", production + "ai/koog/Unexpected.class"),
            "spring-leak" to host("spring-leak", production + "org/springframework/Unexpected.class"),
            "host-tool-leak" to host("host-tool-leak", production + "io/mazewall/demo/koog/TextTools.class"),
            "unadvertised-denied" to host("unadvertised-denied", production + denied),
            "services" to host("services", production, "test-denied\nbounded-text\n"),
            "format" to host("format", production, format = "1"),
            "digest" to host("digest", production, badDigest = true),
            "host-guest" to host("host-guest", production, hostGuest = true),
        )) {
            assertThrows<IllegalStateException>(name) { KoogWorkerPackage.verify(path, KoogPackageKind.PRODUCTION) }
        }
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { archive ->
            for ((name, bytes) in entries) {
                archive.putNextEntry(ZipEntry(name))
                archive.write(bytes)
                archive.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
