package io.mazewall.portal

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.tools.ToolProvider

interface ValidatedReplyService {
    fun call(mode: String): String
}

class ValidatedReplyServicePortalStub(
    private val broker: ProcessBroker,
) : ValidatedReplyService {
    override fun call(mode: String): String {
        val value =
            broker.invokeValidated(PortalMethod.Generated(4242), PortalCodec.encodeString(mode)) { bytes ->
                val reader = PortalCodec.Reader(bytes, "io.mazewall.portal.ValidatedReplyService.call.return")
                val decoded = reader.string()
                reader.requireExhausted()
                decoded
            }
        if (value == "invalid-dto") throw IllegalArgumentException("rejected by dto")
        return value
    }
}

internal fun validatedReplyWorkerBundle(
    directory: Path,
    manifest: PortalServiceManifest,
): PortalWorkerArtifact {
    val work = Files.createTempDirectory(directory, "validated-reply-")
    val source = Files.createDirectory(work.resolve("fixture-source"))
    val classes = Files.createDirectory(work.resolve("fixture-classes"))
    val sources = mapOf(
        "ValidatedReplyService.java" to "package io.mazewall.portal; public interface ValidatedReplyService { String call(String mode); }",
        "ValidatedReplyImpl.java" to """
            package fixture;
            import java.util.concurrent.atomic.AtomicInteger;
            public class ValidatedReplyImpl implements io.mazewall.portal.ValidatedReplyService {
                private final AtomicInteger calls = new AtomicInteger();
                public ValidatedReplyImpl() {}
                public String call(String mode) {
                    return "pid=" + ProcessHandle.current().pid() + ",calls=" + calls.incrementAndGet() + ",mode=" + mode;
                }
            }
        """.trimIndent(),
        "ValidatedReplyPortalDispatcher.java" to """
            package fixture;
            import java.nio.ByteBuffer;
            import java.nio.ByteOrder;
            import java.nio.charset.StandardCharsets;
            public class ValidatedReplyPortalDispatcher {
                public static final ValidatedReplyPortalDispatcher INSTANCE = new ValidatedReplyPortalDispatcher();
                public int[] getMETHOD_IDS() { return new int[] {4242}; }
                public byte[] handle(Object service, Object method, byte[] payload, java.util.List<?> capabilities) {
                    String mode = decodeString(payload);
                    String observation = ((io.mazewall.portal.ValidatedReplyService) service).call(mode);
                    if ("malformed-trailing".equals(mode)) {
                        byte[] encoded = encodeString(observation);
                        byte[] out = java.util.Arrays.copyOf(encoded, encoded.length + 1);
                        out[encoded.length] = 9;
                        return out;
                    }
                    if ("malformed-bool".equals(mode)) {
                        return new byte[] {2};
                    }
                    if ("remote-business-error".equals(mode)) {
                        throw new IllegalArgumentException("business failure");
                    }
                    if ("host-dto-reject".equals(mode)) {
                        return encodeString("invalid-dto");
                    }
                    return encodeString(observation);
                }
                private static String decodeString(byte[] payload) {
                    ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
                    int len = buf.getInt();
                    byte[] raw = new byte[len];
                    buf.get(raw);
                    if (buf.hasRemaining()) throw new IllegalArgumentException("trailing fixture bytes");
                    return new String(raw, StandardCharsets.UTF_8);
                }
                private static byte[] encodeString(String value) {
                    byte[] raw = value.getBytes(StandardCharsets.UTF_8);
                    return ByteBuffer.allocate(4 + raw.length).order(ByteOrder.BIG_ENDIAN).putInt(raw.length).put(raw).array();
                }
            }
        """.trimIndent(),
    ).map { (name, text) -> source.resolve(name).also { Files.writeString(it, text) } }
    val arguments = listOf("-d", classes.toString()) + sources.map(Path::toString)
    check(ToolProvider.getSystemJavaCompiler().run(null, null, null, *arguments.toTypedArray()) == 0) { "validated reply fixture Java compilation failed" }
    val jar = work.resolve("validated-reply-fixture.jar")
    JarOutputStream(Files.newOutputStream(jar)).use { output ->
        Files.walk(classes).use { paths ->
            paths.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                output.putNextEntry(JarEntry(classes.relativize(file).toString()))
                Files.copy(file, output)
                output.closeEntry()
            }
        }
        mapOf(
            "META-INF/mazewall/portal/services.index" to "${manifest.serviceId.value}\n",
            "META-INF/mazewall/portal/services/${manifest.serviceId.value}" to PortalServiceManifestCodec.encode(manifest),
            "META-INF/mazewall/portal/${manifest.contractName}" to "${manifest.dispatcherName}\n",
        ).forEach { (name, text) ->
            output.putNextEntry(JarEntry(name))
            output.write(text.toByteArray(Charsets.UTF_8))
            output.closeEntry()
        }
    }
    val bundle = work.resolve("worker.zip")
    ZipOutputStream(Files.newOutputStream(bundle)).use { zip ->
        System.getProperty("io.mazewall.portal.worker.classpath").split(File.pathSeparator).forEachIndexed { index, file ->
            val path = Path.of(file)
            check(Files.isRegularFile(path) && file.endsWith(".jar")) { "integration worker classpath must contain packaged JARs" }
            zip.putNextEntry(JarEntry("lib/$index-${path.fileName}"))
            Files.copy(path, zip)
            zip.closeEntry()
        }
        zip.putNextEntry(JarEntry("lib/validated-reply-fixture.jar"))
        Files.copy(jar, zip)
        zip.closeEntry()
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(bundle)).joinToString("") { "%02x".format(Locale.ROOT, it) }
    return PortalWorkerArtifact(bundle, digest)
}

