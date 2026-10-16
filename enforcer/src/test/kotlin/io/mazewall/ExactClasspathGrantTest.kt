package io.mazewall.enforcer.api

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

class ExactClasspathGrantTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `classpath files remain exact while resource directories retain directory scope`() {
        val artifact = Files.writeString(directory.resolve("parser.jar"), "artifact")
        val resources = Files.createDirectory(directory.resolve("resources"))
        val previous = System.getProperty("java.class.path")
        try {
            System.setProperty("java.class.path", artifact.toString() + File.pathSeparator + resources)
            val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowJvmClasspath().build()
            val local = policy.definition.allowedFsReadPaths
                .map { it.value }
                .filter { it.startsWith(directory.toString()) }
                .toSet()
            assertEquals(setOf(artifact.toString(), resources.toString()), local)
        } finally {
            if (previous == null) System.clearProperty("java.class.path") else System.setProperty("java.class.path", previous)
        }
    }
}
