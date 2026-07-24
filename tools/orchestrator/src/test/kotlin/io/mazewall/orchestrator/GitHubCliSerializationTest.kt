package io.mazewall.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
}