internal data class ValidatedReplyBrokerFixture(
    val manifest: PortalServiceManifest,
    val config: PortalRuntimeConfig,
    val brokerClasspath: String,
    val workerLaunch: PortalWorkerLaunch,
)

internal fun validatedReplyBrokerFixture(
    directory: Path,
    mode: PortalInstanceMode = PortalInstanceMode.STATELESS,
    limits: PortalRuntimeLimits = PortalRuntimeLimits(),
): ValidatedReplyBrokerFixture {
    val manifest = PortalServiceManifest(
        PortalServiceId("validated-reply"),
        ValidatedReplyService::class.java.name,
        "fixture.ValidatedReplyImpl",
        "fixture.ValidatedReplyPortalDispatcher",
        "validated-reply-fixture",
        "validated-reply",
        mode,
        "restricted",
        "worker",
    )
    val artifact = validatedReplyWorkerBundle(directory, manifest)
    val materialized = WorkerBundleMaterializer.materialize(artifact, Files.createTempDirectory(directory, "portal-cache-"))
    val classpath = WorkerBundleMaterializer.classpath(materialized.directory)
    val config = PortalRuntimeConfig(
        listOf(manifest),
        mapOf("worker" to artifact),
        Files.createTempDirectory(directory, "runtime-cache-"),
        mapOf("restricted" to PortalWorkerProfile()),
        limits = limits,
    )
    return ValidatedReplyBrokerFixture(
        manifest = manifest,
        config = config,
        brokerClasspath = classpath,
        workerLaunch = PortalWorkerLaunch(listOf(manifest), artifact),
    )
}

/** A real child-only service graph and metadata; the typed host stub still exercises builtin RPC. */
internal fun ownedEchoWorkerFixture(
    directory: Path,
    manifest: PortalServiceManifest,
    echoExpression: String = "value",
): Path {
    val source = Files.createDirectory(directory.resolve("fixture-source"))
    val classes = Files.createDirectory(directory.resolve("fixture-classes"))
    val sources = mapOf(
        "OwnedEchoService.java" to "package io.mazewall.portal; public interface OwnedEchoService { String echo(String value); }",
        "OwnedEchoImpl.java" to
            "package fixture; public class OwnedEchoImpl implements io.mazewall.portal.OwnedEchoService { public OwnedEchoImpl() {} public String echo(String value) { return $echoExpression; } }",
        "OwnedEchoPortalDispatcher.java" to """
            package fixture;
            public class OwnedEchoPortalDispatcher {
                public static final OwnedEchoPortalDispatcher INSTANCE = new OwnedEchoPortalDispatcher();
                public int[] getMETHOD_IDS() { return new int[] {4242}; }
                public byte[] handle(Object service, Object method, byte[] payload, java.util.List<?> capabilities) {
                    return ((io.mazewall.portal.OwnedEchoService) service).echo(new String(payload, java.nio.charset.StandardCharsets.UTF_8)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        """.trimIndent(),
    ).map { (name, text) -> source.resolve(name).also { Files.writeString(it, text) } }
    val arguments = listOf("-d", classes.toString()) + sources.map(Path::toString)
    check(ToolProvider.getSystemJavaCompiler().run(null, null, null, *arguments.toTypedArray()) == 0) { "worker fixture Java compilation failed" }
    val jar = directory.resolve("owned-echo-fixture.jar")
    JarOutputStream(Files.newOutputStream(jar)).use { output ->
        Files.walk(classes).use { paths ->
            paths.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                output.putNextEntry(JarEntry(classes.relativize(file).toString()))
                Files.copy(file, output)
                output.closeEntry()
            }
        }
        mapOf(
            "META-INF/mazewall/portal/services.index" to "${manifest.serviceId.value}\n",
            "META-INF/mazewall/portal/services/${manifest.serviceId.value}" to PortalServiceManifestCodec.encode(manifest),
            "META-INF/mazewall/portal/${manifest.contractName}" to "${manifest.dispatcherName}\n",
        ).forEach { (name, text) ->
            output.putNextEntry(JarEntry(name))
            output.write(text.toByteArray(Charsets.UTF_8))
            output.closeEntry()
        }
    }
    return jar
}
