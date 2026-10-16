package io.mazewall.profiler.triage

import java.io.File
import java.time.Duration

/**
 * DiagnosticTriageRunner collects local, domain-specific telemetry for mazewall failures.
 * It focuses on extracting JVM thread coordination state and Linux kernel security logs
 * without using external SaaS tools, heavy frameworks, or custom serialization.
 */
internal object DiagnosticTriageRunner {
    @JvmStatic
    fun main(args: Array<String>) {
        val options = parseOptions(args)
        println("==> Initiating mazewall local diagnostic triage...")
        val targetFile = options.output

        // 1. Capture local kernel audit logs for blocked syscalls (SECCOMP/Landlock)
        val dmesgLogs = captureDmesg()

        // 2. Snapshot this collector JVM only, not a failed workload or a Gradle daemon.
        val jvmThreadDump = SelfThreadSnapshot().capture()

        // 3. Attempt to parse syscall definitions from the local system headers
        val syscallMapping = parseLocalSyscalls()

        // 4. Capture any Java crash dumps (hs_err_pid*.log)
        val hsErrLogs = collectHsErrLogs()

        // 5. Capture kernel security configurations
        val kernelConfig = captureKernelConfig()

        writeReport(
            targetFile,
            System.currentTimeMillis(),
            mapOf(
                "dmesg_seccomp_logs" to dmesgLogs,
                "jvm_thread_dump" to jvmThreadDump,
                "system_syscall_definitions" to syscallMapping,
                "hs_err_logs" to hsErrLogs,
                "kernel_security_config" to kernelConfig,
            ),
            options.failedTask,
        )

        println("==> Local diagnostic triage complete. Report written to: ${targetFile.path}")
    }

    private data class Options(
        val failedTask: String?,
        val output: File,
    )

    private fun parseOptions(args: Array<String>): Options {
        var failedTask: String? = null
        var output = File("build/triage_report.json")
        var index = 0
        while (index < args.size) {
            require(index + 1 < args.size) { "Missing value for ${args[index]}" }
            when (val option = args[index]) {
                "--failed-task" -> failedTask = args[index + 1]
                "--output" -> output = File(args[index + 1])
                else -> error("Unknown triage option: $option")
            }
            index += 2
        }
        return Options(failedTask, output)
    }

    internal fun writeReport(
        targetFile: File,
        timestamp: Long,
        diagnostics: Map<String, String>,
        failedTask: String? = null,
    ) {
        targetFile.parentFile?.mkdirs()
        targetFile.writeText(diagnosticReportJson(timestamp, diagnostics, failedTask))
    }

    /** Timeout for reading an owned external helper's stdout, not for self-snapshot acquisition. */
    private const val CAPTURE_TIMEOUT_SECONDS = 10L

    /**
     * Reads an owned dmesg helper's stdout with a timeout. This does not bound the acquisition,
     * allocation, or safepoint progress of the direct current-JVM thread snapshot.
     */
    private fun readProcessOutputBounded(capture: ManagedProcessCapture): String =
        runCatching { capture.readStdout(Duration.ofSeconds(CAPTURE_TIMEOUT_SECONDS)) }
            .getOrElse { "Capture timed out after ${CAPTURE_TIMEOUT_SECONDS}s." }

    private fun captureDmesg(): String {
        return captureOptional(
            onFailure = { "Unable to capture dmesg: ${it.message}" },
        ) {
            ManagedProcessCapture
                .start(ProcessBuilder("dmesg"))
                .use { capture ->
                readProcessOutputBounded(capture)
            }.lineSequence()
                .filter { line ->
                    line.contains("seccomp", ignoreCase = true) ||
                        line.contains("landlock", ignoreCase = true) ||
                        line.contains("audit", ignoreCase = true)
                }.take(100)
                .joinToString("\n")
        }
    }

    private fun collectHsErrLogs(): String {
        val sb = StringBuilder()
        val rootDir = File(".")
        val files = rootDir
            .walkTopDown()
            .filter { it.isFile && it.name.startsWith("hs_err_") && it.name.endsWith(".log") }
            .toList()

        if (files.isEmpty()) {
            return "No hs_err_pid*.log files found."
        }

        for (file in files) {
            sb.append("=== Error Report File: ${file.path} ===\n")
            sb.append(captureOptional(onFailure = { "Failed to read ${file.path}: ${it.message}\n" }) { file.readText() })
            sb.append("\n")
        }
        return sb.toString()
    }

