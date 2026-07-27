package io.mazewall.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString

class GitHubCliSerializationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parse GitHubComment correctly`() {
        val payload = """
            {
                "author": { "login": "testuser" },
                "body": "comment text",
                "createdAt": "2023-01-01T00:00:00Z"
            }
        """.trimIndent()

        val comment = json.decodeFromString<GitHubComment>(payload)
        assertEquals("testuser", comment.author?.login)
        assertEquals("comment text", comment.body)
        assertEquals("2023-01-01T00:00:00Z", comment.createdAt)
    }

    @Test
    fun `parse CommentsContainer correctly`() {
        val payload = """
            {
                "comments": [
                    { "body": "first", "createdAt": "time1" },
                    { "body": "second", "createdAt": "time2" }
                ]
            }
        """.trimIndent()

        val container = json.decodeFromString<CommentsContainer>(payload)
        assertEquals(2, container.comments.size)
        assertEquals("first", container.comments[0].body)
    }

    @Test
    fun `parse GitHubMergeable correctly`() {
        val payload = """{ "mergeable": "CONFLICTING" }"""
        val mergeable = json.decodeFromString<GitHubMergeable>(payload)
        assertEquals("CONFLICTING", mergeable.mergeable)
    }

    @Test
    fun `parse GitHubRun correctly`() {
        val payload = """{ "databaseId": 123456 }"""
        val run = json.decodeFromString<GitHubRun>(payload)
        assertEquals(123456L, run.databaseId)
    }

    @Test
    fun `parse GitHubCheck correctly`() {
        val payload = """
            {
                "state": "SUCCESS",
                "name": "build",
                "bucket": "test",
                "event": "push"
            }
        """.trimIndent()
        val check = json.decodeFromString<GitHubCheck>(payload)
        assertEquals("SUCCESS", check.state)
        assertEquals("build", check.name)
        assertEquals("test", check.bucket)
        assertEquals("push", check.event)
    }

    @Test
    fun `parse GitHubPR correctly`() {
        val payload = """
            {
                "number": 42,
                "title": "Fix bug",
                "headRefName": "fix/bug-123",
                "body": "fixes #123"
            }
        """.trimIndent()
        val pr = json.decodeFromString<GitHubPR>(payload)
        assertEquals(42, pr.number)
        assertEquals("Fix bug", pr.title)
        assertEquals("fix/bug-123", pr.headRefName)
        assertEquals("fixes #123", pr.body)
    }

    @Test
    fun `parse GitHubIssue correctly`() {
        val payload = """
            {
                "number": 100,
                "title": "New feature"
            }
        """.trimIndent()
        val issue = json.decodeFromString<GitHubIssue>(payload)
        assertEquals(100, issue.number)
        assertEquals("New feature", issue.title)
    }

    @Test
    fun `serialize and deserialize Telegram classes correctly`() {
        val replyMarkup = ReplyMarkup(listOf(listOf(InlineKeyboardButton("text", "data"))))
        val sendMessageRequest = SendMessageRequest("chatId", "text", "Markdown", replyMarkup)
        val answerCallbackQueryRequest = AnswerCallbackQueryRequest("queryId")

        val replyMarkupJson = json.encodeToString(ReplyMarkup.serializer(), replyMarkup)
        val sendMessageRequestJson = json.encodeToString(SendMessageRequest.serializer(), sendMessageRequest)
        val answerCallbackQueryRequestJson = json.encodeToString(AnswerCallbackQueryRequest.serializer(), answerCallbackQueryRequest)

        assertNotNull(replyMarkupJson)
        assertNotNull(sendMessageRequestJson)
        assertNotNull(answerCallbackQueryRequestJson)

        val parsedReplyMarkup = json.decodeFromString<ReplyMarkup>(replyMarkupJson)
        assertEquals("text", parsedReplyMarkup.inline_keyboard[0][0].text)

        val parsedSendMessage = json.decodeFromString<SendMessageRequest>(sendMessageRequestJson)
        assertEquals("text", parsedSendMessage.text)

        val parsedAnswer = json.decodeFromString<AnswerCallbackQueryRequest>(answerCallbackQueryRequestJson)
        assertEquals("queryId", parsedAnswer.callback_query_id)
    }

    @Test
    fun `deserialize TelegramUpdate correctly`() {
        val payload = """
            {
                "update_id": 1,
                "message": { "message_id": 10, "text": "hello" },
                "callback_query": { "id": "q1", "data": "approve" }
            }
        """.trimIndent()
        val update = json.decodeFromString<TelegramUpdate>(payload)
        assertEquals(1L, update.update_id)
        assertEquals(10L, update.message?.message_id)
        assertEquals("hello", update.message?.text)
        assertEquals("q1", update.callback_query?.id)
        assertEquals("approve", update.callback_query?.data)

        val tgResponsePayload = """
            {
                "ok": true,
                "result": [
                    { "update_id": 1 }
                ]
            }
        """.trimIndent()
        val response = json.decodeFromString<TelegramResponse<List<TelegramUpdate>>>(tgResponsePayload)
        assertTrue(response.ok)
        assertEquals(1, response.result?.size)
    }

    @Test
    fun `serialize and deserialize Jules Request and Session classes correctly`() {
        val repoContext = GithubRepoContext("main")
        val sourceContext = SourceContext("source", repoContext)
        val createSessionRequest = CreateSessionRequest("prompt", sourceContext, "title")

        val listSessionsResponse = ListSessionsResponse(listOf(SessionResponse("session-1", "title", "state")))

        val createSessionJson = json.encodeToString(CreateSessionRequest.serializer(), createSessionRequest)
        val listSessionsJson = json.encodeToString(ListSessionsResponse.serializer(), listSessionsResponse)

        assertNotNull(createSessionJson)
        assertNotNull(listSessionsJson)

        val parsedCreate = json.decodeFromString<CreateSessionRequest>(createSessionJson)
        assertEquals("prompt", parsedCreate.prompt)
        assertEquals("main", parsedCreate.sourceContext.githubRepoContext.startingBranch)

        val parsedList = json.decodeFromString<ListSessionsResponse>(listSessionsJson)
        assertEquals(1, parsedList.sessions.size)
        assertEquals("session-1", parsedList.sessions[0].name)
    }

    @Test
    fun `serialize and deserialize Jules Activity classes correctly`() {
        val step = PlanStep("step-1", "title", "desc", 0)
        val plan = Plan("plan-1", listOf(step))
        val planGenerated = PlanGeneratedPayload(plan)
        val planApproved = PlanApprovedPayload("plan-1")
        val progressUpdated = ProgressUpdatedPayload("title", "desc")
        val gitPatch = GitPatch("patch", "commit")
        val changeSet = ChangeSet("source", gitPatch)
        val artifact = Artifact(changeSet)
        val sessionFailed = SessionFailedPayload("reason")
        val userMessaged = UserMessagedPayload("msg")

        val activity = Activity(
            name = "act-1",
            createTime = "now",
            originator = "user",
            id = "id-1",
            planGenerated = planGenerated,
            planApproved = planApproved,
            progressUpdated = progressUpdated,
            artifacts = listOf(artifact),
            sessionFailed = sessionFailed,
            userMessaged = userMessaged
        )

        val listResponse = ListActivitiesResponse(listOf(activity), "token")

        val listJson = json.encodeToString(ListActivitiesResponse.serializer(), listResponse)
        assertNotNull(listJson)

        val parsedList = json.decodeFromString<ListActivitiesResponse>(listJson)
        assertEquals(1, parsedList.activities.size)
        val parsedActivity = parsedList.activities[0]
        assertEquals("act-1", parsedActivity.name)
        assertEquals("step-1", parsedActivity.planGenerated?.plan?.steps?.get(0)?.id)
        assertEquals("plan-1", parsedActivity.planApproved?.planId)
        assertEquals("desc", parsedActivity.progressUpdated?.description)
        assertEquals("patch", parsedActivity.artifacts?.get(0)?.changeSet?.gitPatch?.unidiffPatch)
        assertEquals("reason", parsedActivity.sessionFailed?.reason)
        assertEquals("msg", parsedActivity.userMessaged?.userMessage)
        assertEquals("token", parsedList.nextPageToken)
    }
}
