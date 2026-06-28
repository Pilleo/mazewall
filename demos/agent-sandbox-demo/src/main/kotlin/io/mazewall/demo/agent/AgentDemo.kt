package io.mazewall.demo.agent

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.model.chat.ChatLanguageModel
import io.mazewall.Policy
import io.mazewall.PolicyScope
import io.mazewall.core.Syscall
import io.mazewall.enforcer.ContainedExecutors
import java.io.File
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
        println("Usage: AgentDemoKt --scenario <ssrf|path_traversal|rce_legit|rce_malicious> [--mazewall]")
        exitProcess(1)
    }

    println("======================================================================")
    println("Starting AI Agent Sandbox Demo")
    println("Scenario: $scenario")
    println("Mazewall Security: ${if (useMazewall) "ENABLED" else "DISABLED"}")
    println("======================================================================")

    if (useMazewall) {
        // Tier 1: Process-Wide Baseline Constraint
        // We block highly sensitive system administration syscalls for the entire process,
        // ensuring that even if an attacker achieves Arbitrary Code Execution (ACE) on any thread, 
        // they cannot escape the container namespace boundary via mount, ptrace, unshare, etc.
        val processBaseline = Policy.builder()
            .allowMmapExec()
            .block(Syscall.PTRACE, Syscall.MOUNT, Syscall.UNSHARE, Syscall.CHROOT)
            .build()
        ContainedExecutors.installOnProcess(processBaseline)
        println("[MAZEWALL] Tier 1 Process-wide containment applied (blocked PTRACE, MOUNT, UNSHARE, CHROOT).")
    }

    // Setup workspace directory
    val workspaceDir = File("agent-workspace").canonicalFile
    workspaceDir.mkdirs()
    val secretFile = File(workspaceDir, "secret.txt")
    secretFile.writeText("SUPER_SECRET_WORKSPACE_TOKEN_12345")
    
    // Also prepare a dummy target for path traversal demo
    val dummyEtcPasswd = File("passwd")
    dummyEtcPasswd.writeText("root:x:0:0:root:/root:/bin/bash\nbin:x:1:1:bin:/bin:/sbin/nologin\n")

    val tools = AgentTools(useMazewall, workspaceDir)
    val model: ChatLanguageModel = MockAttackLlm(scenario)

    try {
        println("[AGENT] Prompting LLM...")
        val response = model.generate(emptyList())
        val aiMessage = response.content()

        if (aiMessage.hasToolExecutionRequests()) {
            for (toolRequest in aiMessage.toolExecutionRequests()) {
                println("[AGENT] LLM requested tool execution: ${toolRequest.name()} with args ${toolRequest.arguments()}")
                try {
                    val result = invokeTool(tools, toolRequest)
                    println("[AGENT] Tool execution SUCCESS. Result:\n$result")
                } catch (e: Exception) {
                    println("[AGENT] Tool execution FAILED: ${e.cause?.message ?: e.message}")
                    if (useMazewall) {
                        println("[MAZEWALL] Security violation successfully intercepted at kernel level!")
                    }
                }
            }
        } else {
            println("[AGENT] LLM response: ${aiMessage.text()}")
        }
    } finally {
        tools.shutdown()
        // Clean up dummy passwd file if created
        if (dummyEtcPasswd.exists()) {
            dummyEtcPasswd.delete()
        }
        if (secretFile.exists()) {
            secretFile.delete()
        }
        if (workspaceDir.exists()) {
            workspaceDir.delete()
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
