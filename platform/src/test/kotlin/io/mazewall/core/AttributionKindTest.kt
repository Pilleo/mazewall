package io.mazewall.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class AttributionKindTest {

    @Test
    fun `exact kind set is stable - wire consumers depend on it`() {
        val names = AttributionKind.entries.map { it.name }
        assertEquals(listOf("NONE", "EXPLICIT_CONTEXT", "AGENT_CONTEXT", "USER_NOTIF_ORACLE"), names)
    }

    @Test
    fun `oracle kind exists alongside tier e kinds`() {
        assertEquals("USER_NOTIF_ORACLE", AttributionKind.USER_NOTIF_ORACLE.name)
    }
}
