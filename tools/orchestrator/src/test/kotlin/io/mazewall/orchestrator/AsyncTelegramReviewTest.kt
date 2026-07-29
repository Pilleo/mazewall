package io.mazewall.orchestrator

import java.io.File
import kotlin.test.*

class AsyncTelegramReviewTest {

    private var tempDir: File = File("")

    @BeforeTest
    fun setUp() {
        tempDir = File.createTempFile("async-telegram-test-", "")
        tempDir.delete()
        tempDir.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testAsyncTelegramPollingLaunchesReviewTaskIntoActiveSlots() {
        var initialized = false
        val fakeTransport = FakeHttpTransport { req ->
            when {
                req.uri().path.contains("getUpdates") -> {
                    if (!initialized) {
                        initialized = true
                        FakeHttpResponse(200, """{"ok": true, "result": []}""")
                    } else {
                        FakeHttpResponse(
                            200,
                            """
                            {
                                "ok": true,
                                "result": [
                                    {
                                        "update_id": 101,
                                        "message": {
                                            "message_id": 50,
                                            "text": "/review focus on FFM safety"
                                        }
                                    }
                                ]
                            }
                            """.trimIndent()
                        )
                    }
                }
                else -> FakeHttpResponse(200, """{"ok": true}""")
            }
        }

        val bot = TelegramBot("mockToken", "12345", fakeTransport)
        val context = OrchestratorContext()

        // Add an existing running task to context.activeSlots
        val existingSlot = SlotContext("issue-existing-01").apply {
            state = OrchestratorState.fromName("AWAITING_PR")
        }
        context.activeSlots.add(existingSlot)

        val env = MockOrchestratorEnvironment()

        bot.onReviewRequested = { focusComments ->
            ReviewIssueLauncher.launchReviewTask(focusComments, tempDir, env, context)
        }

        // 2. Poll Telegram updates non-blockingly
        bot.pollUpdates()

        // 3. Verify that the new review task was launched as a parallel active slot
        assertEquals(2, context.activeSlots.size)
        val reviewSlot = context.activeSlots.firstOrNull { it.currentIssueId != "issue-existing-01" }
        assertNotNull(reviewSlot)
        assertTrue(reviewSlot.currentIssueTitle?.contains("Review Task") == true)
        assertEquals(OrchestratorState.fromName("AWAITING_JULES_START").name, reviewSlot.state.name)
    }
}