    private fun captureKernelConfig(): String {
        val sb = StringBuilder()

        // 1. Read Yama ptrace_scope
        val yamaFile = File("/proc/sys/kernel/yama/ptrace_scope")
        if (yamaFile.exists()) {
            sb
                .append("Yama ptrace_scope: ")
                .append(captureOptional(onFailure = { "read failed (${it.message})" }) { yamaFile.readText().trim() })
                .append("\n")
        } else {
            sb.append("Yama ptrace_scope: not present\n")
        }

        // 2. Read active LSMs
        val lsmFile = File("/sys/kernel/security/lsm")
        if (lsmFile.exists()) {
            sb
                .append("Active LSMs: ")
                .append(captureOptional(onFailure = { "read failed (${it.message})" }) { lsmFile.readText().trim() })
                .append("\n")
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
            "/usr/include/x86_64-linux-gnu/asm/unistd_64.h",
        )
        for (pathStr in paths) {
            val file = File(pathStr)
            if (file.exists()) {
                return captureOptional(
                    onFailure = { "Failed reading $pathStr: ${it.message}" },
                ) {
                    file.useLines { lines ->
                        lines
                            .filter { it.startsWith("#define __NR_") }
                            .map { it.removePrefix("#define __NR_").trim() }
                            .joinToString("\n")
                    }
                }
            }
        }
        return "Local unistd_64.h not found. Syscall mapping unavailable."
    }
}

/**
 * Host diagnostic collection is deliberately non-authoritative: a failed optional probe is
 * rendered into the report while containment and policy decisions remain unaffected.
 * Shared by the self snapshot and existing probes; fatal JVM errors are never caught.
 */
private inline fun captureOptional(
    onFailure: (Exception) -> String,
    action: () -> String,
): String =
    try {
        action()
    } catch (expectedOptionalFailure: Exception) {
        onFailure(expectedOptionalFailure)
    }

private const val MIN_SNAPSHOT_CHARACTERS = 256

/** These limits bound rendered output only; acquiring all platform-thread stacks remains best-effort. */
internal data class ThreadSnapshotLimits(
    val threads: Int = 128,
    val framesPerThread: Int = 64,
    val characters: Int = 65_536,
) {
    init {
        require(threads > 0)
        require(framesPerThread > 0)
        require(characters >= MIN_SNAPSHOT_CHARACTERS)
    }
}

/**
 * Direct current-JVM snapshot, excluding virtual threads and Thread.print monitor detail.
 * Acquisition may allocate all stacks and wait for safepoints; output caps are not a deadline.
 * Dependencies are captured per instance, never mutable singleton test hooks or external PIDs.
 */
internal class SelfThreadSnapshot(
    private val acquire: () -> Map<Thread, Array<StackTraceElement>> = Thread::getAllStackTraces,
    private val limits: ThreadSnapshotLimits = ThreadSnapshotLimits(),
) {
    fun capture(): String {
        val output = SnapshotText(limits.characters)
        output.append("=== Self snapshot: collector PID ${ProcessHandle.current().pid()}; current JVM only; platform threads; best-effort ===\n")
        return captureOptional(
            onFailure = {
                output.append("Unable to capture self snapshot: ${it.javaClass.simpleName}: ${it.message}\n")
                output.text()
            },
        ) {
            render(acquire(), output)
            output.text()
        }
    }

    private fun render(
        stacks: Map<Thread, Array<StackTraceElement>>,
        output: SnapshotText,
    ) {
        val ordered = stacks.entries.sortedBy { it.key.threadId() }
        if (ordered.isEmpty()) output.append("No platform-thread stacks available.\n")
        for ((thread, frames) in ordered.take(limits.threads)) {
            if (output.full) break
            output.append("\"${thread.name}\" id=${thread.threadId()} state=${thread.state}\n")
            for (frame in frames.take(limits.framesPerThread)) {
                if (output.full) break
                output.append("    at $frame\n")
            }
            if (frames.size > limits.framesPerThread) output.append("[truncated: frame limit]\n")
        }
        if (ordered.size > limits.threads) output.append("[truncated: thread limit]\n")
    }
}

/** Reserve marker space so even truncation of the first long line is visible within the character cap. */
private class SnapshotText(
    private val limit: Int,
) {
    private val content = StringBuilder()
    var full: Boolean = false
        private set

    fun append(text: String) {
        if (full) return
        val remaining = limit - content.length
        if (text.length <= remaining) {
            content.append(text)
        } else {
            val prefixLimit = limit - CHARACTER_MARKER.length
            if (content.length > prefixLimit) content.setLength(prefixLimit)
            content.append(text, 0, (prefixLimit - content.length).coerceAtLeast(0))
            content.append(CHARACTER_MARKER)
            full = true
        }
    }

    fun text(): String = content.toString()

    private companion object {
        const val CHARACTER_MARKER = "[truncated: character limit]\n"
    }
}
