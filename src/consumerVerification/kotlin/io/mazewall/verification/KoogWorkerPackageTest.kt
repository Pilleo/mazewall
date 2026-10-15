package io.mazewall.verification

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.security.MessageDigest
import java.util.jar.JarFile
import java.util.zip.ZipInputStream

internal enum class KoogPackageKind { PRODUCTION, NATIVE_FIXTURE }

internal object KoogWorkerPackage {
    fun verify(
        path: Path,
        kind: KoogPackageKind,
    ) {
        val fixture = when (kind) {
            KoogPackageKind.PRODUCTION -> false
            KoogPackageKind.NATIVE_FIXTURE -> true
        }
        JarFile(path.toFile()).use { host ->
            fun resource(name: String): ByteArray {
                val entry = host.getJarEntry(name) ?: error("Missing host package resource: $name")
                return host.getInputStream(entry).use { it.readAllBytes() }
            }
            val services = String(resource("META-INF/mazewall/portal/services.index")).lines().filter { it.isNotEmpty() }
            check(services == if (fixture) listOf("bounded-text", "test-denied") else listOf("bounded-text")) { "Incorrect service inventory: $services" }
            val metadata = String(resource("META-INF/mazewall/portal/worker-artifacts/bounded-text-worker")).lines().filter(String::isNotEmpty).associate {
                val fields = it.split('=', limit = 2)
                check(fields.size == 2) { "Invalid worker metadata" }
                fields[0] to fields[1]
            }
            check(metadata["format"] == "2") { "Worker metadata is not format2" }
            val bundle = resource(metadata.getValue("resource"))
            val sha = MessageDigest.getInstance("SHA-256").digest(bundle).joinToString("") { "%02x".format(it) }
            check(sha == metadata["sha256"]) { "Worker bundle digest mismatch" }
            check(host.entries().asSequence().none { "/worker/" in it.name && it.name.endsWith(".class") }) { "Guest implementation is loadable in host" }
            val classes = mutableSetOf<String>()
            var jars = 0
            ZipInputStream(ByteArrayInputStream(bundle)).use { worker ->
                while (true) {
                    val entry = worker.nextEntry ?: break
                    check(entry.name.startsWith("lib/") && entry.name.endsWith(".jar") && !entry.isDirectory) { "Invalid worker member: ${entry.name}" }
                    jars++
                    ZipInputStream(ByteArrayInputStream(worker.readAllBytes())).use { jar ->
                        while (true) classes.add((jar.nextEntry ?: break).name)
                    }
                }
            }
            check(classes.none { it.startsWith("ai/koog/") || it.startsWith("org/springframework/") }) { "Host frameworks leaked into worker" }
            check("io/mazewall/demo/koog/worker/AsciiTextTransformer.class" in classes) { "Missing worker implementation" }
            check("io/mazewall/demo/koog/TextTransformerServicePortalDispatcher.class" in classes) { "Missing generated dispatcher" }
            check(("io/mazewall/consumer/koog/worker/DeniedResourceProbe.class" in classes) == fixture) { "Unexpected denied-probe package selection" }
            check("io/mazewall/demo/koog/TextTools.class" !in classes) { "Host TextTools leaked into worker" }
            println("OPAQUE_WORKER_PACKAGE_OK fixture=$fixture jars=$jars sha256=$sha")
        }
    }
}

@Tag("consumer-native")
class KoogWorkerPackageTest {
    @Test
    fun `actual native fixture keeps worker closure opaque to the host`() {
        val jar = Path.of(System.getProperty("consumerKoogJar") ?: error("Missing required -PconsumerKoogJar"))
        KoogWorkerPackage.verify(jar, KoogPackageKind.NATIVE_FIXTURE)
    }
}
