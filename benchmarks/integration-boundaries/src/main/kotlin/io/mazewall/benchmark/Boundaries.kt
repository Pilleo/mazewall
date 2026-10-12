package io.mazewall.benchmark

import io.mazewall.demo.koog.TextBounds
import io.mazewall.demo.koog.TextTransformerService
import io.mazewall.enforcer.api.FileSystemPolicy
import io.mazewall.enforcer.api.ProcessPolicyBuilder
import io.mazewall.enforcer.api.RuntimeProfile
import io.mazewall.enforcer.api.WorkerPolicyBuilder
import io.mazewall.enforcer.api.execution.DrainReport
import io.mazewall.enforcer.api.execution.DomainLimits
import io.mazewall.enforcer.api.execution.ExecutionEnd
import io.mazewall.enforcer.api.execution.InvocationHandle
import io.mazewall.enforcer.api.execution.ManagedRuntime
import io.mazewall.platform.syscall.Syscall
import io.mazewall.portal.PortalCallException
import io.mazewall.portal.PortalInvocationEvidence
import io.mazewall.portal.PortalInstanceMode
import io.mazewall.portal.PortalInvocationTiming
import io.mazewall.portal.PortalOperationCall
import io.mazewall.portal.PortalRuntime
import io.mazewall.portal.PortalRuntimeConfig
import io.mazewall.portal.PortalRuntimeLimits
import io.mazewall.portal.PortalServiceId
import io.mazewall.portal.PortalServiceManifest
import io.mazewall.portal.PortalServiceManifestCatalog
import io.mazewall.portal.PortalServiceHealth
import io.mazewall.portal.PortalServiceHealthState
import io.mazewall.portal.PortalWorkerLifecycle
import io.mazewall.portal.PortalWorkerLifecycleState
import io.mazewall.portal.PortalWorkerProfile
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

private enum class Mode { DIRECT, JDK, MANAGED, PROCESS_PORTAL }

private data class Options(val samples: Int, val size: Int, val pressure: Int, val seconds: Int) {
    companion object {
        fun parse(args: Array<String>): Options {
            require(args.size % 2 == 0) { "Expected --name value pairs" }
            val values = args.toList().chunked(2).associate { it[0] to it[1].toInt() }
            require(values.size == args.size / 2 && values.keys.all { it in setOf("--samples", "--size", "--pressure", "--seconds", "--concurrency") })
            require(values.getOrDefault("--concurrency", 1) == 1) { "Packaged singleton supports one equivalent execution lane" }
            return Options(values.getOrDefault("--samples", 8), values.getOrDefault("--size", 1024), values.getOrDefault("--pressure", 4), values.getOrDefault("--seconds", 60)).also {
                require(it.samples in 2..1000 && it.size in 1..65536 && it.pressure in 3..64 && it.seconds in 5..600)
            }
        }
    }
}

private class Budget(seconds: Int) {
    private val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds.toLong())
    fun remaining(): Long = (deadline - System.nanoTime()).also { if (it <= 0) throw TimeoutException("Benchmark time budget exhausted") }
    fun <T> await(value: CompletableFuture<T>): T = value.get(remaining(), TimeUnit.NANOSECONDS)
}

private class Request(val id: Int, val text: String, val gate: CountDownLatch? = null, val entered: CountDownLatch? = null) {
    val submitted = System.nanoTime()
    @Volatile var started = 0L
    @Volatile var ended = 0L
    fun execute(work: (String) -> String): String {
        started = System.nanoTime()
        entered?.countDown()
        try {
            check(gate == null || gate.await(10, TimeUnit.SECONDS)) { "Pressure gate timeout" }
            return work(text)
        } finally {
            ended = System.nanoTime()
        }
    }
}

private fun transform(text: String): String {
    TextBounds.requireBounded(text)
    val result = buildString(text.length) {
        text.forEach { character -> append(if (character in 'a'..'z') character.uppercaseChar() else character) }
    }
    TextBounds.requireBounded(result)
    return result
}

