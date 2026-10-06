package io.mazewall.demo.agent

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.model.chat.ChatLanguageModel
import io.mazewall.enforcer.api.ProcessPolicyBuilder
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.PolicyScope
import io.mazewall.enforcer.api.Supervision
import io.mazewall.platform.syscall.Syscall
import io.mazewall.enforcer.api.ContainedExecutors
import java.lang.reflect.Method
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    var scenario: String? = null
    var useMazewall = false

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--scenario" -> {
                if (i + 1 < args.size) {
                    scenario = args[++i]
                }
            }
            "--mazewall" -> {
                useMazewall = true
            }
        }
        i++
    }

    if (scenario == null) {
        println("Usage: AgentDemoKt --scenario <ssrf|path_traversal|file_legit|rce_legit|rce_malicious> [--mazewall]")
        exitProcess(1)
    }

    val result = runAgentScenario(scenario, useMazewall)
    println(result.render())
    if (!result.passed) exitProcess(2)
}

internal fun runAgentScenario(
    scenario: String,
    useMazewall: Boolean,
): AgentScenarioResult {
    println("======================================================================")
    println("Starting AI Agent Sandbox Demo")
    println("Scenario: $scenario")
    println("Mazewall Security: ${if (useMazewall) "ENABLED" else "DISABLED"}")
    println("======================================================================")

    val expected = if (useMazewall && scenario !in setOf("file_legit", "rce_legit")) {
        ExpectedAgentResult.DENIED
    } else {
        ExpectedAgentResult.ALLOWED
    }
    return try {
        runAgentScenarioConfigured(scenario, useMazewall, expected)
    } catch (failure: Exception) {
        rethrowFatalOrRestoreInterruption(failure)
        AgentScenarioResult(scenario, expected, AgentOutcome.InfrastructureFailure(failure))
    }
}

private fun runAgentScenarioConfigured(
    scenario: String,
    useMazewall: Boolean,
    expected: ExpectedAgentResult,
): AgentScenarioResult {
    if (useMazewall) {
        // The demo supervises the parent-side vfork. Set this before ProcessImpl is initialized;
        // applicationDefaultJvmArgs provides the same value for normal Gradle runs.
        System.setProperty("jdk.lang.Process.launchMechanism", "vfork")
        // The public facade starts USER_NOTIF supervision before this permanent baseline applies.
        Supervision.prepareSupervisor()
        // Tier 1: Process-Wide Baseline Constraint
        // We block highly sensitive system administration syscalls for the entire process,
        // ensuring that even if an attacker achieves Arbitrary Code Execution (ACE) on any thread, 
        // they cannot escape the container namespace boundary via mount, ptrace, unshare, etc.
        val processBaseline = ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT)
            .allowMmapExec()
            .block(Syscall.PTRACE, Syscall.MOUNT, Syscall.UNSHARE, Syscall.CHROOT)
            .build()
        ContainedExecutors.installOnProcess(processBaseline).use {
            println("[MAZEWALL] Tier 1 Process-wide containment applied (blocked PTRACE, MOUNT, UNSHARE, CHROOT).")
            return runAgentScenario(useMazewall, scenario, expected)
        }
    }
    return runAgentScenario(useMazewall, scenario, expected)
}

private fun runAgentScenario(
    useMazewall: Boolean,
    scenario: String,
    expected: ExpectedAgentResult,
): AgentScenarioResult {
    AgentDemoFixture.create().use { fixture ->
        AgentTools(useMazewall, fixture.workspace).use { tools ->
            val model: ChatLanguageModel = MockAttackLlm(scenario)
            val outcome = runScenarioBoundary(
                action = {
                    println("[AGENT] Prompting LLM...")
                    val response = model.generate(emptyList())
                    val aiMessage = response.content()
                    if (aiMessage.hasToolExecutionRequests()) {
                        val toolRequest = aiMessage.toolExecutionRequests().single()
                        println("[AGENT] LLM requested tool execution: ${toolRequest.name()} with args ${toolRequest.arguments()}")
                        classifyToolOutcome(captureRecoverable { invokeTool(tools, toolRequest) })
                    } else {
                        AgentOutcome.InfrastructureFailure(IllegalStateException("scenario produced no tool request"))
                    }
                },
                cleanup = tools::close,
            )
            return AgentScenarioResult(scenario, expected, outcome)
        }
    }
}

private fun invokeTool(tools: AgentTools, request: ToolExecutionRequest): String {
    val method = tools.javaClass.methods.find { it.name == request.name() }
        ?: throw IllegalArgumentException("Tool ${request.name()} not found")

    // Simple JSON argument parser for mock arguments
    val args = parseArgs(request.arguments(), method)
    return method.invoke(tools, *args) as String
}

private fun parseArgs(json: String, method: Method): Array<Any> {
    // Basic parser for our mock tool inputs: e.g. {"url":"..."} or {"filename":"..."} or {"script":"..."}
    val cleanJson = json.trim().removePrefix("{").removeSuffix("}")
    if (cleanJson.isEmpty()) return emptyArray()
    val parts = cleanJson.split(":", limit = 2)
    if (parts.size < 2) return emptyArray()
    val value = parts[1].trim().removePrefix("\"").removeSuffix("\"")
    return arrayOf(value)
}
