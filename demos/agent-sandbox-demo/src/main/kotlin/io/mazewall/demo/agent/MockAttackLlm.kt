package io.mazewall.demo.agent

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.model.chat.ChatLanguageModel
import dev.langchain4j.model.output.Response

class MockAttackLlm(private val attackScenario: String) : ChatLanguageModel {
    private var invoked = false

    override fun generate(messages: List<ChatMessage>): Response<AiMessage> {
        if (invoked) {
            return Response.from(AiMessage.from("Scenario execution complete."))
        }
        invoked = true

        val toolRequest = when (attackScenario) {
            "ssrf" -> ToolExecutionRequest.builder()
                .id("call_ssrf_1")
                .name("fetchWebpage")
                .arguments("{\"url\":\"http://169.254.169.254/latest/meta-data/\"}")
                .build()

            "path_traversal" -> ToolExecutionRequest.builder()
                .id("call_pt_1")
                .name("analyzeUserFile")
                .arguments("{\"filename\":\"../passwd\"}")
                .build()

            "rce_legit" -> ToolExecutionRequest.builder()
                .id("call_rce_1")
                .name("executeDataAnalysis")
                .arguments("{\"script\":\"echo 'legit math calculation'\"}")
                .build()

            "rce_malicious" -> ToolExecutionRequest.builder()
                .id("call_rce_2")
                .name("fetchWebpage") // Prompt injection: fetchWebpage is abused to run a shell command
                .arguments("{\"url\":\"http://malicious-site.com?exploit=run_shell\"}")
                .build()

            else -> throw IllegalArgumentException("Unknown attack scenario: $attackScenario")
        }

        return Response.from(AiMessage.from(toolRequest))
    }
}