private fun input(id: Int, size: Int): String = buildString(size) {
    repeat(size) { append(('a'.code + (id + it) % 26).toChar()) }
}

private fun expected(text: String): String = text.uppercase(java.util.Locale.ROOT)
private fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

/** TSV: fixed schema; only explicit noncredential metadata and numeric measurements. */
private fun row(vararg fields: Any?) = println(fields.joinToString("\t") { it?.toString() ?: "unavailable" })

private fun resources(label: String, pid: Long = ProcessHandle.current().pid()) {
    val status = Files.readAllLines(Path.of("/proc/$pid/status"))
    fun field(name: String) = status.first { it.startsWith("$name:") }.substringAfter(':').trim().replace('\t', ' ')
    row("resources", label, pid, field("VmRSS"), field("Threads"), ManagementFactory.getMemoryMXBean().heapMemoryUsage.used, ManagementFactory.getThreadMXBean().threadCount)
}

private fun preparedManifest(manifests: List<PortalServiceManifest>, id: PortalServiceId): PortalServiceManifest =
    manifests.filter { it.serviceId == id }.single().also {
        check(it.contractName == TextTransformerService::class.java.name)
        check(it.instanceMode == PortalInstanceMode.STATEFUL_SINGLETON)
    }

private fun preparedHealth(health: List<PortalServiceHealth>, manifest: PortalServiceManifest, pid: Long? = null): PortalServiceHealth =
    health.filter { it.isolationGroup == manifest.isolationGroup }.single().also {
        check(it.instanceMode == manifest.instanceMode && it.state == PortalServiceHealthState.AVAILABLE)
        check(it.policyProfile == manifest.policyProfile)
        val actualPid = it.workerPids.single()
        check(actualPid > 0 && (pid == null || actualPid == pid))
        check(it.lastWorkerLifecycle.state == PortalWorkerLifecycleState.STARTED && it.lastWorkerLifecycle.pid == actualPid)
    }

private fun preparedTiming(health: PortalServiceHealth, previous: PortalInvocationTiming?, requestId: Int, expectedId: Int): PortalInvocationTiming {
    check(requestId == expectedId) { "Nonserial Portal timing request ID" }
    return checkNotNull(health.lastInvocation).also {
        // The public contract has no wire ID: serial completion must publish a new timing object.
        check(it !== previous) { "Stale Portal timing for request $requestId" }
        check(!it.queueWait.isNegative && !it.workerExecution.isNegative)
    }
}

