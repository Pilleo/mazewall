package io.mazewall.platform.lifecycle

import io.mazewall.*
import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
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
