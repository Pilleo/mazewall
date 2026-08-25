package io.mazewall.orchestrator

import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Telegram full-parity routing, verified at the transport seam: pcapprove/pcreject
 * button presses must surface action+uuid to the handler hook; legacy orchestrator
 * approve/skip prefixes keep their old behavior untouched.
 */
class SupervisorTelegramTest {

    private class RecordingTransport : HttpTransport {
        val requests = mutableListOf<HttpRequest>()

        var nextStatus = 200
        var nextBody: String = """{"ok":true,"result":[]}"""

        override fun send(request: HttpRequest): HttpResponse<String> {
            requests.add(request)
            return FakeHttpResponse(nextStatus, nextBody)
        }
    }

    private fun bot(transport: HttpTransport) = TelegramBot("token", "chat", transport)

    @Test
    fun `pcapprove callback exposes action id and callback query`() {
        val transport = RecordingTransport()
        val b = bot(transport)
        var handled: Triple<String, String, String>? = null
        b.onPaperclipApproval = { action, approvalId, cbId, _ ->
            handled = Triple(action, approvalId, cbId)
            b.answerCallbackWith(cbId, "Successfully approved")
            b.clearReplyMarkup(42L)
        }
        transport.nextBody =
            """{"ok":true,"result":[{"update_id":7,"callback_query":{"id":"cb1","data":"pcapprove:d0f1","message":{"message_id":42,"text":"x"}}}]}"""
        b.pollUpdates()

        assertEquals("approve", handled?.first)
        assertEquals("d0f1", handled?.second)
        assertEquals("cb1", handled?.third)

        val paths = transport.requests.map { it.uri().path }
        assertTrue(paths.contains("/bottoken/answerCallbackQuery"))
        assertTrue(paths.contains("/bottoken/editMessageReplyMarkup"))
        // Offset advanced: the next poll must not replay update 7.
        transport.nextBody = """{"ok":true,"result":[]}"""
        b.pollUpdates()
        assertTrue(transport.requests.any { it.uri().query?.contains("offset=8") == true })
    }

    @Test
    fun `pcreject callback surfaces reject action`() {
        val transport = RecordingTransport()
        val b = bot(transport)
        var action: String? = null
        b.onPaperclipApproval = { a, _, _, _ -> action = a }
        transport.nextBody =
            """{"ok":true,"result":[{"update_id":3,"callback_query":{"id":"cb2","data":"pcreject:abc"}}]}"""
        b.pollUpdates()
        assertEquals("reject", action)
    }

    @Test
    fun `legacy approve-skip prefixes still route to orchestrator map`() {
        val transport = RecordingTransport()
        val b = bot(transport)
        var pcSeen = false
        b.onPaperclipApproval = { _, _, _, _ -> pcSeen = true }
        transport.nextBody = """
            {"ok":true,"result":[
              {"update_id":1,"callback_query":{"id":"c1","data":"approve:issue-1"}},
              {"update_id":2,"callback_query":{"id":"c2","data":"skip:issue-2"}}
            ]}
        """.trimIndent()
        b.pollUpdates()
        assertEquals(true, b.checkApprovalNonBlocking("issue-1"))
        assertEquals(false, b.checkApprovalNonBlocking("issue-2"))
        assertEquals(false, pcSeen)
    }

    @Test
    fun `paperclip approval markup round-trips through the poller`() {
        val markup = paperclipApprovalMarkup("uuid-9")
        val buttons = markup.inline_keyboard.single()
        assertEquals("pcapprove:uuid-9", buttons[0].callback_data)
        assertEquals("pcreject:uuid-9", buttons[1].callback_data)

        val transport = RecordingTransport()
        val b = bot(transport)
        var action: String? = null
        var approvalId: String? = null
        b.onPaperclipApproval = { a, aid, _, _ -> action = a; approvalId = aid }
        transport.nextBody =
            """{"ok":true,"result":[{"update_id":5,"callback_query":{"id":"c9","data":"${buttons[0].callback_data}"}}]}"""
        b.pollUpdates()
        assertEquals("approve", action)
        assertEquals("uuid-9", approvalId)
    }

    @Test
    fun `event notifier announces each pending approval once via watermark`() {
        val transport = RecordingTransport()
        transport.nextBody = """{"ok":true}"""
        val board = RecordingTransport()
        board.nextBody =
            """[{"id":"ap1","type":"hire","status":"pending","createdAt":"2026-08-24T10:00:00Z"},
                {"id":"ap0","type":"hire","status":"pending","createdAt":"2026-08-24T09:00:00Z"}]"""

        val stateFile = java.nio.file.Files.createTempFile("sup-state", ".properties")
        val client = PaperclipClient(board, "local")
        val notifier = EventNotifier(bot(transport), client, "company", stateFile)

        notifier.pollApprovals()
        val cardsAfterFirst = transport.requests.count { it.uri().path.endsWith("/sendMessage") }
        assertEquals(2, cardsAfterFirst)

        // Watermark persisted: fresh notifier instance must not re-announce.
        val reloaded = EventNotifier(bot(transport), client, "company", stateFile)
        reloaded.pollApprovals()
        val cardsAfterSecond = transport.requests.count { it.uri().path.endsWith("/sendMessage") }
        assertEquals(cardsAfterFirst, cardsAfterSecond)

        // A newer approval gets announced.
        board.nextBody =
            """[{"id":"ap2","type":"tool","status":"pending","createdAt":"2026-08-24T11:00:00Z"}]"""
        reloaded.pollApprovals()
        assertEquals(cardsAfterFirst + 1, transport.requests.count { it.uri().path.endsWith("/sendMessage") })
    }
}
