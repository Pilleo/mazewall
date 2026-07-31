package io.mazewall.orchestrator

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Optional
import javax.net.ssl.SSLSession
import kotlin.test.*

class FakeHttpResponse(
    private val statusCode: Int,
    private val body: String
) : HttpResponse<String> {
    override fun statusCode(): Int = statusCode
    override fun request(): HttpRequest = throw UnsupportedOperationException()
    override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()
    override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
    override fun body(): String = body
    override fun sslSession(): Optional<SSLSession> = Optional.empty()
    override fun uri(): URI = throw UnsupportedOperationException()
    override fun version(): HttpClient.Version = HttpClient.Version.HTTP_2
}

class FakeHttpTransport(private val responder: (HttpRequest) -> HttpResponse<String>) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()

    override fun send(request: HttpRequest): HttpResponse<String> {
        requests.add(request)
        return responder(request)
    }
}

class HttpTransportTest {

    @Test
    fun testRealJulesClientWithFakeTransport() {
        System.setProperty("JULES_API_KEY", "fake-api-key")
        try {
            val config = OrchestratorConfig()
            val fakeTransport = FakeHttpTransport { req ->
                when {
                    req.uri().path.contains("activities") -> {
                        FakeHttpResponse(200, """{"activities": [{"name": "act1", "sessionFailed": {"reason": "Oops"}, "userMessaged": {"userMessage": "unable to complete"}}]}""")
                    }
                    req.uri().path.contains("sessions") -> {
                        FakeHttpResponse(200, """{"sessions": [{"name": "sessions/14927969181089226847", "title": "[issue-123] description", "state": "FAILED"}]}""")
                    }
                    else -> FakeHttpResponse(404, "Not Found")
                }
            }
            val client = RealJulesClient(config, fakeTransport)
            val sessions = client.listSessions()
            assertEquals(1, sessions.size)
            assertEquals("14927969181089226847", sessions[0].id)
            assertEquals("FAILED", sessions[0].status)

            val status = client.getSessionStatusFromActivities("14927969181089226847")
            assertEquals("failed", status)
        } finally {
            System.clearProperty("JULES_API_KEY")
        }
    }

    @Test
    fun testTelegramBotWithFakeTransport() {
        var updatesReturned = false
        val fakeTransport = FakeHttpTransport { req ->
            when {
                req.uri().path.contains("getUpdates") -> {
                    if (!updatesReturned) {
                        updatesReturned = true
                        FakeHttpResponse(200, """{"ok": true, "result": [{"update_id": 10, "message": {"message_id": 1, "text": "hello"}}]}""")
                    } else {
                        FakeHttpResponse(200, """{"ok": true, "result": []}""")
                    }
                }
                req.uri().path.contains("sendMessage") -> {
                    FakeHttpResponse(200, """{"ok": true}""")
                }
                else -> FakeHttpResponse(404, "Not Found")
            }
        }

        // Initialize bot (will call getUpdates during init)
        val bot = TelegramBot("fakeToken", "fakeChatId", fakeTransport)
        bot.sendMessage("Hello there")

        val sendMsgRequest = fakeTransport.requests.find { it.uri().path.contains("sendMessage") }
        assertNotNull(sendMsgRequest)
        assertEquals("POST", sendMsgRequest.method())
    }

    @Test
    fun testJulesSessionIdParsingAndSortingExceedingLongMax() {
        System.setProperty("JULES_API_KEY", "fake-api-key")
        try {
            val config = OrchestratorConfig()
            val fakeTransport = FakeHttpTransport { req ->
                when {
                    req.uri().path.contains("sessions") -> {
                        FakeHttpResponse(200, """
                            {
                              "sessions": [
                                {
                                  "name": "sessions/9223372036854775807",
                                  "title": "[issue-195] Long.MAX_VALUE",
                                  "state": "ACTIVE"
                                },
                                {
                                  "name": "sessions/14927969181089226847",
                                  "title": "[issue-195] Exceeds Long.MAX_VALUE",
                                  "state": "ACTIVE"
                                },
                                {
                                  "name": "sessions/invalid-id-123",
                                  "title": "[issue-195] Invalid",
                                  "state": "ACTIVE"
                                },
                                {
                                  "name": "sessions/500",
                                  "title": "[issue-195] Small id",
                                  "state": "ACTIVE"
                                }
                              ]
                            }
                        """.trimIndent())
                    }
                    else -> FakeHttpResponse(404, "Not Found")
                }
            }
            val client = RealJulesClient(config, fakeTransport)

            // 1. Verify listSessions filters out non-numeric and retains numeric IDs (even if > Long.MAX_VALUE)
            val sessions = client.listSessions()
            assertEquals(3, sessions.size)
            assertTrue(sessions.any { it.id == "9223372036854775807" })
            assertTrue(sessions.any { it.id == "14927969181089226847" })
            assertTrue(sessions.any { it.id == "500" })
            assertFalse(sessions.any { it.id == "invalid-id-123" })

            // 2. Verify getActiveSession sorts descending numerically (not lexicographically)
            val activeSession = client.getActiveSession("issue-195")
            assertNotNull(activeSession)
            assertEquals("14927969181089226847", activeSession.id)
        } finally {
            System.clearProperty("JULES_API_KEY")
        }
    }
}
