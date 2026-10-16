package io.mazewall.profiler.tierE.tools

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files

class TierERootfulGateProfileTest {
    @Test
    fun `pinned rootful profile rejects typo and repeated CPU before runner creation`() {
        val profile = Files.createTempFile("tier-e-rootful-profile-", ".json")
        try {
            val input = """{"schema_version":1,"container":{"engine":"docker","image":"mazewall-tier-e-rootful:latest"},"cpu_set":"0,1"}"""
            Files.writeString(profile, input)
            assertEquals("0,1", TierERootfulGateProfile.load(profile).cpuSet)
            Files.writeString(profile, input.replace("\"cpu_set\":\"0,1\"", "\"cpu_set\":\"0,0\""))
            assertThrows(IllegalArgumentException::class.java) { TierERootfulGateProfile.load(profile) }
            Files.writeString(profile, input.replace("\"cpu_set\"", "\"cpus_typo\""))
            assertThrows(IllegalArgumentException::class.java) { TierERootfulGateProfile.load(profile) }
            Files.writeString(profile, input.replace("\"mazewall-tier-e-rootful:latest\"", "42"))
            assertThrows(IllegalArgumentException::class.java) { TierERootfulGateProfile.load(profile) }
            Files.writeString(profile, input.replace("\"schema_version\":1", "\"schema_version\":\"1\""))
            assertThrows(IllegalArgumentException::class.java) { TierERootfulGateProfile.load(profile) }
        } finally {
            Files.deleteIfExists(profile)
        }
    }
}
