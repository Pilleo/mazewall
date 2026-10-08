package io.mazewall.enforcer.api.execution

import io.mazewall.platform.process.JvmChildProcess
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.CRC32
import java.util.zip.ZipInputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real finished artifacts and real Boot launcher; no synthetic URL handlers/loaders. */
internal object DeploymentPackagingFixture {
    fun compile(
        root: Path,
        inherited: Boolean = false,
    ): Path {
        val classes = Files.createDirectories(root.resolve("classes"))
        val base = Files.writeString(root.resolve("Base.java"), "package fixture; public class Base { public int call() { return 42; } }")
        val source = Files.writeString(
            root.resolve("Operation.java"),
            if (inherited) "package fixture; public class Operation extends Base {}" else "package fixture; public class Operation { public int call() { return 42; } }",
        )
        val sources = if (inherited) arrayOf(source.toString(), base.toString()) else arrayOf(source.toString())
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), *sources))
        Files.writeString(classes.resolve("application.properties"), "fixture=reviewed\n")
        return classes
    }

    fun boot(
        root: Path,
        library: Boolean,
        modifyLoader: Boolean = false,
        nestedMutation: String? = null,
        inherited: Boolean = false,
    ): Path {
        val classes = compile(root, inherited)
        val launch = Files.writeString(
            root.resolve("Launch.java"),
            """
            package fixture;
            public class Launch {
                public static void main(String[] args) throws Exception {
                    Class<?> operation = Class.forName("fixture.Operation");
                    System.out.println("ORIGIN=" + operation.getProtectionDomain().getCodeSource().getLocation());
                    System.out.println("RESOURCE=" + operation.getClassLoader().getResource("fixture/Operation.class"));
                    for (java.net.URL url : ((java.net.URLClassLoader) operation.getClassLoader()).getURLs()) System.out.println("INPUT=" + url);
                    java.net.URL[] urls = java.util.Arrays.stream(System.getProperty("task8.probe.classpath").split(java.io.File.pathSeparator))
                        .map(p -> { try { return java.nio.file.Path.of(p).toUri().toURL(); } catch (Exception e) { throw new RuntimeException(e); } })
                        .toArray(java.net.URL[]::new);
                    try (java.net.URLClassLoader probe = new java.net.URLClassLoader(urls, ClassLoader.getSystemClassLoader())) {
                        probe.loadClass(System.getProperty("task8.probe.class", "io.mazewall.enforcer.api.execution.DeploymentOriginProbe")).getMethod("run", Class.class, String[].class)
                            .invoke(null, operation, args);
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(), launch.toString()))
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes[Attributes.Name.MAIN_CLASS] = "org.springframework.boot.loader.launch.JarLauncher"
            mainAttributes.putValue("Start-Class", "fixture.Launch")
        }
        val jar = root.resolve("application.jar")
        JarOutputStream(Files.newOutputStream(jar), manifest).use { output ->
            ZipInputStream(checkNotNull(javaClass.getResourceAsStream("/packaging/spring-boot-loader-4.1.1.jar"))).use { input ->
                var entry = input.nextEntry
                while (entry != null) {
                    if (entry.name != "META-INF/MANIFEST.MF") {
                        val bytes = input.readAllBytes()
                        if (modifyLoader && entry.name == "org/springframework/boot/loader/launch/LaunchedClassLoader.class") {
                            // Harmless SourceFile mutation: executable but not supported original loader bytes.
                            val marker = "LaunchedClassLoader.java".toByteArray()
                            val index = bytes.indices.first { offset -> offset + marker.size <= bytes.size && marker.indices.all { bytes[offset + it] == marker[it] } }
                            bytes[index] = 'C'.code.toByte()
                        }
                        write(output, entry.name, bytes)
                    }
                    entry = input.nextEntry
                }
            }
            write(output, "BOOT-INF/classes/", byteArrayOf())
            write(output, "BOOT-INF/classes/fixture/Launch.class", Files.readAllBytes(classes.resolve("fixture/Launch.class")))
            if (library) {
                val inner = ByteArrayOutputStream()
                JarOutputStream(inner).use { nested ->
                    write(nested, "fixture/Operation.class", Files.readAllBytes(classes.resolve("fixture/Operation.class")))
                    write(nested, "application.properties", Files.readAllBytes(classes.resolve("application.properties")))
                    if (nestedMutation == "traversal") write(nested, "../outside.txt", "escape".toByteArray())
                    if (nestedMutation == "duplicate") write(nested, "fixture/Duplicate.class", Files.readAllBytes(classes.resolve("fixture/Operation.class")))
                }
                val innerBytes = inner.toByteArray()
                if (nestedMutation == "duplicate") replaceEntryName(innerBytes, "fixture/Duplicate.class", "fixture/Operation.class")
                write(output, "BOOT-INF/lib/operation.jar", innerBytes, true)
            } else {
                write(output, "BOOT-INF/classes/fixture/Operation.class", Files.readAllBytes(classes.resolve("fixture/Operation.class")))
                write(output, "BOOT-INF/classes/application.properties", Files.readAllBytes(classes.resolve("application.properties")))
            }
            if (inherited) {
                val base = ByteArrayOutputStream()
                JarOutputStream(base).use { write(it, "fixture/Base.class", Files.readAllBytes(classes.resolve("fixture/Base.class"))) }
                write(output, "BOOT-INF/lib/base.jar", base.toByteArray(), true)
            }
        }
        return jar
    }

    private fun replaceEntryName(
        bytes: ByteArray,
        original: String,
        replacement: String,
    ) {
        val before = original.toByteArray()
        val after = replacement.toByteArray()
        assertEquals(before.size, after.size)
        bytes.indices.filter { offset -> offset + before.size <= bytes.size && before.indices.all { bytes[offset + it] == before[it] } }.forEach { offset -> after.copyInto(bytes, offset) }
    }

    fun launch(
        jar: Path,
        vararg arguments: String,
        probeClass: String = "io.mazewall.enforcer.api.execution.DeploymentOriginProbe",
    ): String {
        val builder = ProcessBuilder(
            listOf(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED",
                "-Dtask8.probe.classpath=${System.getProperty("java.class.path")}",
                "-Dtask8.probe.class=$probeClass",
                "-jar",
                jar.toString(),
            ) +
                arguments,
        ).redirectErrorStream(true)
        JvmChildProcess.stripInheritedJvmOptions(builder.environment())
        val process = builder.start()
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Boot fixture must finish")
            val output = process.inputStream.readNBytes(65536).toString(Charsets.UTF_8)
            assertEquals(0, process.exitValue(), output)
            return output
        } finally {
            if (process.isAlive) process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Boot fixture must be physically reaped")
        }
    }

    private fun write(
        output: JarOutputStream,
        name: String,
        bytes: ByteArray,
        stored: Boolean = false,
    ) {
        val entry = JarEntry(name)
        if (stored) {
            entry.method = JarEntry.STORED
            entry.size = bytes.size.toLong()
            entry.compressedSize = entry.size
            entry.crc = CRC32().apply { update(bytes) }.value
        }
        output.putNextEntry(entry)
        output.write(bytes)
        output.closeEntry()
    }
}
