package io.mazewall.profiler.triage

import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths

/**
 * DiagnosticTriageRunner collects local, domain-specific telemetry for mazewall failures.
 * It focuses on extracting JVM thread coordination state and Linux kernel security logs
 * without using external SaaS tools, heavy frameworks, or custom serialization.
 */
object DiagnosticTriageRunner {
    @JvmStatic
    fun main(args: Array<String>) {
        println("==> Initiating mazewall local diagnostic triage...")
        val targetFile = File("build/triage_report.json")
        targetFile.parentFile.mkdirs()

        // 1. Capture local kernel audit logs for blocked syscalls (SECCOMP/Landlock)
        val dmesgLogs = captureDmesg()

        // 2. Capture local JVM thread states to locate deadlocks or virtual thread pinning
        val jvmThreadDump = captureThreadDump()

        // 3. Attempt to parse syscall definitions from the local system headers
        val syscallMapping = parseLocalSyscalls()

        // 4. Capture any Java crash dumps (hs_err_pid*.log)
        val hsErrLogs = collectHsErrLogs()

        // 5. Capture kernel security configurations
        val kernelConfig = captureKernelConfig()

        // Write a simple JSON structure containing the raw diagnostic dumps
        val jsonContent = """
            {
              "timestamp": ${System.currentTimeMillis()},
              "diagnostics": {
                "dmesg_seccomp_logs": ${escapeJson(dmesgLogs)},
                "jvm_thread_dump": ${escapeJson(jvmThreadDump)},
                "system_syscall_definitions": ${escapeJson(syscallMapping)},
                "hs_err_logs": ${escapeJson(hsErrLogs)},
                "kernel_security_config": ${escapeJson(kernelConfig)}
              }
            }
        """.trimIndent()

        targetFile.writeText(jsonContent)
        println("==> Local diagnostic triage complete. Report written to: build/triage_report.json")
    }

    private fun captureDmesg(): String {
        return try {
            val process = ProcessBuilder("dmesg").start()
            process.inputStream.bufferedReader().useLines { lines ->
                lines.filter { line ->
                    line.contains("seccomp", ignoreCase = true) || 
                    line.contains("landlock", ignoreCase = true) ||
                    line.contains("audit", ignoreCase = true)
                }.take(100).joinToString("\n")
            }
        } catch (e: Exception) {
            "Unable to capture dmesg: ${e.message}"
        }
    }

    private fun captureThreadDump(): String {
        val selfPid = ProcessHandle.current().pid()
        val pids = ProcessHandle.allProcesses().toList()
            .filter { ph ->
                val command = ph.info().command().orElse("")
                ph.pid() != selfPid && (command.contains("java") || command.contains("gradle"))
            }
            .map { it.pid() }

        if (pids.isEmpty()) {
            return "No other active Java/Gradle processes found."
        }

        val sb = StringBuilder()
        for (pid in pids) {
            sb.append("=== JVM Thread Dump for PID $pid ===\n")
            try {
                val process = ProcessBuilder("jcmd", pid.toString(), "Thread.print").start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                sb.append(output)
            } catch (e: Exception) {
                sb.append("Failed to capture thread dump for PID $pid: ${e.message}\n")
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    private fun collectHsErrLogs(): String {
        val sb = StringBuilder()
        val rootDir = File(".")
        val files = rootDir.walkTopDown()
            .filter { it.isFile && it.name.startsWith("hs_err_") && it.name.endsWith(".log") }
            .toList()

        if (files.isEmpty()) {
            return "No hs_err_pid*.log files found."
        }

        for (file in files) {
            sb.append("=== Error Report File: ${file.path} ===\n")
            try {
                sb.append(file.readText())
            } catch (e: Exception) {
                sb.append("Failed to read ${file.path}: ${e.message}\n")
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    private fun captureKernelConfig(): String {
        val sb = StringBuilder()
        
        // 1. Read Yama ptrace_scope
        val yamaFile = File("/proc/sys/kernel/yama/ptrace_scope")
        if (yamaFile.exists()) {
            try {
                sb.append("Yama ptrace_scope: ").append(yamaFile.readText().trim()).append("\n")
            } catch (e: Exception) {
                sb.append("Yama ptrace_scope: read failed (${e.message})\n")
            }
        } else {
            sb.append("Yama ptrace_scope: not present\n")
        }

        // 2. Read active LSMs
        val lsmFile = File("/sys/kernel/security/lsm")
        if (lsmFile.exists()) {
            try {
                sb.append("Active LSMs: ").append(lsmFile.readText().trim()).append("\n")
            } catch (e: Exception) {
                sb.append("Active LSMs: read failed (${e.message})\n")
            }
        } else {
            sb.append("Active LSMs list: not present\n")
        }

        // 3. Check Landlock directory
        val landlockSec = File("/sys/kernel/security/landlock")
        sb.append("Landlock kernel security directory present: ").append(landlockSec.exists()).append("\n")

        return sb.toString()
    }

    private fun parseLocalSyscalls(): String {
        // Try standard Linux x86_64 system call header locations
        val paths = listOf(
            "/usr/include/asm/unistd_64.h",
            "/usr/include/x86_64-linux-gnu/asm/unistd_64.h"
        )
        for (pathStr in paths) {
            val file = File(pathStr)
            if (file.exists()) {
                return try {
                    file.useLines { lines ->
                        lines.filter { it.startsWith("#define __NR_") }
                            .map { it.removePrefix("#define __NR_").trim() }
                            .joinToString("\n")
                    }
                } catch (e: Exception) {
                    "Failed reading $pathStr: ${e.message}"
                }
            }
        }
        return "Local unistd_64.h not found. Syscall mapping unavailable."
    }

    private fun escapeJson(value: String): String {
        return "\"" + value.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r") + "\""
    }
}
