package io.mazewall.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CliAuthenticationTest {

    @Test
    fun `detects GitHub CLI auth failures correctly`() {
        val command = arrayOf("gh", "pr", "view")

        // Error with "not authenticated"
        val ex1 = assertFailsWith<CliAuthenticationException> {
            checkForAuthenticationFailure(command, 1, "error: not authenticated")
        }
        assertEquals("gh", ex1.tool)
        assertEquals("error: not authenticated", ex1.output)

        // Error with "GH_TOKEN"
        val ex2 = assertFailsWith<CliAuthenticationException> {
            checkForAuthenticationFailure(command, 1, "Please set GH_TOKEN environment variable")
        }
        assertEquals("gh", ex2.tool)

        // Error with "sign in"
        val ex3 = assertFailsWith<CliAuthenticationException> {
            checkForAuthenticationFailure(command, 1, "To sign in, run gh auth login")
        }
        assertEquals("gh", ex3.tool)
    }

    @Test
    fun `detects Jules CLI auth failures correctly`() {
        val command = arrayOf("jules", "start")

        // Error with "not logged in"
        val ex1 = assertFailsWith<CliAuthenticationException> {
            checkForAuthenticationFailure(command, 1, "You are not logged in.")
        }
        assertEquals("jules", ex1.tool)

        // Error with "authentication required"
        val ex2 = assertFailsWith<CliAuthenticationException> {
            checkForAuthenticationFailure(command, 1, "Error: Authentication required.")
        }
        assertEquals("jules", ex2.tool)

        // Error with "unauthorized"
        val ex3 = assertFailsWith<CliAuthenticationException> {
            checkForAuthenticationFailure(command, 1, "HTTP 401: Unauthorized")
        }
        assertEquals("jules", ex3.tool)
    }

    @Test
    fun `detects Antigravity CLI auth failures correctly`() {
        val command = arrayOf("agy", "run")

        // Error with "token validation"
        val ex1 = assertFailsWith<CliAuthenticationException> {
            checkForAuthenticationFailure(command, 1, "Token validation failed")
        }
        assertEquals("agy", ex1.tool)
    }

    @Test
    fun `does not fail on non-auth errors or other commands`() {
        val command1 = arrayOf("gh", "pr", "view")
        // Non-auth error should not fail
        checkForAuthenticationFailure(command1, 1, "error: pull request not found")

        val command2 = arrayOf("git", "status")
        // Non-supported tools should not fail even with auth-like words
        checkForAuthenticationFailure(command2, 1, "not authenticated")
    }
}
