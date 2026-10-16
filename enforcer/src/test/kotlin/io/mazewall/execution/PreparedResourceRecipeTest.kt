package io.mazewall.enforcer.api.execution

import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.internal.execution.PreparedResourceSet
import io.mazewall.platform.native.LinuxNative
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class PreparedResourceRecipeTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `writable ancestry cannot cancel explicit read only intent`() {
        val template = Files.writeString(directory.resolve("template.txt"), "template")
        val policy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.HOTSPOT_JIT)
            .allowFsRead(template)
            .allowFsWrite(directory)
            .build()
        assertFailsWith<IllegalArgumentException> { PreparedResourceSet.capture(policy.definition, LinuxNative.snapshot(), setOf(template)) }
    }

    @Test
    fun `hard linked writable alias contradicts explicit read only file`() {
        val template = Files.writeString(directory.resolve("template.txt"), "template")
        val alias = Files.createLink(directory.resolve("alias.txt"), template)
        val policy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.HOTSPOT_JIT)
            .allowFsRead(template)
            .allowFsWrite(alias)
            .build()
        assertFailsWith<IllegalArgumentException> { PreparedResourceSet.capture(policy.definition, LinuxNative.snapshot(), setOf(template)) }
    }

    @Test
    fun `ordinary read allowances permit writable overlap but strict recipe detects content mutation`() {
        val template = Files.writeString(directory.resolve("template.txt"), "template")
        val writable = WorkerPolicyBuilder
            .denyList(RuntimeProfile.HOTSPOT_JIT)
            .allowFsRead(template)
            .allowFsWrite(directory)
            .build()
        PreparedResourceSet.capture(writable.definition, LinuxNative.snapshot()).use { it.validate() }
        val read = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowFsRead(template).build()
        PreparedResourceSet.capture(read.definition, LinuxNative.snapshot(), setOf(template)).use { strict ->
            val generation = strict.generation
            Files.writeString(template, "mutated!")
            assertFailsWith<IllegalStateException> { strict.validate() }
            PreparedResourceSet.capture(read.definition, LinuxNative.snapshot(), setOf(template)).use { changed -> assertNotEquals(generation, changed.generation) }
        }
    }

    @Test
    fun `writable output directory cannot hide a hard linked read only inode`() {
        val template = Files.writeString(directory.resolve("template.txt"), "template")
        val output = Files.createDirectory(directory.resolve("output"))
        Files.createLink(output.resolve("alias.txt"), template)
        val policy = WorkerPolicyBuilder
            .denyList(RuntimeProfile.HOTSPOT_JIT)
            .allowFsRead(template)
            .allowFsWrite(output)
            .build()
        assertFailsWith<IllegalArgumentException> { PreparedResourceSet.capture(policy.definition, LinuxNative.snapshot(), setOf(template)) }
    }
}
