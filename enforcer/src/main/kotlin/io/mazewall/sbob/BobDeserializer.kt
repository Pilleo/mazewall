package io.mazewall.sbob

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*

import io.mazewall.BillOfBehaviorDto
import kotlinx.serialization.json.Json

/**
 * Handles deserialization of Software Bill of Behavior (SBoB) JSON data.
 */
internal object BobDeserializer {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun deserialize(content: String): BillOfBehaviorDto {
        return json.decodeFromString(BillOfBehaviorDto.serializer(), content)
    }
}
