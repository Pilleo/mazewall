package io.mazewall.enforcer.api.execution

import io.mazewall.platform.process.JvmChildProcess
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreparedSystemLoaderReviewTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `custom system loader reporting original jar while defining counterfeit bytes rejects`() = runProbe(true)

    @Test
    fun `ordinary JDK application system loader still resolves its actual finished jar`() = runProbe(false)

    private fun runProbe(custom: Boolean) {
        val original = compile(42, "original")
        val counterfeit = compile(99, "counterfeit")
        val jar = directory.resolve("operation.jar")
        JarOutputStream(Files.newOutputStream(jar)).use {
            it.putNextEntry(JarEntry("fixture/Operation.class"))
            it.write(Files.readAllBytes(original))
            it.closeEntry()
        }
        val command = mutableListOf(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--enable-native-access=ALL-UNNAMED",
            "-Dtask5.original.jar=$jar",
            "-Dtask5.counterfeit.bytes=$counterfeit",
        )
        if (custom) command.add("-Djava.system.class.loader=io.mazewall.execution.ReviewCustomSystemLoader")
        command.addAll(
            listOf(
                "-cp",
                System.getProperty("java.class.path") + File.pathSeparator + jar,
            "io.mazewall.execution.ReviewSystemLoaderProbe",
                if (custom) "custom" else "ordinary",
            ),
        )
        val builder = ProcessBuilder(command).redirectErrorStream(true)
        JvmChildProcess.stripInheritedJvmOptions(builder.environment())
        val process = builder.start()
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "System-loader probe must finish")
            val output = process.inputStream.readNBytes(65536).toString(Charsets.UTF_8)
            assertEquals(0, process.exitValue(), output)
        } finally {
            if (process.isAlive) process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Probe child must be reaped")
        }
    }

    private fun compile(
        value: Int,
        name: String,
    ): Path {
        val output = Files.createDirectory(directory.resolve(name))
        val source = Files.writeString(output.resolve("Operation.java"), "package fixture; public class Operation { public int call() { return $value; } }")
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", output.toString(), source.toString()))
        return output.resolve("fixture/Operation.class")
    }
}
