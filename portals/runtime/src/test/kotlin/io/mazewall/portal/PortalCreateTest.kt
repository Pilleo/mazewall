package io.mazewall.portal

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PortalCreateTest {
    interface MissingGuest {
        fun echo(text: String): String
    }

    @Test
    fun `create fails closed when stub class is missing and does not load Impl`() {
        val broker = ProcessBroker()
        val ex =
            assertThrows(PortalCallException::class.java) {
                Portal.create(MissingGuest::class.java, broker)
            }
        broker.close()
        val msg = ex.message ?: ""
        assertTrue(msg.contains("Missing generated portal stub"))
        assertTrue(msg.contains("never loaded"))
        assertTrue(!msg.contains("MissingGuestImpl") || msg.contains("never loaded"))
    }
}