private fun selectioncheck() {
    val manifests = PortalServiceManifestCatalog.load(TextTransformerService::class.java.classLoader)
    val id = PortalServiceId("bounded-text")
    val manifest = preparedManifest(manifests, id)
    val denied = manifests.single { it.serviceId == PortalServiceId("test-denied") }
    check(manifest.isolationGroup != denied.isolationGroup)
    val pid = ProcessHandle.current().pid()
    val timing = PortalInvocationTiming(Duration.ofNanos(1), Duration.ofNanos(2))
    val selected = PortalServiceHealth(manifest.isolationGroup, manifest.instanceMode, PortalServiceHealthState.AVAILABLE,
        policyProfile = manifest.policyProfile, lastWorkerLifecycle = PortalWorkerLifecycle(PortalWorkerLifecycleState.STARTED, pid),
        workerPids = listOf(pid), lastInvocation = timing)
    val other = selected.copy(isolationGroup = denied.isolationGroup, workerPids = listOf(pid + 1), lastInvocation = timing.copy())
    fun rejects(name: String, action: () -> Unit) { check(runCatching(action).isFailure) { "Selection control accepted $name" } }
    check(preparedHealth(listOf(other, selected), manifest, pid) === selected)
    check(preparedHealth(listOf(selected, other), manifest, pid) === selected)
    check(preparedTiming(selected, null, 0, 0) === timing)
    check(preparedTiming(selected.copy(lastInvocation = timing.copy()), timing, 1, 1) !== timing)
    rejects("missing manifest") { preparedManifest(listOf(denied), id) }
    rejects("duplicate manifest") { preparedManifest(manifests + manifest, id) }
    rejects("contract mismatch") { preparedManifest(listOf(manifest.copy(contractName = denied.contractName)), id) }
    rejects("unsupported mode") { preparedManifest(listOf(manifest.copy(instanceMode = PortalInstanceMode.STATELESS)), id) }
    rejects("missing group") { preparedHealth(listOf(other), manifest, pid) }
    rejects("substring group") { preparedHealth(listOf(selected.copy(isolationGroup = manifest.isolationGroup + "-other")), manifest, pid) }
    rejects("duplicate group") { preparedHealth(listOf(selected, selected, other), manifest, pid) }
    rejects("duplicate PID") { preparedHealth(listOf(selected.copy(workerPids = listOf(pid, pid))), manifest, pid) }
    rejects("PID mismatch") { preparedHealth(listOf(selected), manifest, pid + 1) }
    rejects("lifecycle PID mismatch") { preparedHealth(listOf(selected.copy(lastWorkerLifecycle = selected.lastWorkerLifecycle.copy(pid = pid + 1))), manifest, pid) }
    rejects("state lost") { preparedHealth(listOf(selected.copy(state = PortalServiceHealthState.STATE_LOST)), manifest, pid) }
    rejects("missing timing") { preparedTiming(selected.copy(lastInvocation = null), null, 0, 0) }
    rejects("stale timing") { preparedTiming(selected, timing, 1, 1) }
    rejects("request mismatch") { preparedTiming(selected, null, 1, 0) }
    rejects("negative timing") { preparedTiming(selected.copy(lastInvocation = timing.copy(queueWait = Duration.ofNanos(-1))), null, 0, 0) }
}

private fun selfcheck() {
    check(transform("aBc éß🙂") == "ABC éß🙂")
    check(transform(input(7, 65536)) == expected(input(7, 65536)))
    val invalid = listOf(arrayOf("--samples", "0"), arrayOf("--size", "65537"), arrayOf("--seconds", "0"), arrayOf("--pressure", "65"), arrayOf("--concurrency", "2"), arrayOf("--samples", "2", "--samples", "3"), arrayOf("--unknown", "1"), arrayOf("--size"))
    invalid.forEach { args -> check(runCatching { Options.parse(args) }.exceptionOrNull() is IllegalArgumentException) }
    check(runCatching { TextBounds.requireBounded("é".repeat(32769)) }.exceptionOrNull() is IllegalArgumentException)
    check(runCatching { Budget(0).remaining() }.exceptionOrNull() is TimeoutException)
    val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(1), Thread.ofPlatform().factory(), ThreadPoolExecutor.AbortPolicy())
    val release = CountDownLatch(1)
    try {
        executor.prestartAllCoreThreads()
        val entered = CountDownLatch(1)
        val first = executor.submit { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        check(entered.await(5, TimeUnit.SECONDS))
        val queued = executor.submit(Callable { transform("abc") })
        check(runCatching { executor.submit {} }.exceptionOrNull() is RejectedExecutionException)
        check(runCatching { first.get(1, TimeUnit.MILLISECONDS) }.exceptionOrNull() is TimeoutException)
        release.countDown()
        first.get(5, TimeUnit.SECONDS)
        check(queued.get(5, TimeUnit.SECONDS) == "ABC")
    } finally {
        release.countDown()
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS))
    }
    row("selfcheck", "PASS", "unicode,bounds,overload,timeout,physical-end")
}

