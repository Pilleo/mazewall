package io.mazewall.orchestrator

import kotlin.test.Test
import kotlin.test.assertTrue

class OrchestratorPromptsTest {

    @Test
    fun testTaskPromptIncludesGuidelines() {
        val originalBody = "Fix some bugs."
        val prompt = OrchestratorPrompts.taskPrompt(originalBody)

        assertTrue(prompt.contains(originalBody))
        assertTrue(prompt.contains("Quality and Safety Guidelines"))
        assertTrue(prompt.contains("Absolute Certainty"))
        assertTrue(prompt.contains("Zero Silent Bypasses"))
        assertTrue(prompt.contains("JVM Coordination Invariants"))
        assertTrue(prompt.contains("FFM Safety"))
        assertTrue(prompt.contains("Loom Carrier Protection"))
    }

    @Test
    fun testReviewPromptIncludesGuidelines() {
        val prNumber = "123"
        val shaPrefix = "abc1234"
        val pushWarning = "Don't push again!"
        val prompt = OrchestratorPrompts.reviewPrompt(prNumber, shaPrefix, pushWarning)

        assertTrue(prompt.contains("\n\nDon't push again!"))
        assertTrue(prompt.contains(prNumber))
        assertTrue(prompt.contains(shaPrefix))
        assertTrue(prompt.contains(pushWarning))
        assertTrue(prompt.contains("Quality and Safety Guidelines"))
        assertTrue(prompt.contains("Absolute Certainty"))
        assertTrue(prompt.contains("Zero Silent Bypasses"))
        assertTrue(prompt.contains("JVM Coordination Invariants"))
        assertTrue(prompt.contains("FFM Safety"))
        assertTrue(prompt.contains("Loom Carrier Protection"))
    }
}
