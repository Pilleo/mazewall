package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TierERootfulPinnedImageAdmissionTest {
    @TempDir lateinit var directory: Path
    private val imageId = "sha256:${"a".repeat(64)}"
    private val containerfileSha = "b".repeat(64)

    @Test
    fun `version two profile pins immutable image ID without changing version one parsing`() {
        val input = """{"schema_version":2,"container":{"engine":"docker","image":"mazewall-tier-e-rootful:perf-20260919-external-profiler"},"cpu_set":"0,2","image_id":"$imageId"}"""
        val path = directory.resolve("profile.json")
        Files.writeString(path, input)
        val pinned = TierERootfulPinnedImageProfile.load(path)
        assertEquals(imageId, pinned.imageId)
        assertEquals("0,2", pinned.profile.cpuSet)
        assertThrows(IllegalArgumentException::class.java) { TierERootfulGateProfile.load(path) }
        Files.writeString(path, """{"schema_version":1,"container":{"engine":"docker","image":"mazewall-tier-e-rootful:latest"},"cpu_set":"0,2"}""")
        assertEquals("0,2", TierERootfulGateProfile.load(path).cpuSet)
        assertThrows(IllegalArgumentException::class.java) { TierERootfulPinnedImageProfile.load(path) }
        Files.writeString(path, input.replace("$imageId", "sha256:bad"))
        assertThrows(IllegalArgumentException::class.java) { TierERootfulPinnedImageProfile.load(path) }
        Files.writeString(path, input.replace("\"cpu_set\"", "\"cpu_set_typo\""))
        assertThrows(IllegalArgumentException::class.java) { TierERootfulPinnedImageProfile.load(path) }
    }

    @Test
    fun `explicit matching pin uses image by ID while legacy unlabeled image still rebuilds`() {
        val pinned = TierERootfulImageAdmission.select("tag", imageId, "<no value>", containerfileSha, imageId)
        assertEquals(imageId, pinned.imageReference)
        assertFalse(pinned.rebuild)
        assertThrows(IllegalStateException::class.java) {
            TierERootfulImageAdmission.select("tag", imageId, "<no value>", containerfileSha, "sha256:${"c".repeat(64)}")
        }
        val legacy = TierERootfulImageAdmission.select("tag", imageId, "<no value>", containerfileSha, null)
        assertEquals("tag", legacy.imageReference)
        assertTrue(legacy.rebuild)
        assertFalse(TierERootfulImageAdmission.select("tag", imageId, containerfileSha, containerfileSha, null).rebuild)
    }
}
