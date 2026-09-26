package io.mazewall.profiler.tierE.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TierECoverageStackIdentityTest {
    private fun stacks(
        loader: String,
        name: String,
    ): kotlinx.serialization.json.JsonObject = Json.parseToJsonElement("""{"0":[["257","0","COMPLETE","$loader","$name","invoke","()V","10","JAVA"]]}""").jsonObject

    @Test
    fun `loader numbers and hidden addresses are process local but distinct hidden classes stay distinct`() {
        assertEquals(
            TierECoverageStackIdentity.normalize(stacks("loader-3", "Owner\$\$Lambda.0xaaaa")),
            TierECoverageStackIdentity.normalize(stacks("loader-7", "Owner\$\$Lambda.0xbbbb")),
        )
        val separate = Json
            .parseToJsonElement(
            """{"0":[["257","0","COMPLETE","loader-3","Owner.0xaaaa","invoke","()V","10","JAVA"]],"1":[["257","0","COMPLETE","loader-3","Owner.0xbbbb","invoke","()V","10","JAVA"]]}""",
        ).jsonObject
        val result = TierECoverageStackIdentity.normalize(separate)
        assertNotEquals(result.getValue("0"), result.getValue("1"))
        assertThrows(IllegalArgumentException::class.java) {
            TierECoverageStackIdentity.normalize(Json.parseToJsonElement("""{"0":[["257"]]}""").jsonObject)
        }
    }
}
