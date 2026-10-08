package io.mazewall.portal

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

/** A real child-only service graph and metadata; the typed host stub still exercises builtin RPC. */
internal fun ownedEchoWorkerFixture(
    directory: Path,
    manifest: PortalServiceManifest,
): Path {
    val source = Files.createDirectory(directory.resolve("fixture-source"))
    val classes = Files.createDirectory(directory.resolve("fixture-classes"))
    val sources = mapOf(
        "OwnedEchoService.java" to "package io.mazewall.portal; public interface OwnedEchoService { String echo(String value); }",
        "OwnedEchoImpl.java" to
            "package fixture; public class OwnedEchoImpl implements io.mazewall.portal.OwnedEchoService { public OwnedEchoImpl() {} public String echo(String value) { return value; } }",
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