fun main(args: Array<String>) {
    if (args.contentEquals(arrayOf("--selectioncheck"))) { selectioncheck(); println("selectioncontrols PASS actual-multigroup,order,missing,duplicate,contract,mode,group,PID,lifecycle,state,timing,freshness,request-ID"); return }
    if (args.contentEquals(arrayOf("--selfcheck"))) { selfcheck(); return }
    val options = Options.parse(args)
    val budget = Budget(options.seconds)
    selfcheck()
    // Preload the local body before imposing Landlock. The guest implementation remains opaque.
    check(transform(input(0, options.size)) == expected(input(0, options.size)))
    check(runCatching { Class.forName("io.mazewall.demo.koog.worker.AsciiTextTransformer") }.exceptionOrNull() is ClassNotFoundException)
    row("schema", 1, "monotonic-ns", "checking-only")
    row("environment", System.getProperty("java.runtime.version"), System.getProperty("java.vendor"), System.getProperty("os.name"), System.getProperty("os.arch"), options)
    row("unavailable", "isolated-native-install,IPC,guest-body,dispatch-overhead,per-call-child-memory")
    val jdkThread = AtomicReference<Thread>()
    val jdk = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue(1), { task ->
        Thread.ofPlatform().name("benchmark-jdk").inheritInheritableThreadLocals(false).unstarted(task).also {
            check(jdkThread.compareAndSet(null, it)) { "No replacement worker or inherited Landlock fallback" }
        }
    }, ThreadPoolExecutor.AbortPolicy())
    var primary: Throwable? = null
    try {
        val prestart = System.nanoTime()
        check(jdk.prestartAllCoreThreads() == 1)
        row("prepare", "JDK", System.nanoTime() - prestart, "thread-prestart")
        // Explicit permanent Tier1 allows Portal child launch. No host Landlock is installed.
        val baseline = ProcessPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).allowMmapExec().block(Syscall.PTRACE, Syscall.MOUNT, Syscall.UNSHARE, Syscall.CHROOT).build()
        val opened = System.nanoTime()
        ManagedRuntime.open(RuntimeProfile.HOTSPOT_JIT, baseline, Duration.ofSeconds(30), Duration.ofSeconds(10)).use { managed ->
            row("prepare", "MANAGED", System.nanoTime() - opened, "open+required-process-install")
            val prepare = System.nanoTime()
            val policy = WorkerPolicyBuilder.denyList(RuntimeProfile.HOTSPOT_JIT).filesystem(FileSystemPolicy.DenyAll).allowJvmClasspath().block(Syscall.EXECVE, Syscall.EXECVEAT).build()
            val domain = managed.prepare(policy, DomainLimits(1, 2))
            row("prepare", "MANAGED", System.nanoTime() - prepare, "domain-prepare+required-install+readiness")
            val cache = Path.of(System.getProperty("benchmark.cache", "worker-cache")).toAbsolutePath()
            check(!Files.exists(cache)) { "Use a fresh worker bundle cache for the cold sample" }
            val configStart = System.nanoTime()
            val config = PortalRuntimeConfig.load(workerBundleCache = cache, workerProfiles = mapOf("static-baseline" to PortalWorkerProfile()), limits = PortalRuntimeLimits(workers = 1, maxAdmissions = 2, callTimeoutMs = 15000, startupTimeoutMs = 30000, closeTimeoutMs = 10000, maxRequestBytes = 131072, maxResponseBytes = 131072, maxCapabilities = 0))
            row("prepare", "PROCESS_PORTAL", System.nanoTime() - configStart, "metadata+bundle-catalog")
            config.workerArtifacts.forEach { (name, artifact) -> row("worker-artifact", name, artifact.sha256) }
            val portalOpen = System.nanoTime()
            val children = mutableListOf<ProcessHandle>()
            var portalFailure: Throwable? = null
            try {
                PortalRuntime.open(config).use { portal ->
                    row("prepare", "PROCESS_PORTAL", System.nanoTime() - portalOpen, "runtime-open")
                    val bindingStart = System.nanoTime()
                    val serviceId = PortalServiceId("bounded-text")
                    val manifest = preparedManifest(config.manifests, serviceId)
                    val operation = portal.prepare(TextTransformerService::class.java, serviceId, PortalOperationCall<TextTransformerService, Request, String> { service, request -> request.execute(service::uppercaseAscii) })
                    row("prepare", "PROCESS_PORTAL", System.nanoTime() - bindingStart, "binding+materialize+process-startup+required-install+readiness")
                    val ready = preparedHealth(portal.health(), manifest)
                    check(ready.lastInvocation == null)
                    ready.workerPids.forEach { pid ->
                        val child = ProcessHandle.of(pid).orElseThrow()
                        check(child.isAlive)
                        children.add(child)
                        resources("worker-ready", pid)
                    }
                    check(children.size == 1)
                    var previousTiming: PortalInvocationTiming? = null
                    var nextPortalRequestId = 0
                    for (mode in Mode.entries) {
                        resources("$mode-before")
                        var accepted = 0
                        var rejected = 0
                        var completed = 0
                        val batchStart = System.nanoTime()
                        fun submit(request: Request): Pair<CompletableFuture<String>, CompletableFuture<ExecutionEnd>> = when (mode) {
                            Mode.DIRECT -> {
                                val value = request.execute(::transform)
                                CompletableFuture.completedFuture(value) to CompletableFuture.completedFuture(ExecutionEnd.Finished(null))
                            }
                            Mode.JDK -> {
                                val result = CompletableFuture<String>()
                                val end = CompletableFuture<ExecutionEnd>()
                                jdk.execute {
                                    var failure: Throwable? = null
                                    try { result.complete(request.execute(::transform)) } catch (caught: Throwable) { failure = caught; result.completeExceptionally(caught) } finally { end.complete(ExecutionEnd.Finished(failure)) }
                                }
                                result to end
                            }
                            Mode.MANAGED -> managed.submit(domain, Callable { request.execute(::transform) }).futures()
                            Mode.PROCESS_PORTAL -> operation.submit(request).futures()
                        }
                        fun collect(request: Request, phase: String, futures: Pair<CompletableFuture<String>, CompletableFuture<ExecutionEnd>>?) {
                            if (futures == null) { rejected++; row("rejected", mode, phase, request.id, request.text.length, digest(request.text), "synchronous-admission"); return }
                            val value = try { budget.await(futures.first) } catch (failure: ExecutionException) {
                                val end = budget.await(futures.second)
                                check(mode == Mode.PROCESS_PORTAL && phase == "pressure" && end is ExecutionEnd.NotStarted && failure.cause is PortalCallException && (failure.cause as PortalCallException).evidence == PortalInvocationEvidence.NOT_STARTED) { "Unexpected result failure: $failure / $end" }
                                check(request.started == 0L)
                                rejected++; row("rejected", mode, phase, request.id, request.text.length, digest(request.text), "not-started-handle"); return
                            }
                            val delivered = System.nanoTime()
                            val end = budget.await(futures.second)
                            val observedEnd = System.nanoTime()
                            check(end is ExecutionEnd.Finished && end.failure == null)
                            check(value == expected(request.text)) { "Wrong result $mode ${request.id}" }
                            check(request.submitted <= request.started && request.started <= request.ended && request.ended <= observedEnd)
                            accepted++; completed++
                            row("sample", mode, phase, request.id, request.text.toByteArray().size, digest(request.text), digest(value), request.submitted, request.started, request.ended, delivered, observedEnd, request.started - request.submitted, request.ended - request.started, if (mode == Mode.PROCESS_PORTAL) "host-mapping+transport+worker" else "local-body")
                            if (mode == Mode.PROCESS_PORTAL && phase != "pressure") {
                                 val health = preparedHealth(portal.health(), manifest, children.single().pid())
                                 val timing = preparedTiming(health, previousTiming, request.id, nextPortalRequestId)
                                 check(timing.queueWait.plus(timing.workerExecution).toNanos() <= request.ended - request.started)
                                 previousTiming = timing
                                 nextPortalRequestId++
                                row("portal-timing", phase, request.id, timing.queueWait.toNanos(), timing.workerExecution.toNanos(), "broker-queue,transport+worker")
                            }
                        }
                        repeat(options.samples + 1) { id ->
                            budget.remaining()
                            val request = Request(id, input(id, options.size))
                            collect(request, if (id == 0) "first-measured" else "warm", submit(request))
                        }
                        val ordinaryEnd = System.nanoTime()
                        row("throughput", mode, options.samples + 1, ordinaryEnd - batchStart, "includes-result-verification+raw-emission")
                        val gate = if (mode == Mode.DIRECT) null else CountDownLatch(1)
                        val entered = if (gate == null) null else CountDownLatch(1)
                        val pending = mutableListOf<Pair<Request, Pair<CompletableFuture<String>, CompletableFuture<ExecutionEnd>>?>>()
                        try {
                            repeat(options.pressure) { offset ->
                                val request = Request(options.samples + 1 + offset, input(options.samples + 1 + offset, options.size), gate, entered)
                                val futures = try { submit(request) } catch (_: RejectedExecutionException) { null }
                                pending.add(request to futures)
                                if (offset == 0 && entered != null) check(entered.await(budget.remaining(), TimeUnit.NANOSECONDS))
                            }
                        } finally { gate?.countDown() }
                        pending.forEach { (request, futures) -> collect(request, "pressure", futures) }
                        check(accepted == completed && accepted + rejected == options.samples + 1 + options.pressure)
                        check(if (mode == Mode.DIRECT) rejected == 0 else rejected == options.pressure - 2)
                        row("accounting", mode, accepted + rejected, accepted, rejected, completed)
                        resources("$mode-after")
                        if (mode == Mode.DIRECT) row("drain", mode, 0, "synchronous-no-owner")
                    }
                    children.forEach { resources("worker-after-work", it.pid()) }
                    val drainStart = System.nanoTime()
                    portal.close()
                    row("drain", "PROCESS_PORTAL", System.nanoTime() - drainStart, "close-returned")
                    val after = operation.submit(Request(-1, "abc"))
                    check(budget.await(after.executionEnd().toCompletableFuture()) is ExecutionEnd.NotStarted)
                    check(runCatching { budget.await(after.result().toCompletableFuture()) }.exceptionOrNull() is ExecutionException)
                    row("post-drain", "PROCESS_PORTAL", "NOT_STARTED")
                }
            } catch (failure: Throwable) {
                portalFailure = failure
                throw failure
            } finally {
                children.forEach { child ->
                    row("physical-child-end", child.pid(), child.isAlive)
                    if (child.isAlive) {
                        val failure = IllegalStateException("Portal close retained live acquired worker ${child.pid()}")
                        if (portalFailure == null) throw failure else portalFailure.addSuppressed(failure)
                    }
                }
            }
            val drainStart = System.nanoTime()
            check(managed.drain(Duration.ofSeconds(10)) == DrainReport.Complete)
            row("drain", "MANAGED", System.nanoTime() - drainStart, "Complete")
            check(runCatching { managed.submit(domain, Callable { "unreachable" }) }.exceptionOrNull() is RejectedExecutionException)
            row("post-drain", "MANAGED", "REJECTED")
        }
    } catch (failure: Throwable) {
        primary = failure
        throw failure
    } finally {
        val drainStart = System.nanoTime()
        jdk.shutdownNow()
        try {
            check(jdk.awaitTermination(10, TimeUnit.SECONDS)) { "JDK owner did not terminate" }
            jdkThread.get()?.let { thread ->
                thread.join(10_000)
                row("physical-thread-end", "JDK", thread.threadId(), thread.isAlive)
                check(!thread.isAlive) { "Acquired JDK worker is still alive" }
            }
            row("drain", "JDK", System.nanoTime() - drainStart, "terminated+joined")
            check(runCatching { jdk.execute { error("unreachable") } }.exceptionOrNull() is RejectedExecutionException)
            row("post-drain", "JDK", "REJECTED")
        } catch (failure: Throwable) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            if (primary == null) throw failure else primary.addSuppressed(failure)
        }
    }
    resources("final")
    row("checking-smoke", "PASS", "all-accepted-results-verified,physical-ends-accounted")
}

private fun InvocationHandle<String>.futures() = result().toCompletableFuture() to executionEnd().toCompletableFuture()
