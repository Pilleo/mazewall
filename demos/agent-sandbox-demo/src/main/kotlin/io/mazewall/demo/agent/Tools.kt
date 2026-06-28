package io.mazewall.demo.agent

import dev.langchain4j.agent.tool.Tool
import io.mazewall.Policy
import io.mazewall.core.Syscall
import io.mazewall.enforcer.ContainedExecutors
import io.mazewall.enforcer.supervisor.StacktraceScopingPolicy
import io.mazewall.enforcer.supervisor.ScopingHandler
import io.mazewall.core.Tid
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AgentTools(
    private val useMazewall: Boolean,
    private val workspaceDir: File
) {
    // Thread pools representing separate agent compartments (Thread-Level Compartmentalization)
    private val webRawExecutor = Executors.newSingleThreadExecutor()
    private val fileRawExecutor = Executors.newSingleThreadExecutor()
    private val analysisRawExecutor = Executors.newSingleThreadExecutor()

    // 1. Web Agent Compartment: Allows network connections, but blocks private IPs (SSRF) and blocks process spawning
    private val networkPolicy = Policy.builder()
        .allowMmapExec()
        .allow(Syscall.CLONE, Syscall.CLONE3, Syscall.EXECVE, Syscall.EXECVEAT)
        .build()

    private val webScopingPolicy = object : StacktraceScopingPolicy {
        override val handlers = mapOf<Syscall, ScopingHandler>(
            Syscall.CONNECT to { tid, args, stack ->
                val ip = args.firstOrNull() as? String ?: ""
                val isPrivate = ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("127.") || ip.startsWith("172.16.")
                
                if (isPrivate) {
                    println("[MAZEWALL] [BLOCKED] Network connection to private IP $ip intercepted and dropped!")
                    false
                } else {
                    println("[MAZEWALL] [ALLOWED] Network connection to public IP $ip authorized.")
                    true
                }
            },
            Syscall.VFORK to { tid, args, stack -> authorizeExec(stack) },
            Syscall.FORK to { tid, args, stack -> authorizeExec(stack) }
        )
    }

    private val webExecutor: ExecutorService = if (useMazewall) {
        ContainedExecutors.wrap(webRawExecutor, networkPolicy, webScopingPolicy)
    } else {
        webRawExecutor
    }

    // 2. File Agent Compartment: RESTRICTED read-only filesystem access via Landlock (Path Traversal) and blocks process spawning
    private val filePolicy = Policy.builder()
        .allowMmapExec()
        .block(Syscall.VFORK, Syscall.FORK, Syscall.EXECVE, Syscall.EXECVEAT)
        .allowFsRead(workspaceDir.absolutePath)
        .build()

    private val fileExecutor: ExecutorService = if (useMazewall) {
        // Wrap with default scoping policy as Landlock does not need supervisor stack inspection
        ContainedExecutors.wrap(fileRawExecutor, filePolicy)
    } else {
        fileRawExecutor
    }

    // 3. Execution Agent Compartment: Allows execve, but ONLY from the executeDataAnalysis stack (RCE)
    private val execPolicy = Policy.builder()
        .allowMmapExec()
        .allow(Syscall.CLONE, Syscall.CLONE3, Syscall.EXECVE, Syscall.EXECVEAT)
        .build()

    private val execScopingPolicy = object : StacktraceScopingPolicy {
        override val handlers = mapOf<Syscall, ScopingHandler>(
            Syscall.VFORK to { tid, args, stack -> authorizeExec(stack) },
            Syscall.FORK to { tid, args, stack -> authorizeExec(stack) }
        )
    }

    private fun authorizeExec(stack: List<StackTraceElement>): Boolean {
        val isLegit = stack.any { it.className.contains("AgentTools") && it.methodName.contains("executeDataAnalysis") }
        if (!isLegit) {
            println("[MAZEWALL] [BLOCKED] Process spawn blocked! Execution stack did not originate from executeDataAnalysis!")
        } else {
            println("[MAZEWALL] [ALLOWED] Process spawn authorized for executeDataAnalysis.")
        }
        return isLegit
    }

    private val analysisExecutor: ExecutorService = if (useMazewall) {
        ContainedExecutors.wrap(analysisRawExecutor, execPolicy, execScopingPolicy)
    } else {
        analysisRawExecutor
    }

    @Tool("Fetch a public webpage for information")
    fun fetchWebpage(url: String): String {
        return webExecutor.submit<String> {
            println("[TOOL] Fetching webpage: $url")
            
            if (url.contains("run_shell")) {
                println("[TOOL] Prompt injection exploit trigger: Trying to run process exec directly from fetchWebpage context!")
                val process = ProcessBuilder("sh", "-c", "echo 'hacked'").start()
                process.inputStream.bufferedReader().use { it.readText() }
            } else {
                val client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build()
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                response.body()
            }
        }.get()
    }

    @Tool("Analyze a user upload file within workspace")
    fun analyzeUserFile(filename: String): String {
        return fileExecutor.submit<String> {
            println("[TOOL] Analyzing file: $filename")
            val targetFile = File(workspaceDir, filename).canonicalFile
            println("[TOOL] Resolved file path: ${targetFile.absolutePath}")
            targetFile.readText()
        }.get()
    }

    @Tool("Execute data analysis script")
    fun executeDataAnalysis(script: String): String {
        return analysisExecutor.submit<String> {
            println("[TOOL] Executing script: $script")
            val process = ProcessBuilder("sh", "-c", script).start()
            val result = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            result
        }.get()
    }

    private fun isPrivateIp(sockaddrBytes: ByteArray): Boolean {
        if (sockaddrBytes.size < 8) return false
        val family = (sockaddrBytes[0].toInt() and 0xFF) or ((sockaddrBytes[1].toInt() and 0xFF) shl 8)

        if (family == 2) { // AF_INET
            val ip0 = sockaddrBytes[4].toInt() and 0xFF
            val ip1 = sockaddrBytes[5].toInt() and 0xFF
            val ip2 = sockaddrBytes[6].toInt() and 0xFF
            val ip3 = sockaddrBytes[7].toInt() and 0xFF
            return isPrivateIpv4(ip0, ip1, ip2, ip3)
        } else if (family == 10) { // AF_INET6
            if (sockaddrBytes.size < 24) return false
            // Check if it is an IPv4-mapped IPv6 address (::ffff:x.x.x.x)
            // Bytes 8-17 should be 0, bytes 18-19 should be 0xFF
            val isMapped = (8..17).all { sockaddrBytes[it].toInt() == 0 } &&
                    (sockaddrBytes[18].toInt() and 0xFF) == 0xFF &&
                    (sockaddrBytes[19].toInt() and 0xFF) == 0xFF

            if (isMapped && sockaddrBytes.size >= 24) {
                val ip0 = sockaddrBytes[20].toInt() and 0xFF
                val ip1 = sockaddrBytes[21].toInt() and 0xFF
                val ip2 = sockaddrBytes[22].toInt() and 0xFF
                val ip3 = sockaddrBytes[23].toInt() and 0xFF
                return isPrivateIpv4(ip0, ip1, ip2, ip3)
            }

            // Native IPv6 private checks
            val firstByte = sockaddrBytes[8].toInt() and 0xFF
            // ULA (fc00::/7) - first byte is 0xfc or 0xfd
            if (firstByte == 0xFC || firstByte == 0xFD) return true
            // Link-local (fe80::/10) - first byte is 0xfe and second byte has top 2 bits set (0x80 to 0xbf)
            val secondByte = sockaddrBytes[9].toInt() and 0xFF
            if (firstByte == 0xFE && (secondByte and 0xC0) == 0x80) return true
            // Loopback (::1)
            val isLoopback = (8..22).all { sockaddrBytes[it].toInt() == 0 } && sockaddrBytes[23].toInt() == 1
            if (isLoopback) return true
        }

        return false
    }

    private fun isPrivateIpv4(ip0: Int, ip1: Int, ip2: Int, ip3: Int): Boolean {
        return ip0 == 127 || ip0 == 10 || (ip0 == 192 && ip1 == 168) || (ip0 == 172 && ip1 in 16..31) || (ip0 == 169 && ip1 == 254)
    }

    fun shutdown() {
        webRawExecutor.shutdown()
        fileRawExecutor.shutdown()
        analysisRawExecutor.shutdown()
    }
}
