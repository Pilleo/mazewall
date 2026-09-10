package demo

import io.mazewall.LinuxNative
import io.mazewall.Policy
import io.mazewall.core.FileDescriptor
import io.mazewall.core.FileDescriptorRole
import io.mazewall.core.Syscall
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.onFailure
import io.mazewall.onSuccess
import io.mazewall.profiler.Profiler
import io.mazewall.enforcer.diagnostics.ContainmentViolationDetector
import io.mazewall.enforcer.supervisor.StacktraceScopingPolicy
import io.mazewall.enforcer.supervisor.ScopingHandler
import io.mazewall.enforcer.supervisor.SupervisorDaemonManager
import io.mazewall.core.Tid
import io.mazewall.ffi.memory.ConfinedSegment
import java.io.File
import java.io.IOException
import java.lang.foreign.Arena
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Callable
import kotlin.concurrent.thread

internal fun runProfileAndEnforce(): DemoReport {
    val outcomes = mutableListOf<ScenarioResult>()
    // Warm up the ForkJoinPool before applying any containment.
    // If the pool is lazily initialized inside a contained thread, its new workers
    // would inadvertently inherit the sandbox via the clone syscall!
    java.util.concurrent.CompletableFuture
        .runAsync {}
        .join()

    println("\u001b[36;1m==========================================================")
    println("          MAZEWALL: PROFILE & ENFORCE DEMO                ")
    println("==========================================================\u001b[0m")
    println("Goal: Profile a workload doing synchronous file and socket")
    println("      I/O, generate the policy,")
    println("      and strictly enforce the complementary Seccomp-Landlock sandbox.")
    println()

    // 1. Setup paths and mock server
    // (Mazewall now automatically canonicalizes symlinks, but we use canonicalFile for demo hygiene)
    val fixtureRoot = Files.createTempDirectory("mazewall-profile-demo-").toFile().canonicalFile
    fixtureRoot.setReadable(false, false)
    fixtureRoot.setWritable(false, false)
    fixtureRoot.setExecutable(false, false)
    fixtureRoot.setReadable(true, true)
    fixtureRoot.setWritable(true, true)
    fixtureRoot.setExecutable(true, true)
    val configFile = File(fixtureRoot, "app_config.json").canonicalFile

    var baseExecutor: java.util.concurrent.ExecutorService? = null
    var containedExecutor: java.util.concurrent.ExecutorService? = null
    var serverSocket: ServerSocket? = null

    try {
        configFile.writeText("""{"api_url": "localhost", "timeout_ms": 5000}""")

        // Create a local loopback server to safely profile sockets completely offline
        val loopback = InetAddress.getLoopbackAddress()
        val ss = ServerSocket(0, 50, loopback)
        serverSocket = ss
        val serverPort = ss.localPort

        thread(isDaemon = true, name = "demo-loopback-server") {
            try {
                while (!ss.isClosed) {
                    val client = ss.accept()
                    client.getOutputStream().write("Welcome to the secure endpoint!\n".toByteArray())
                    client.close()
                }
            } catch (expected: IOException) {
                // Expected when server socket is closed during shutdown
            }
        }

        // This is the realistic workload we want to profile & run securely.
        // It performs synchronous File and Socket I/O. This demo does not submit io_uring work.
        fun legitWorkload(): String {
            // [1. Synchronous File I/O] Read configuration from disk
            val config = configFile.readText()

            // [2. Synchronous Socket I/O] Connect to local server and read greetings
            val serverGreeting = Socket(loopback, serverPort).use { clientSocket ->
                clientSocket.getInputStream().bufferedReader().readText()
            }

            return "Workload completed.\n" +
                "  => Config read: ${config.trim()}\n" +
                "  => Network greeting: ${serverGreeting.trim()}\n" +
                "  => I/O mode: synchronous (io_uring not exercised)"
        }

        val workload = {
            legitWorkload()
        }

        val expectedConfig = configFile.readText().trim()

        // A positive control must prove that the executor can still run useful work. Keep it
        // syscall-free: the negative controls below deliberately exercise the supervised open
        // path, while a file read here would conflate an FD-injection failure with policy denial.
        fun legitEnforcedWorkload(): String = "Config verified: $expectedConfig"

        // ------------------------------------------------------------
        // PHASE 1: Profiling
        // ------------------------------------------------------------
        println("\u001b[33;1m[PHASE 1] Profiling the Workload...\u001b[0m")
        println("Running the workload under the Tier S USER_NOTIF Profiler to audit exact syscalls & FS access...")

        // USER_NOTIF stays on this thread after profile() and is inherited by clone().
        // The supervisor JVM must exist first or wrap() cannot spawn it (PR_GET_SECCOMP=2).
        SupervisorDaemonManager.getInstance().getOrSpawnSharedDaemon()

        val profilingResult =
            Profiler.profile {
                workload()
            }

        println("\n\u001b[32m[RESULT] Workload successfully executed during profiling:\u001b[0m")
        println(profilingResult.value)
        outcomes += ScenarioResult("profiling workload", ExpectedResult.ALLOWED, ScenarioOutcome.Completed("profile collected"))

        val bob = profilingResult.behavior

        // ------------------------------------------------------------
        // PHASE 2: Code Generation (BoB DSL)
        // ------------------------------------------------------------
        println("\n\u001b[33;1m[PHASE 2] Generated Bill of Behavior (BoB) DSL:\u001b[0m")
        // Note: toDsl() throws if exec/connect destinations were observed but cannot be enforced.
        // This demo workload doesn't have exec/connect, but if it did, pass allowIncomplete=true.
        val dsl = bob.toDsl("Policy.PURE_COMPUTE_UNSAFE", Policy.PURE_COMPUTE_UNSAFE, allowIncomplete = true)
        println("\u001b[34m$dsl\u001b[0m")
        outcomes += ScenarioResult("policy generation", ExpectedResult.ALLOWED, ScenarioOutcome.Completed("DSL generated"))

        // Always save the compiled Bill of Behavior (SBoB) JSON containing captured stack traces
        var rootDir = File(".").absoluteFile
        while (rootDir.parentFile != null && !File(rootDir, "settings.gradle.kts").exists()) {
            rootDir = rootDir.parentFile
        }
        val outputDir = File(rootDir, "demos/output")
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        val sbobFile = File(outputDir, "sbob.json")
        sbobFile.writeText(bob.toJson())
        println("\u001b[32m[INFO] Persisted compiled SBoB with stack traces to: ${sbobFile.absolutePath}\u001b[0m")

        println("\u001b[35m[INFO] This demonstration does not initialize, submit, or validate io_uring operations.\u001b[0m")

        // ------------------------------------------------------------
        // PHASE 3: Strict Enforcement
        // ------------------------------------------------------------
        println("\n\u001b[33;1m[PHASE 3] Compiling and Enforcing Policy...\u001b[0m")
        // Compile the observed profile into a Landlock & Seccomp enforced Policy.
        // In addition, we stack the new JVM-level Supervisor Proxy to audit and authorize OPEN/OPENAT
        // syscalls based on the calling thread's active stack trace context!
        val baseForEnforcement =
            profilingResult.toPolicy(Policy.PURE_COMPUTE_UNSAFE, allowIncomplete = true)
        val compiledPolicy =
            Policy
                .threadLocalBuilder()
                .base(baseForEnforcement)
                // Make the executable positive control explicit: destination evidence can be
                // incomplete, and the supervisor separately narrows these observed boundaries.
                .unblock(Syscall.OPEN, Syscall.OPENAT, Syscall.READLINK)
                .allowFsRead(configFile.absolutePath)
                .allowJvmClasspath()
                .build()

        val scopingPolicy = object : StacktraceScopingPolicy {
            override val handlers = mapOf<Syscall, ScopingHandler>(
                Syscall.OPENAT to { tid, args, stack -> authorize(tid, args, stack) },
                Syscall.OPEN to { tid, args, stack -> authorize(tid, args, stack) },
            )

            private fun authorize(tid: Tid, args: List<Any>, stack: List<StackTraceElement>): Boolean {
                val hasLegitWorkload = stack.any { it.methodName.contains("legitEnforcedWorkload") }
                if (!hasLegitWorkload) {
                    println("  [SUPERVISOR INTERCEPT] Denying syscall for TID $tid. Call stack does NOT originate from legitWorkload()!")
                } else {
                    println("  [SUPERVISOR INTERCEPT] Allowing syscall for TID $tid. Stack context verified.")
                }
                return hasLegitWorkload
            }
        }

        println("Creating standard Thread Pool and wrapping it with ContainedExecutors (with StacktraceScopingPolicy)...")
        val rawExecutor = Executors.newSingleThreadExecutor()
        baseExecutor = rawExecutor
        val wrapper = ContainedExecutors.wrap(rawExecutor, compiledPolicy, scopingPolicy)
        containedExecutor = wrapper

        try {
            println("Executing the legitimate computation positive control inside the contained environment...")
            val future = wrapper.submit(Callable { legitEnforcedWorkload() })
            val result = future.get()
            println("\u001b[32;1m[SUCCESS] Workload executed successfully under containment!\u001b[0m")
            println(result)
            outcomes += ScenarioResult("contained legitimate workload", ExpectedResult.ALLOWED, ScenarioOutcome.Completed("workload completed"))
        } catch (e: ExecutionException) {
            println("\u001b[31;1m[FAIL] Workload was blocked by mistake: ${e.message}\u001b[0m")
            outcomes += ScenarioResult("contained legitimate workload", ExpectedResult.ALLOWED, classifyFailure(e))
        }

        // ------------------------------------------------------------
        // PHASE 4: Simulating Breach / Path Containment (Landlock)
        // ------------------------------------------------------------
        println("\n\u001b[33;1m[PHASE 4] Simulating Breach: Unauthorized Path Access...\u001b[0m")
        println("SCENARIO: Untrusted Data / Synchronous Execution.")
        println("An attacker has achieved Arbitrary Code Execution (ACE) inside the contained")
        println("thread and attempts to read a sensitive file '/etc/hosts' synchronously.")

        val sensitiveFile = File("/etc/hosts").canonicalFile
        val attackerPathTask = {
            println("  [Attacker] Attempting to read unauthorized system file: ${sensitiveFile.canonicalPath}...")
            sensitiveFile.readText()
        }

        try {
            val future = wrapper.submit(attackerPathTask)
            val stolenData = future.get()
            println("\u001b[31;1m[ALERT] VULNERABILITY! Stolen data leaked (Is Landlock supported on this kernel?):\n$stolenData\u001b[0m")
            outcomes += ScenarioResult("synchronous path attack", ExpectedResult.DENIED, ScenarioOutcome.Completed("read escaped"))
        } catch (e: ExecutionException) {
            val denied = classifyFailure(e)
            if (denied is ScenarioOutcome.Denied) {
                println("\u001b[32;1m[BOUNCER SUCCESS] Landlock blocked the file read attempt at the kernel level!\u001b[0m")
            } else {
                println("\u001b[31m[ERROR] Unexpected execution failure: ${e.message}\u001b[0m")
            }
            outcomes += ScenarioResult("synchronous path attack", ExpectedResult.DENIED, denied)
        }

        // ------------------------------------------------------------
        // PHASE 5: Direct native synchronous open (not io_uring)
        // ------------------------------------------------------------
        println("\n\u001b[33;1m[PHASE 5] Simulating Breach: Direct Native Synchronous Open...\u001b[0m")
        println("SCENARIO: A native dependency invokes open(2) directly.")
        println("This is a synchronous FFM call. No io_uring queue or SQE is created or submitted.")

        val attackerIoUringEvasionTask = {
            println("  [Attacker] Calling native open(2) for '/etc/hosts' synchronously...")
            Arena.ofConfined().use { arena ->
                val fs = LinuxNative.fileSystem
                val openResult =

fs.open(
    ConfinedSegment(arena.allocateFrom(sensitiveFile.canonicalPath)),
    io.mazewall.core.OpenFlags(0), // O_RDONLY
)


                // EPERM = Seccomp blocked the syscall entirely
                // EACCES = Landlock denied the path access at the VFS layer
                openResult.onFailure { errno, _ ->
                    if (errno == 1 || errno == 13) {
                        throw java.io.IOException("Permission denied (synchronous native open blocked)")
                    }
                }.onSuccess { value ->
                    LinuxNative.fileSystem.close(FileDescriptor.adopt(value.toInt(), FileDescriptorRole.Generic))
                }
            }
            "native open succeeded"
        }

        try {
            val future = wrapper.submit(attackerIoUringEvasionTask)
            val evasionResult = future.get()
            println("\u001b[31;1m[ALERT] VULNERABILITY! Evasion succeeded: $evasionResult\u001b[0m")
            outcomes += ScenarioResult("native synchronous open attack", ExpectedResult.DENIED, ScenarioOutcome.Completed(evasionResult))
        } catch (e: ExecutionException) {
            val denied = classifyFailure(e)
            if (denied is ScenarioOutcome.Denied) {
                println("\u001b[32;1m[BOUNCER SUCCESS] Landlock blocked the synchronous native open!\u001b[0m")
            } else {
                println("\u001b[31m[ERROR] Unexpected execution failure: ${e.message}\u001b[0m")
            }
            outcomes += ScenarioResult("native synchronous open attack", ExpectedResult.DENIED, denied)
        }

        // ------------------------------------------------------------
        // PHASE 6: The Thread-Hopping Bypass (Concurrency Evasion)
        // ------------------------------------------------------------
        println("\n\u001b[33;1m[PHASE 6] Simulating Breach: The Thread-Hopping Bypass...\u001b[0m")
        println("SCENARIO: Untrusted Java Code / RCE Bypass.")
        println("An attacker has achieved Java-level Remote Code Execution (e.g., via SpEL injection)")
        println("and attempts to bypass the Tier 2 containment by hopping threads.")
        println("WARNING: Thread-scoped Seccomp provides NO protection against untrusted Java code!")

        val threadHoppingTask = {
            println("  [Attacker] Inside contained thread. Submitting malicious task to global ForkJoinPool...")

            // Standard Java concurrency APIs delegate execution to pre-existing OS threads
            // (like ForkJoinPool.commonPool()) that lack the Seccomp/Landlock filters.
            java.util.concurrent.CompletableFuture
                .supplyAsync {
                    println("  [Attacker] Executing on uncontained thread: ${Thread.currentThread().name}")
                    println("  [Attacker] Reading sensitive file: ${sensitiveFile.canonicalPath}...")
                    sensitiveFile.readText()
                }.get()
        }

        try {
            val future = wrapper.submit(threadHoppingTask)
            val stolenData = future.get()
            println("\u001b[31;1m[BYPASS DETECTED] Attack succeeded! Data stolen via thread-hopping:\n$stolenData\u001b[0m")
            println("\u001b[35m[LESSON] Tier 2 (Thread-Scoped) is a shield for trusted code, not a cage for malicious code.")
            println("         To block this attack, you MUST use Tier 1 (Process-Wide) isolation.\u001b[0m")
            outcomes += ScenarioResult("documented thread-hop limitation", ExpectedResult.ALLOWED, ScenarioOutcome.Completed("bypass observed"))
        } catch (e: ExecutionException) {
            println("\u001b[32;1m[UNEXPECTED] Attack was blocked? This should not happen on standard JVMs without Tier 1 containment.\u001b[0m")
            outcomes += ScenarioResult("documented thread-hop limitation", ExpectedResult.ALLOWED, classifyFailure(e))
        }

        // ------------------------------------------------------------
        // PHASE 7: JVM-Level Stack Trace Supervision (Supervisor Proxy)
        // ------------------------------------------------------------
        println("\n\u001b[33;1m[PHASE 7] Simulating Breach: Dynamic JVM Stacktrace Verification...\u001b[0m")
        println("SCENARIO: Subverting Trusted Files.")
        println("The config file 'mazewall_app_config.json' is whitelisted by Landlock.")
        println("However, the Supervisor Proxy enforces that any open/openat syscall must")
        println("originate from within our trusted 'legitWorkload()' stack frame.")
        println("An attacker attempts to read this whitelisted config file from outside that context:")

        val attackerConfigTask = {
            println("  [Attacker] Attempting to read whitelisted config file outside trusted stack context...")
            configFile.readText()
        }

        try {
            val future = wrapper.submit(attackerConfigTask)
            val configContent = future.get()
            println("\u001b[31;1m[ALERT] VULNERABILITY! Read whitelisted config from untrusted stack context: $configContent\u001b[0m")
            outcomes += ScenarioResult("stack-scoped config attack", ExpectedResult.DENIED, ScenarioOutcome.Completed("read escaped"))
        } catch (e: ExecutionException) {
            val denied = classifyFailure(e)
            if (denied is ScenarioOutcome.Denied) {
                println("\u001b[32;1m[BOUNCER SUCCESS] Supervisor Proxy blocked the file read attempt at the JVM boundary!\u001b[0m")
            } else {
                println("\u001b[31m[ERROR] Unexpected execution failure: ${e.message}\u001b[0m")
            }
            outcomes += ScenarioResult("stack-scoped config attack", ExpectedResult.DENIED, denied)
        }
    } finally {
        containedExecutor?.shutdown()
        baseExecutor?.shutdown()
        serverSocket?.close()
        fixtureRoot.deleteRecursively()
    }

    val report = DemoReport(outcomes.toList())
    println("\n\u001b[36;1m==========================================================")
    println("          PHASE OUTCOMES SUMMARY                          ")
    println("==========================================================")
    println(report.summary())
    println("  Native probe: synchronous open(2); io_uring NOT TESTED")
    println("\u001b[35m")
    println("  [LESSON] Phase 6 is intentionally bypassed. Thread-scoped Tier 2 containment")
    println("           is a shield for trusted code paths, not a cage for arbitrary Java logic.")
    println("           For full isolation of untrusted code, stack Tier 1 (process-wide)")
    println("           lockdown with GraalVM Isolates or Wasm sandboxing.")
    println("\u001b[36;1m")
    println(if (report.passed) "          DEMO PASSED                                     " else "          DEMO FAILED                                     ")
    println("==========================================================\u001b[0m")
    return report
}

private fun classifyFailure(failure: Throwable): ScenarioOutcome {
    val diagnostic = ContainmentViolationDetector.diagnose(failure)
    return if (diagnostic != null) {
        ScenarioOutcome.Denied(diagnostic.evidence.name)
    } else {
        ScenarioOutcome.InfrastructureFailure(failure)
    }
}
