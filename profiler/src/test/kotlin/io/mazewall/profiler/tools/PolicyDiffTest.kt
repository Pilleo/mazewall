package io.mazewall.profiler.tools

import io.mazewall.enforcer.api.CanonicalPolicyArtifact
import io.mazewall.enforcer.api.CoverageFacet
import io.mazewall.enforcer.api.OperationAdapter
import io.mazewall.enforcer.api.OperationBinding
import io.mazewall.enforcer.api.OperationPolicyDocument
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.platform.syscall.Syscall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PolicyDiffTest {
    @TempDir private lateinit var root: Path

    @Test fun `comparison reports exact policy identity and coverage fields without ordering claims`() {
        val old = document(false)
        val changed = PolicyDiff.compare(old, document(true))
        for (field in listOf(
                "binding.implementationSha256",
                "binding.resourceGeneration",
                "policy.runtime",
                "policy.syscalls",
                "policy.arguments.executableMappings",
                "policy.filesystem.read",
                "policy.filesystem.write",
                "missingCoverage",
                "reviewedExceptions.FILE_PATHS",
            )) {
            assertTrue(changed.contains(field), changed)
        }
        assertTrue(changed.contains("not approval"), changed)
        assertTrue(PolicyDiff.compare(old, old).contains("NO_DOCUMENT_CHANGES"))
        assertEquals(changed, PolicyDiff.compare(old, OperationPolicyDocument.fromJson(document(true).toJson())))
    }

    @Test fun `bounded reader accepts exact limit and rejects larger malformed duplicate and invalid documents`() {
        val path = root.resolve("operation.json")
        val json = document(false).toJson()
        Files.writeString(path, json + " ".repeat(2_097_152 - json.toByteArray().size))
        assertEquals(json, PolicyDiff.read(path).toJson())
        Files.writeString(path, " ".repeat(2_097_153))
        assertThrows(IllegalArgumentException::class.java) { PolicyDiff.read(path) }
        Files.write(path, byteArrayOf(0xc3.toByte(), 0x28))
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { PolicyDiff.read(path) }
        for (bad in listOf("{}", json.replace("\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1"), json.replace("\"formatVersion\":1", "\"formatVersion\":999"))) {
            Files.writeString(path, bad)
            assertThrows(IllegalArgumentException::class.java) { PolicyDiff.read(path) }
        }
    }

    private fun document(changed: Boolean): OperationPolicyDocument {
        val runtime = if (changed) RuntimeProfile.NATIVE_IMAGE else RuntimeProfile.HOTSPOT_JIT
        val builder = WorkerPolicyBuilder.denyList(runtime).allowMmapExec(changed).allowFsRead(root.resolve(if (changed) "broader" else "one"))
        if (changed) builder.allowMmapExec().allowFsWrite(root.resolve("new-output")).block(Syscall.SOCKET)
        val binding = OperationBinding(
            "reader",
            OperationAdapter.CALLABLE,
            (if (changed) "b" else "a").repeat(64),
            if (changed) "g2" else "g1",
            mapOf("entryPoint" to "readDocument"),
        )
        return OperationPolicyDocument(
            binding,
            CanonicalPolicyArtifact.worker(runtime, builder.build()),
            if (changed) setOf(CoverageFacet.FILE_PATHS) else emptySet(),
            if (changed) mapOf(CoverageFacet.FILE_PATHS to "explicit remaining gap") else emptyMap(),
        )
    }
}
