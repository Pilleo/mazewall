package io.mazewall.profiler.tierE.stress

import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.profiler.internal.attribution.AgentDefinitionFile
import io.mazewall.profiler.internal.attribution.ExecutionKind
import io.mazewall.profiler.internal.attribution.ResolutionStatus
import io.mazewall.profiler.internal.attribution.TierECaptureBinding
import io.mazewall.profiler.internal.attribution.TierECaptureProfile
import io.mazewall.profiler.internal.attribution.TierECaptureProfilePreset
import io.mazewall.profiler.internal.attribution.TierECollectorLoop
import io.mazewall.profiler.internal.attribution.TierECollectorLoopPolicy
import io.mazewall.profiler.internal.attribution.TierECollectorResultMode
import io.mazewall.profiler.internal.attribution.TierEEmissionMode
import io.mazewall.profiler.internal.attribution.TierEKnownJoinMode
import io.mazewall.profiler.internal.attribution.TierELinuxMonotonicClock
import io.mazewall.profiler.internal.attribution.TierEOptions
import io.mazewall.profiler.internal.attribution.TierEProfile
import io.mazewall.profiler.internal.attribution.TierEProfilerSession
import io.mazewall.profiler.internal.attribution.TierEReadyTransferMode
import io.mazewall.profiler.internal.attribution.TierERingArgumentListMode
import io.mazewall.profiler.internal.attribution.TierERingArgumentViewMode
import io.mazewall.profiler.internal.attribution.TierERingDecodeMode
import io.mazewall.profiler.internal.attribution.TierERingSubmissionMode
import io.mazewall.profiler.internal.attribution.TierEStackTransport
import io.mazewall.profiler.internal.attribution.TierETaskIdentityMode
import io.mazewall.profiler.internal.attribution.UniqueEdgeDecision
import io.mazewall.profiler.tierE.daemon.AttachMode
import io.mazewall.profiler.tierE.daemon.ElfSymbolOffset
import io.mazewall.profiler.tierE.daemon.UsdtProbeNotes
import io.mazewall.profiler.tierE.daemon.selectMarkerTransport
import io.mazewall.profiler.tierE.engine.TierEbpfEngine
import io.mazewall.profiler.tierE.ringbuf.SyscallInvocationEvent
import io.mazewall.profiler.triage.ManagedProcessCapture
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Privileged end-to-end diagnostic used by the Docker kernel gate. */
internal object TierEKernelSmoke {
    @JvmStatic
    public fun main(args: Array<String>) {
        require(
            args.size in 4..13,
        ) {
            "usage: <agent.so> <marker-offset-hex|auto> <workload-classpath> <definitions-file> [virtual|platform] [iterations] [force-cpu] [full-stream|unique-stack-syscall] [dictionary|inline-per-invocation] [common-file-workload] [benchmark <warmups> <batches>]"
        }
        val agent = Path.of(args[0]).toAbsolutePath()
        val markerOffset = if (args[1] == "auto") {
            ElfSymbolOffset.resolve(agent, "mazewall_stack_marker")
        } else {
            args[1].removePrefix("0x").toLong(16)
        }
        val requestedMarkerTransport = when (System.getProperty("mazewall.tier-e.marker-transport", "uprobe")) {
            "auto" -> AttachMode.AUTO
            "uprobe" -> AttachMode.UPROBE
            "usdt" -> AttachMode.USDT
            else -> error("unknown Tier E marker transport")
        }
        val markerSelection = selectMarkerTransport(requestedMarkerTransport) {
            UsdtProbeNotes.findStackMarkerV1(agent)
        }
        val markerTransport = markerSelection.selected.name.lowercase()
        val usdtNote = markerSelection.usdtNote
        val definitions = Path.of(args[3]).toAbsolutePath()
        val captureYieldPath = System.getProperty("mazewall.tier-e.capture-yield")?.let(Path::of)?.toAbsolutePath()
        val straceOutputPath = System
            .getProperty("mazewall.tier-e.benchmark-strace-output")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
        val lifecycleScenario = System.getProperty("mazewall.tier-e.lifecycle-scenario", "normal")
        require(lifecycleScenario in setOf("normal", "detach_while_active", "callback_suppression", "terminal_while_active", "capture_failure", "exec_transition")) {
            "unknown Tier E lifecycle scenario: $lifecycleScenario"
        }
        val sessionTag = 0x4d5a
        val emissionMode = when (args.getOrElse(7) { "full-stream" }) {
            "full-stream" -> TierEEmissionMode.FULL_STREAM
            "unique-stack-syscall" -> TierEEmissionMode.UNIQUE_STACK_SYSCALL
            else -> error("unknown Tier E emission mode: ${args[7]}")
        }
        val stackTransport = when (args.getOrElse(8) { "dictionary" }) {
            "dictionary" -> TierEStackTransport.DICTIONARY
            "inline-per-invocation" -> TierEStackTransport.INLINE_PER_INVOCATION
            else -> error("unknown Tier E stack transport: ${args[8]}")
        }
        val gate = Path.of("$definitions.gate")
        val oracleManifest = Path.of("$definitions.openat-fds")
        val lifecycleBarrier = Path.of("$definitions.lifecycle-barrier")
        val lifecycleReady = Path.of("$lifecycleBarrier.ready")
        val lifecycleRelease = Path.of("$lifecycleBarrier.release")
        Files.deleteIfExists(gate)
        Files.deleteIfExists(oracleManifest)
        Files.deleteIfExists(lifecycleReady)
        Files.deleteIfExists(lifecycleRelease)
        Files.deleteIfExists(definitions)
        captureYieldPath?.let(Files::deleteIfExists)
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val commonWorkload = args.getOrNull(9) == "common-file-workload"
        val writeOracleWorkload = args.getOrNull(9) == "write-oracle-workload"
        val sharedOpenatOracleWorkload = args.getOrNull(9) == "shared-openat-oracle-workload"
        val workloadClass = when {
            commonWorkload -> TierECommonFileWorkload::class.java.name
            writeOracleWorkload -> TierEWriteOracleWorkload::class.java.name
            sharedOpenatOracleWorkload -> TierESharedOpenatOracleWorkload::class.java.name
            else -> "AgentWorkload"
        }
        val workloadClasspath = if (commonWorkload || writeOracleWorkload || sharedOpenatOracleWorkload) System.getProperty("java.class.path") else args[2]
        val benchmark = args.getOrNull(10) == "benchmark"
        val nativePhaseTiming = System.getProperty("mazewall.tier-e.native-phase-timing", "true").toBooleanStrictOrNull()
            ?: error("native phase timing must be true or false")
        val expectRetentionExhaustion = System
            .getProperty(
            "mazewall.tier-e.expect-retention-exhaustion",
            "false",
        ).toBooleanStrictOrNull()
            ?: error("retention exhaustion expectation must be true or false")
        require(benchmark || !expectRetentionExhaustion) {
            "retention exhaustion expectation is benchmark-only"
        }
        val maxRetainedRecords = System
            .getProperty(
            "mazewall.tier-e.max-retained-records",
            TierEOptions.DEFAULT_RECORD_LIMIT.toString(),
        ).toInt()
            .also { limit ->
            require(limit > 0) { "max retained records must be positive" }
        }
        require(benchmark || maxRetainedRecords == TierEOptions.DEFAULT_RECORD_LIMIT) {
            "custom retained record limits are benchmark-only"
        }
        val benchmarkAllocationAccounting = System.getProperty("mazewall.tier-e.benchmark-allocation-accounting", "false").toBooleanStrictOrNull()
            ?: error("benchmark allocation accounting must be true or false")
        val benchmarkEmitTaskStorageUnavailableEvents = System
            .getProperty("mazewall.tier-e.benchmark-emit-task-storage-unavailable-events", "false")
            .toBooleanStrictOrNull()
            ?: error("task-storage unavailable event diagnostics must be true or false")
        val benchmarkEmitMarkerLossEvents = System
            .getProperty("mazewall.tier-e.benchmark-emit-marker-loss-events", "false")
            .toBooleanStrictOrNull()
            ?: error("marker-loss event diagnostics must be true or false")
        val benchmarkRawSysEnterAudit = System
            .getProperty("mazewall.tier-e.benchmark-raw-sys-enter-audit", "false")
            .toBooleanStrictOrNull()
            ?: error("raw sys_enter audit must be true or false")
        val uniqueEdgeObserverEnabled = System.getProperty("mazewall.tier-e.unique-edge-observer", "true").toBooleanStrictOrNull()
            ?: error("unique edge observer must be true or false")
        val idlePollBackoffNanos = System.getProperty("mazewall.tier-e.idle-poll-backoff-nanos", "0").toLong()
        val idlePollPolicy = System.getProperty("mazewall.tier-e.idle-poll-policy", "continuous")
        val maxIdlePollBackoffNanos = System.getProperty("mazewall.tier-e.idle-poll-max-backoff-nanos", "50000").toLong()
        val receiptClockMode = System.getProperty("mazewall.tier-e.collector-receipt-clock", "disabled")
        val ringSubmissionMode = when (System.getProperty("mazewall.tier-e.ring-submission", "enabled")) {
            "enabled" -> TierERingSubmissionMode.ENABLED
            "benchmark-disabled" -> TierERingSubmissionMode.BENCHMARK_DISABLED
            else -> error("unknown Tier E ring submission mode")
        }
        val ownedArgumentStorage = when (System.getProperty("mazewall.tier-e.owned-arguments", "owned")) {
            "owned" -> true
            "boxed-control" -> false
            else -> error("unknown Tier E owned argument storage mode")
        }
        val readyTransferMode = when (System.getProperty("mazewall.tier-e.ready-transfer", "read-only")) {
            "read-only" -> TierEReadyTransferMode.READ_ONLY
            "copy-control" -> TierEReadyTransferMode.COPY_CONTROL
            else -> error("unknown Tier E ready transfer mode")
        }
        val collectorResultMode = when (System.getProperty("mazewall.tier-e.collector-result", "append-target")) {
            "temporary-list" -> TierECollectorResultMode.TEMPORARY_LIST
            "append-target" -> TierECollectorResultMode.APPEND_TARGET
            else -> error("unknown Tier E collector result mode")
        }
        require(idlePollBackoffNanos in 0..1_000_000L) { "idle poll backoff must be between zero and one millisecond" }
        require(maxIdlePollBackoffNanos in idlePollBackoffNanos..1_000_000L) { "maximum idle poll backoff must cover the initial delay and be at most one millisecond" }
        require(idlePollPolicy in setOf("continuous", "fixed", "adaptive", "readiness")) { "unknown idle poll policy" }
        require(receiptClockMode in setOf("disabled", "monotonic")) { "unknown collector receipt clock" }
        require(benchmark || idlePollPolicy == "continuous") { "idle poll pacing is benchmark-only" }
        require(benchmark || receiptClockMode == "disabled") { "collector receipt clock is benchmark-only" }
        require(benchmark || !benchmarkAllocationAccounting) { "allocation accounting is benchmark-only" }
        require(benchmark || !(benchmarkEmitTaskStorageUnavailableEvents || benchmarkEmitMarkerLossEvents)) {
            "marker-loss event diagnostics are benchmark-only"
        }
        require(benchmark || !benchmarkRawSysEnterAudit) { "raw sys_enter audit is benchmark-only" }
        require(!benchmarkRawSysEnterAudit || sharedOpenatOracleWorkload) {
            "raw sys_enter audit requires the shared openat operation oracle"
        }
        require(benchmark || ringSubmissionMode == TierERingSubmissionMode.ENABLED) {
            "ring submission control is benchmark-only"
        }
        require(benchmark || ownedArgumentStorage) { "boxed argument storage is benchmark-only" }
        require(benchmark || readyTransferMode == TierEReadyTransferMode.READ_ONLY) {
            "copying ready transfer is benchmark-only"
        }
        require(benchmark || collectorResultMode == TierECollectorResultMode.APPEND_TARGET) {
            "temporary collector results are benchmark-only"
        }
        require(benchmark || uniqueEdgeObserverEnabled) { "unique-edge observer control is benchmark-only" }
        require(ringSubmissionMode == TierERingSubmissionMode.ENABLED || lifecycleScenario == "normal") {
            "ring submission control does not support lifecycle fixtures"
        }
        require(idlePollPolicy != "continuous" || idlePollBackoffNanos == 0L) { "continuous idle poll policy requires zero delay" }
        require(lifecycleScenario == "normal" || sharedOpenatOracleWorkload) {
            "$lifecycleScenario requires the shared-openat-oracle-workload"
        }
        val ringDecodeMode = when (System.getProperty("mazewall.tier-e.ring-decode", "legacy")) {
            "direct" -> TierERingDecodeMode.DIRECT
            "legacy" -> TierERingDecodeMode.LEGACY_BYTES
            else -> error("unknown Tier E ring decode mode")
        }
        val ringArgumentListMode = when (System.getProperty("mazewall.tier-e.ring-argument-list", "immutable-factory")) {
            "immutable-factory" -> TierERingArgumentListMode.IMMUTABLE_FACTORY
            "lambda-copy" -> TierERingArgumentListMode.LAMBDA_COPY
            else -> error("unknown Tier E ring argument list mode")
        }
        val ringArgumentViewMode = when (System.getProperty("mazewall.tier-e.ring-argument-view", "lazy")) {
            "lazy" -> TierERingArgumentViewMode.LAZY
            "eager-control" -> TierERingArgumentViewMode.EAGER_CONTROL
            else -> error("unknown Tier E ring argument view mode")
        }
        val taskIdentityMode = when (System.getProperty("mazewall.tier-e.task-identity", "reuse-consecutive")) {
            "per-event" -> TierETaskIdentityMode.PER_EVENT
            "reuse-consecutive" -> TierETaskIdentityMode.REUSE_CONSECUTIVE
            else -> error("unknown Tier E task identity mode")
        }
        val knownJoinMode = when (System.getProperty("mazewall.tier-e.known-join", "queued-control")) {
            "queued-control" -> TierEKnownJoinMode.QUEUED_CONTROL
            "direct-known" -> TierEKnownJoinMode.DIRECT_KNOWN
            else -> error("unknown Tier E known join mode")
        }
        require(benchmark || knownJoinMode == TierEKnownJoinMode.QUEUED_CONTROL) {
            "direct known joins are benchmark-only"
        }
        require(benchmark || ringArgumentViewMode == TierERingArgumentViewMode.LAZY) {
            "eager ring argument views are benchmark-only"
        }
        val captureProfile = TierECaptureProfile.fromProperty(System.getProperty("mazewall.tier-e.capture-profile", "full"))
        val benchmarkOptions = if (benchmark) {
            (if (nativePhaseTiming) ",benchmark-phases" else "") +
                (if (System.getProperty("mazewall.tier-e.canonical-cache") == "disabled") ",canonical-cache=disabled" else "") +
                (if (System.getProperty("mazewall.tier-e.live-publication") == "true" || lifecycleScenario in setOf("terminal_while_active", "exec_transition")) ",live-definitions" else "") +
                captureProfileOption(captureProfile)
        } else {
            ""
        }
        val captureYieldOption = captureYieldPath?.let { ",capture-yield=$it" } ?: ""
        val lifecycleBarrierOption = if (lifecycleScenario in setOf("detach_while_active", "terminal_while_active", "exec_transition")) {
            ",lifecycle-barrier=$lifecycleBarrier,lifecycle-gate=$gate"
        } else {
            ""
        }
        val callbackSuppressionOption = if (lifecycleScenario == "callback_suppression") {
            ",callback-suppression-probe,callback-suppression-gate=$gate"
        } else {
            ""
        }
        val captureFailureOption = if (lifecycleScenario == "capture_failure") {
            ",force-capture-failure-once,capture-failure-gate=$gate"
        } else {
            ""
        }
        val execTransitionOption = if (lifecycleScenario == "exec_transition") ",force-exec-once" else ""
        val command = mutableListOf(
            java,
            "-agentpath:$agent=definitions=$definitions,session=${sessionTag.toString(
                16,
            )},mode=${args.getOrElse(
                7,
            ) { "full-stream" }},stack-transport=${args.getOrElse(
                8,
            ) { "dictionary" }}$benchmarkOptions$captureYieldOption$lifecycleBarrierOption$callbackSuppressionOption$captureFailureOption$execTransitionOption",
            "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED",
            "-D${TierESharedOpenatOracleWorkload.PROFILE_PROPERTY}=${TierESharedOpenatOracleWorkload.Profile.fromSystemProperty().wireName}",
            "-D${TierECommonFileWorkload.APPLICATION_KIND_PROPERTY}=${System.getProperty(
                TierECommonFileWorkload.APPLICATION_KIND_PROPERTY,
                TierECommonFileWorkload.ApplicationKind.BUFFERED_FILE_IO.wireName,
            )}",
            "-D${TierESharedOpenatOracleWorkload.RATE_PER_SECOND_PROPERTY}=${System.getProperty(TierESharedOpenatOracleWorkload.RATE_PER_SECOND_PROPERTY, "0")}",
            "-D${TierESharedOpenatOracleWorkload.MANIFEST_PROPERTY}=$oracleManifest",
            "-cp",
            workloadClasspath,
            workloadClass,
        ).apply {
            if (benchmark) {
                addAll(listOf("benchmark", "gate:$gate", args.getOrElse(4) { "platform" }, args.getOrElse(11) { "3" }, args.getOrElse(12) { "10" }, args.getOrElse(5) { "1" }))
            } else {
                addAll(listOf("gate:$gate", args.getOrElse(4) { "platform" }, args.getOrElse(5) { "1" }))
            }
        }
        val child = ManagedProcessCapture.start(ProcessBuilder(command).inheritIO())
        val process = child.process
        val processEpoch = runCatching { processEpoch(process.pid()) }
            .getOrElse { failure ->
                error("target exited before process identity could be admitted: ${failure.message}")
            }
        val uniqueEdgeDecisions = if (uniqueEdgeObserverEnabled) mutableListOf<UniqueEdgeDecision>() else null
        val uniqueEdgeDecisionObserver: ((UniqueEdgeDecision) -> Unit)? = uniqueEdgeDecisions?.let { decisions ->
            { decision: UniqueEdgeDecision -> decisions.add(decision) }
        }
        val engine = TierEbpfEngine(RealNativeEngine)
        val session = TierEProfilerSession(
            engine,
            processEpoch,
            TierEOptions(
                emissionMode = emissionMode,
                stackTransport = stackTransport,
                captureProfile = captureProfile,
                maxPendingRecords = maxRetainedRecords,
                maxRetainedRecords = maxRetainedRecords,
                benchmarkPhases = benchmark,
                benchmarkAllocationAccounting = benchmarkAllocationAccounting,
                benchmarkEmitTaskStorageUnavailableEvents = benchmarkEmitTaskStorageUnavailableEvents,
                benchmarkEmitMarkerLossEvents = benchmarkEmitMarkerLossEvents,
                benchmarkRawSysEnterAudit = benchmarkRawSysEnterAudit,
                ringDecodeMode = ringDecodeMode,
                ringArgumentListMode = ringArgumentListMode,
                ringArgumentViewMode = ringArgumentViewMode,
                taskIdentityMode = taskIdentityMode,
                knownJoinMode = knownJoinMode,
                ringSubmissionMode = ringSubmissionMode,
                ownedArgumentStorage = ownedArgumentStorage,
                readyTransferMode = readyTransferMode,
                collectorResultMode = collectorResultMode,
            ),
            uniqueEdgeDecisionObserver = uniqueEdgeDecisionObserver,
        )
        val receiptClock = if (receiptClockMode == "monotonic") TierELinuxMonotonicClock() else null
        val collectorLoop = TierECollectorLoop(
            session = session,
            policy = when (idlePollPolicy) {
                "continuous" -> TierECollectorLoopPolicy.CONTINUOUS
                "fixed" -> TierECollectorLoopPolicy(idlePollBackoffNanos, idlePollBackoffNanos, multiplier = 1)
                "adaptive" -> TierECollectorLoopPolicy.boundedAdaptive(idlePollBackoffNanos, maxIdlePollBackoffNanos)
                "readiness" -> TierECollectorLoopPolicy.READINESS_DRIVEN
                else -> error("unreachable idle poll policy")
            },
            receiptClock = receiptClock,
        )
        var oracleRoot: Path? = null
        var lifecycleFaultApplied = false
        var kernelActiveBeforeDetach: Long? = null
        var terminalProfile: TierEProfile? = null
        var terminalLastMarkerPidTgid: Long? = null
        var strace: Process? = null
        val straceMessages = LinkedBlockingQueue<String>()
        try {
            val expectedOpenatFds = if (sharedOpenatOracleWorkload) {
                while (!Files.exists(oracleManifest) || Files.size(oracleManifest) == 0L) Thread.sleep(1)
                val manifest = Files.readAllLines(oracleManifest)
                oracleRoot = manifest.first { it.startsWith("root=") }.removePrefix("root=").let(Path::of)
                manifest.filter { it.startsWith("fd=") }.map { it.removePrefix("fd=").toInt() }.toSet()
            } else {
                emptySet()
            }
            val attachStarted = System.nanoTime()
            session.installAndAttach(process.pid().toInt(), sessionTag, agent, markerOffset, usdtNote)
            val attachNanos = System.nanoTime() - attachStarted
            if (benchmarkRawSysEnterAudit) engine.configureRawSysEnterAudit(expectedOpenatFds)
            straceOutputPath?.let { output ->
                output.parent?.let(Files::createDirectories)
                Files.deleteIfExists(output)
                val tracer = ProcessBuilder(
                    "strace",
                    "-f",
                    "-e",
                    "trace=openat,openat2,close",
                    "-o",
                    output.toString(),
                    "-p",
                    process.pid().toString(),
                ).start()
                strace = tracer
                Thread.startVirtualThread {
                    tracer.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach(straceMessages::offer)
                    }
                }
                val attachmentDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                var attached = false
                while (!attached && System.nanoTime() < attachmentDeadline) {
                    val message = straceMessages.poll(25, TimeUnit.MILLISECONDS) ?: continue
                    attached = message.contains("Process ${process.pid()} attached")
                }
                check(attached && tracer.isAlive) {
                    "strace shadow oracle exited before workload release"
                }
            }
            // Benchmark-only synchronization point for concurrent host-noise fixtures. The marker
            // proves attachment completed; it does not imply producer closure or collector drain.
            println("tier-e-kernel-smoke: attachment_ready=true workload_pid=${process.pid()}")
            System
                .getProperty("mazewall.tier-e.attachment-ready-file")
                ?.takeIf { it.isNotBlank() }
                ?.let { path ->
                    Files.writeString(Path.of(path), "workload_pid=${process.pid()}\n")
                }
            args.getOrNull(6)?.toInt()?.let { cpu ->
                pinTargetToCpu(cpu, process.pid())
            }
            val workloadStarted = System.nanoTime()
            Files.writeString(gate, "go")
            while (process.isAlive) {
                if (terminalProfile == null) {
                    collectorLoop.pollOnce()
                }
                if (lifecycleScenario == "detach_while_active" && !lifecycleFaultApplied && Files.exists(lifecycleReady)) {
                    session.detachMarker()
                    collectorLoop.pollOnce()
                    kernelActiveBeforeDetach = session.kernelActiveObservationCount()
                    Files.writeString(lifecycleRelease, "release\n")
                    lifecycleFaultApplied = true
                }
                if (lifecycleScenario == "terminal_while_active" && !lifecycleFaultApplied && Files.exists(lifecycleReady)) {
                    val snapshotDeadline = System.nanoTime() + 10_000_000_000L
                    while ((!Files.exists(definitions) || Files.size(definitions) == 0L) && System.nanoTime() < snapshotDeadline) {
                        Thread.sleep(10)
                    }
                    check(Files.exists(definitions) && Files.size(definitions) > 0L) {
                        "terminal scenario did not receive a live definition snapshot"
                    }
                    collectorLoop.pollOnce()
                    terminalLastMarkerPidTgid = session.lastMarkerPidTgid()
                    terminalProfile = session.finish(definitions, producerClosed = false)
                    Files.writeString(lifecycleRelease, "release\n")
                    lifecycleFaultApplied = true
                }
                if (lifecycleScenario == "exec_transition" && !lifecycleFaultApplied && Files.exists(lifecycleReady)) {
                    val snapshotDeadline = System.nanoTime() + 10_000_000_000L
                    while ((!Files.exists(definitions) || Files.size(definitions) == 0L) && System.nanoTime() < snapshotDeadline) {
                        Thread.sleep(10)
                    }
                    check(Files.exists(definitions) && Files.size(definitions) > 0L) {
                        "exec scenario did not receive a live definition snapshot"
                    }
                    collectorLoop.pollOnce()
                    terminalLastMarkerPidTgid = session.lastMarkerPidTgid()
                    Files.writeString(lifecycleRelease, "release\n")
                    lifecycleFaultApplied = true
                }
                if (!benchmark) {
                    Thread.sleep(5)
                }
            }
            check(process.waitFor() == 0) { "agent workload failed" }
            val rawSysEnterAuditCounts = if (benchmarkRawSysEnterAudit) {
                engine.readRawSysEnterAuditCounts(expectedOpenatFds)
            } else {
                emptyMap()
            }
            val rawSysEnterAuditMissing = rawSysEnterAuditCounts.filterValues { count -> count.total != 1L }.keys
            val rawSysEnterAuditObserved = rawSysEnterAuditCounts.values.sumOf { count -> count.total }
            val rawSysEnterAuditNonSingletons = rawSysEnterAuditCounts.filterValues { count -> count.total != 1L }
            val rawSysEnterAuditOpenat2 = rawSysEnterAuditCounts.filterValues { count -> count.openat2 > 0L }
            val captureYield = captureYieldPath
                ?.takeIf(Files::exists)
                ?.let(TierECaptureYield::read)
            val terminalStarted = System.nanoTime()
            val lastMarkerPidTgid = terminalLastMarkerPidTgid ?: session.lastMarkerPidTgid()
            // The child has exited successfully, providing the producer-quiescence proof that
            // finish deliberately refuses to infer from an empty final ring drain.
            val producerClosed = terminalProfile == null && lifecycleScenario != "exec_transition"
            val profile = terminalProfile ?: session.finish(definitions, producerClosed = producerClosed)
            val resolvedInvocationIds = profile.syscalls
                .filter { syscall -> syscall.resolutionStatus == ResolutionStatus.RESOLVED }
                .mapNotNull { syscall -> syscall.observation.invocationId }
                .toSet()
            val captureYieldJoin = captureYield?.let {
                definitions
                    .takeIf(Files::exists)
                    ?.let { definitionFile ->
                        TierECaptureYield.join(
                            it,
                            AgentDefinitionFile.readForInvocations(definitionFile, resolvedInvocationIds).invocations,
                            resolvedInvocationIds,
                        )
                    }
            }
            val terminalNanos = System.nanoTime() - terminalStarted
            val workloadNanos = System.nanoTime() - workloadStarted
            val collectorPhases = session.benchmarkPhaseMetrics()
            val collectorLoopMetrics = collectorLoop.metrics()
            val resolved = profile.syscalls.count { it.resolutionStatus == ResolutionStatus.RESOLVED }
            check(profile.syscalls.all { it.observation.task.tid > 0 }) { "kernel emitted an invalid task identity" }
            val workloadClassName = when {
                commonWorkload -> TierECommonFileWorkload::class.java.name
                writeOracleWorkload -> TierEWriteOracleWorkload::class.java.name
                sharedOpenatOracleWorkload -> TierESharedOpenatOracleWorkload::class.java.name
                else -> "AgentWorkload"
            }
            val wrong = profile.syscalls.count { syscall ->
                syscall.resolutionStatus == ResolutionStatus.RESOLVED &&
                    syscall.stackDefinition?.frames?.none { frame -> frame.className == workloadClassName } != false
            }
            val wronglyAttributedUnlinks = profile.syscalls.count { syscall ->
                syscall.resolutionStatus == ResolutionStatus.RESOLVED &&
                    syscall.observation.syscallNumber == SYS_UNLINK &&
                    syscall.stackDefinition?.frames?.none { frame ->
                        if (commonWorkload) {
                            frame.className == TierECommonFileWorkload::class.java.name && frame.methodName == "run"
                        } else {
                            frame.className == "AgentWorkload" && frame.methodName == "deleteWorkload"
                        }
                    } != false
            }
            val unknown = profile.syscalls.count { it.resolutionStatus == ResolutionStatus.NO_ACTIVE_INVOCATION }
            val unexplainedUnknown = profile.syscalls.count {
                it.resolutionStatus != ResolutionStatus.RESOLVED &&
                    it.resolutionStatus != ResolutionStatus.NO_ACTIVE_INVOCATION
            }
            val workloadResolved = profile.syscalls.count { syscall ->
                syscall.resolutionStatus == ResolutionStatus.RESOLVED &&
                    syscall.stackDefinition?.frames?.any { frame -> frame.className == workloadClassName } == true
            }
            val workloadUnlinksResolved = profile.syscalls.count { syscall ->
                syscall.resolutionStatus == ResolutionStatus.RESOLVED &&
                    syscall.observation.syscallNumber == SYS_UNLINK &&
                    syscall.stackDefinition?.frames?.any { frame ->
                        if (commonWorkload) {
                            frame.className == TierECommonFileWorkload::class.java.name && frame.methodName == "run"
                        } else {
                            frame.className == "AgentWorkload" && frame.methodName == "deleteWorkload"
                        }
                    } == true
            }
            val oracleEnabled = writeOracleWorkload || sharedOpenatOracleWorkload
            val manifestCandidateCounts = if (sharedOpenatOracleWorkload) {
                profile.syscalls
                    .filter { it.observation.syscallNumber == SYS_OPENAT && it.observation.args[0].toInt() in expectedOpenatFds }
                    .groupingBy { it.observation.args[0].toInt() }
                    .eachCount()
            } else {
                emptyMap()
            }
            val operationOracleAvailable = oracleEnabled &&
                (emissionMode != TierEEmissionMode.UNIQUE_STACK_SYSCALL || uniqueEdgeObserverEnabled)
            val oracle = if (operationOracleAvailable) {
                val expected = if (writeOracleWorkload) {
                    TierEWriteOracleWorkload.expectedOperationIds(
                    warmups = args.getOrElse(11) { "3" }.toInt(),
                    batches = args.getOrElse(12) { "10" }.toInt(),
                    iterations = args.getOrElse(5) { "1" }.toInt(),
                )
                } else {
                    expectedOpenatFds
                }
                val argumentIndex = if (writeOracleWorkload) 2 else 0
                val syscallNumber = if (writeOracleWorkload) SYS_WRITE else SYS_OPENAT
                if (emissionMode == TierEEmissionMode.UNIQUE_STACK_SYSCALL) {
                    TierEOperationOracle.evaluateUnique(
                        expected,
                        requireNotNull(uniqueEdgeDecisions)
                            .filter { decision ->
                                decision.syscall.observation.syscallNumber == syscallNumber &&
                                    decision.syscall.observation.args[argumentIndex]
                                        .toInt() in expected
                            }.map { decision ->
                                TierEOperationOracle.UniqueObservation(
                                    operationId = decision.syscall.observation.args[argumentIndex]
                                        .toInt(),
                                    decision = when (decision.kind) {
                                        UniqueEdgeDecision.Kind.CANDIDATE -> TierEOperationOracle.UniqueDecision.CANDIDATE
                                        UniqueEdgeDecision.Kind.ACKNOWLEDGED -> TierEOperationOracle.UniqueDecision.ACKNOWLEDGED
                                        UniqueEdgeDecision.Kind.SUPPRESSED -> TierEOperationOracle.UniqueDecision.SUPPRESSED
                                    },
                                    resolved = decision.syscall.resolutionStatus == ResolutionStatus.RESOLVED,
                                    hasWorkloadStack = decision.syscall.stackDefinition
                                        ?.frames
                                        ?.any { it.className == workloadClassName } == true,
                                    hasInvocationIdentity = decision.syscall.observation.invocationId != null,
                                    hasExecutionIdentity = decision.syscall.executionContext != null,
                                )
                            },
                        profile.syscalls
                            .filter { syscall ->
                                syscall.observation.syscallNumber == syscallNumber &&
                                    syscall.observation.args[argumentIndex].toInt() in expected &&
                                    syscall.resolutionStatus != ResolutionStatus.RESOLVED
                            }.mapTo(mutableSetOf()) { syscall ->
                                syscall.observation.args[argumentIndex].toInt()
                            },
                    )
                } else {
                    TierEOperationOracle.evaluate(
                    expected,
                    profile.syscalls
                        .filter {
                            it.observation.syscallNumber == syscallNumber && it.observation.args[argumentIndex].toInt() in expected
                        }.map { syscall ->
                            TierEOperationOracle.Observation(
                                operationId = syscall.observation.args[argumentIndex].toInt(),
                                resolved = syscall.resolutionStatus == ResolutionStatus.RESOLVED,
                                hasWorkloadStack = syscall.stackDefinition?.frames?.any { it.className == workloadClassName } == true,
                                hasInvocationIdentity = syscall.observation.invocationId != null,
                                hasExecutionIdentity = syscall.executionContext != null,
                                markerLossReason = markerLossReason(syscall.observation.captureFlags),
                            )
                        },
                )
                }
            } else {
                TierEOperationOracle.Report(0, 0, 0, 0, 0)
            }
            if (ringSubmissionMode == TierERingSubmissionMode.BENCHMARK_DISABLED) {
                check(profile.syscalls.isEmpty() && resolved == 0) {
                    "ring submission control unexpectedly retained syscall evidence: ${profile.syscalls.size}"
                }
                check(oracleEnabled && oracle.expected > 0L && oracle.matched == 0L) {
                    "ring submission control did not retain the expected absent-operation control: $oracle"
                }
            }
            // The shared workload declares one delete per batch. This verifies a stable
            // operation count, but path pointers are not copied from the ring record, so it
            // cannot prove a per-operation identity mapping. Keep this explicitly unverified.
            val commonWorkloadRequiresUnlink =
                commonWorkload && System.getProperty(TierECommonFileWorkload.APPLICATION_KIND_PROPERTY) != "loopback_socket"
            val expectedWorkloadUnlinks: Long = if (operationOracleAvailable) {
                oracle.expected
            } else if (commonWorkloadRequiresUnlink && benchmark) {
                args.getOrElse(11) { "3" }.toLong() + args.getOrElse(12) { "10" }.toLong()
            } else if (commonWorkloadRequiresUnlink) {
                1L
            } else {
                0L
            }
            val matchedOperations: Long = if (operationOracleAvailable) oracle.matched else workloadUnlinksResolved.toLong()
            val missingWorkloadUnlinks = if (operationOracleAvailable) oracle.missing else (expectedWorkloadUnlinks - matchedOperations).coerceAtLeast(0L)
            val extraWorkloadUnlinks = (matchedOperations - expectedWorkloadUnlinks).coerceAtLeast(0L)
            val uniqueRepresentativeOracle = operationOracleAvailable && emissionMode == TierEEmissionMode.UNIQUE_STACK_SYSCALL
            val suppressedRepresentatives = if (uniqueRepresentativeOracle) oracle.suppressed else 0L
            val callbackProbeResolved = profile.syscalls.count { syscall ->
                syscall.resolutionStatus == ResolutionStatus.RESOLVED &&
                    syscall.stackDefinition?.frames?.any { frame ->
                        frame.className == TierESharedOpenatOracleWorkload::class.java.name &&
                            frame.methodName == "callbackSuppressionProbe"
                    } == true
            }
            val operationOracleExpectation = if (lifecycleScenario == "detach_while_active") {
                "detached_inactive"
            } else if (lifecycleScenario == "terminal_while_active") {
                "terminal_incomplete"
            } else if (lifecycleScenario == "callback_suppression") {
                "callback_suppressed_outer"
            } else if (lifecycleScenario == "capture_failure") {
                "capture_failure_unknown"
            } else if (uniqueRepresentativeOracle) {
                "unique_representative"
            } else {
                "full_exact"
            }
            val semanticExpectedOperations = when {
                writeOracleWorkload -> TierEWriteOracleWorkload.expectedOperationIds(
                    warmups = args.getOrElse(11) { "3" }.toInt(),
                    batches = args.getOrElse(12) { "10" }.toInt(),
                    iterations = args.getOrElse(5) { "1" }.toInt(),
                )
                sharedOpenatOracleWorkload -> expectedOpenatFds
                else -> emptySet()
            }
            val semanticSyscallNumber = if (writeOracleWorkload) SYS_WRITE else SYS_OPENAT
            val semanticArgumentIndex = if (writeOracleWorkload) 2 else 0
            val semanticRecords =
                if (commonWorkload) {
                    val semanticBindings = semanticBindings(TierECommonFileWorkload.ApplicationKind.fromSystemProperty())
                    profile.syscalls.filter { syscall ->
                        syscall.resolutionStatus == ResolutionStatus.RESOLVED &&
                            syscall.stackDefinition?.frames?.any { frame ->
                                semanticBindings.any { binding ->
                                    binding.matchesFrame(frame.className, frame.methodName, frame.descriptor)
                                }
                            } == true
                    }
                } else {
                    profile.syscalls.filter { syscall ->
                        syscall.resolutionStatus == ResolutionStatus.RESOLVED &&
                            syscall.observation.syscallNumber == semanticSyscallNumber &&
                            syscall.observation.args[semanticArgumentIndex].toInt() in semanticExpectedOperations
                    }
                }
            val semanticStackDigest = semanticStackDigest(semanticRecords)
            val semanticStackComplete =
                if (commonWorkload) {
                    semanticRecords.isNotEmpty() && semanticStackDigest != null
                } else {
                    operationOracleAvailable &&
                        oracle.matched == oracle.expected &&
                        oracle.duplicates == 0L &&
                        oracle.wrong == 0L &&
                        oracle.unclassified == 0L &&
                        semanticRecords.size.toLong() == oracle.expected &&
                        semanticStackDigest != null
                }
            val operationOracleComplete = !operationOracleAvailable ||
                (
                    oracle.matched == oracle.expected &&
                        oracle.missing == 0L &&
                        oracle.unresolved == 0L &&
                        oracle.duplicates == 0L &&
                        oracle.wrong == 0L &&
                        oracle.unclassified == 0L
                    )
            val effectiveEvidenceComplete = profile.integrity.coverageComplete && operationOracleComplete
            println(
                "tier-e-kernel-smoke: marker_transport=$markerTransport marker_requested=${requestedMarkerTransport.name.lowercase()} " +
                    "marker_fallback=${markerSelection.fallbackReason ?: "none"} markerTransitions=${profile.integrity.reconciliation.markerTransitions} " +
                    "strace_shadow_oracle=${if (straceOutputPath == null) "disabled" else "enabled"} " +
                    "strace_output=${straceOutputPath ?: "none"} " +
                "raw_sys_enter_audit=${if (benchmarkRawSysEnterAudit) "enabled" else "disabled"} " +
                "raw_sys_enter_audit_expected_operations=${rawSysEnterAuditCounts.size} " +
                "raw_sys_enter_audit_observed_operations=$rawSysEnterAuditObserved " +
                "raw_sys_enter_audit_non_singleton_operations=${rawSysEnterAuditNonSingletons.size} " +
                "raw_sys_enter_audit_openat2_operations=${rawSysEnterAuditOpenat2.size} " +
                "raw_sys_enter_audit_missing_fd_samples=${rawSysEnterAuditMissing.sorted().take(16).joinToString(",").ifEmpty { "none" }} " +
                "raw_sys_enter_audit_non_singleton_samples=${rawSysEnterAuditNonSingletons.toSortedMap().entries.take(
                    16,
                ).joinToString(",") { (fd, count) -> "$fd:${count.openat}/${count.openat2}" }.ifEmpty { "none" }} " +
                "raw_sys_enter_audit_openat2_samples=${rawSysEnterAuditOpenat2.toSortedMap().entries.take(
                    16,
                ).joinToString(",") { (fd, count) -> "$fd:${count.openat}/${count.openat2}" }.ifEmpty { "none" }} " +
                "collector_pid=${ProcessHandle.current().pid()} workload_pid=${process.pid()} " +
                "benchmark_ring_submission=${if (ringSubmissionMode == TierERingSubmissionMode.ENABLED) "enabled" else "disabled"} " +
                "configured_ring_capacity_bytes=${engine.configuredRingCapacityBytes()} " +
                "configured_max_retained_records=$maxRetainedRecords " +
                "benchmark_control_evidence=${if (ringSubmissionMode == TierERingSubmissionMode.ENABLED) "none" else "EXPECTED_INCOMPLETE"} " +
                "lastMarkerPidTgid=${lastMarkerPidTgid.toULong().toString(16)} " +
                "observations=${profile.syscalls.size} resolved=$resolved workload_resolved=$workloadResolved workloadFrames=${workloadResolved > 0} " +
                "workload_unlinks_resolved=$workloadUnlinksResolved unknown_no_active=$unknown " +
                "ambient_inactive=${profile.integrity.ambientInactiveObservations} " +
                "reconciliation_admitted_invocations=${profile.integrity.reconciliation.admittedInvocations} " +
                "reconciliation_exact_invocations=${profile.integrity.reconciliation.exactInvocations} " +
                "reconciliation_failed_invocations=${profile.integrity.reconciliation.failedInvocations} " +
                "reconciliation_kernel_active_observations=${profile.integrity.reconciliation.kernelActiveObservations} " +
                "kernel_active_before_detach=${kernelActiveBeforeDetach ?: -1} " +
                "unknown_other=$unexplainedUnknown wrong=$wrong lost=${profile.integrity.observedLosses} " +
                "kernel_ring_loss=${collectorPhases.kernelRingLosses} retained_result_drops=${collectorPhases.retainedResultDrops} " +
                "pending_observation_budget_drops=${collectorPhases.pendingObservationBudgetDrops} " +
                "pending_observation_queue_drops=${collectorPhases.pendingObservationQueueDrops} " +
                "kernel_exact_admissions=${collectorPhases.kernelExactAdmissions} " +
                "kernel_marker_detached=${collectorPhases.kernelMarkerDetached} " +
                "kernel_task_storage_unavailable=${collectorPhases.kernelTaskStorageUnavailable} " +
                "kernel_stale_marker_generation=${collectorPhases.kernelStaleMarkerGeneration} " +
                "failures=${profile.integrity.attributionFailures} evidence_complete=$effectiveEvidenceComplete " +
                "producer_closed=$producerClosed " +
                "expected_operations=$expectedWorkloadUnlinks matched_operations=$matchedOperations " +
                "missing_operations=$missingWorkloadUnlinks extra_operations=$extraWorkloadUnlinks " +
                "missing_operation_id_samples=${oracle.missingOperationIdSamples.joinToString(",").ifEmpty { "none" }} " +
                "wrong_operation_id_samples=${oracle.wrongOperationIdSamples.joinToString(",").ifEmpty { "none" }} " +
                "marker_loss_reasons=${oracle.markerLossReasons.entries.joinToString(",") { (reason, count) -> "${reason.name.lowercase()}:$count" }.ifEmpty { "none" }} " +
                "candidate_operations=${oracle.candidates} acknowledged_operations=${oracle.acknowledged} " +
                "suppressed_representatives=$suppressedRepresentatives " +
                "callback_probe_resolved=$callbackProbeResolved " +
                "unresolved_operations=${oracle.unresolved} " +
                "duplicate_operations=${oracle.duplicates} wrong_operations=${oracle.wrong} " +
                "unclassified_operations=${oracle.unclassified} operation_oracle_available=$operationOracleAvailable " +
                "operation_oracle_complete=$operationOracleComplete " +
                "unique_edge_observer_enabled=$uniqueEdgeObserverEnabled " +
                "operation_oracle_expectation=$operationOracleExpectation " +
                "profile_semantic_stack_complete=$semanticStackComplete " +
                "profile_semantic_stack_records=${semanticRecords.size} " +
                "profile_semantic_stack_digest=${semanticStackDigest ?: "unavailable"} " +
                "capture_yield_join_available=${captureYieldJoin != null} " +
                "capture_yield_records=${captureYield?.records?.size ?: 0} " +
                "capture_yield_captured=${captureYield?.captured ?: 0} " +
                "capture_yield_capture_failed=${captureYield?.captureFailed ?: 0} " +
                "capture_yield_admission_failed=${captureYield?.admissionFailed ?: 0} " +
                "capture_yield_excluded=${captureYield?.excluded ?: 0} " +
                "capture_yield_exact_joined=${captureYieldJoin?.exactJoined ?: 0} " +
                "capture_yield_missing_definition=${captureYieldJoin?.missingDefinition ?: 0} " +
                "capture_yield_outcome_mismatch=${captureYieldJoin?.outcomeMismatch ?: 0} " +
                "capture_yield_unrecorded_definitions=${captureYieldJoin?.unrecordedDefinitions ?: 0} " +
                "capture_yield_bindings=${captureYieldJoin?.bindings.orEmpty().toSortedMap().entries.joinToString(",") { (binding, yield) ->
                    "$binding:${yield.records}:${yield.exactJoined}:${yield.missingDefinition}:${yield.outcomeMismatch}:" +
                        "${yield.resolvedInvocations}:${yield.resolvedSyscalls}:${yield.capturedWithoutResolvedSyscall}"
                }} " +
                "ring_drain_ns=${collectorPhases.ringDrainNanos} ring_scan_ns=${collectorPhases.ringScanNanos} " +
                    "ring_payload_slice_ns=${collectorPhases.ringPayloadSliceNanos} ring_decode_ns=${collectorPhases.ringDecodeNanos} ring_append_ns=${collectorPhases.ringAppendNanos} " +
                    "ring_consumer_publish_ns=${collectorPhases.ringConsumerPublishNanos} " +
                    "ring_scanned_records=${collectorPhases.ringScannedRecords} ring_ready_records=${collectorPhases.ringReadyRecords} " +
                    "ring_batches=${collectorPhases.ringBatches} ring_max_batch_records=${collectorPhases.ringMaxBatchRecords} " +
                    "records_per_drain_p50_upper=${collectorPhases.ringDrainP50UpperRecords} " +
                    "records_per_drain_p95_upper=${collectorPhases.ringDrainP95UpperRecords} " +
                    "records_per_drain_p99_upper=${collectorPhases.ringDrainP99UpperRecords} " +
                    "ring_reader_initialization_ns=${collectorPhases.ringReaderInitializationNanos} " +
                    "collector_join_ns=${collectorPhases.collectorJoinNanos} " +
                    "allocation_measurement_available=${if (collectorPhases.allocationMeasurementAvailable) 1 else 0} " +
                    "ring_drain_allocated_bytes=${collectorPhases.ringDrainAllocatedBytes} " +
                    "collector_join_allocated_bytes=${collectorPhases.collectorJoinAllocatedBytes} " +
                    "observation_conversion_allocated_bytes=${collectorPhases.observationConversionAllocatedBytes} " +
                    "collector_resolution_allocated_bytes=${collectorPhases.collectorResolutionAllocatedBytes} " +
                    "resolved_evidence_allocated_bytes=${collectorPhases.resolvedEvidenceAllocatedBytes} " +
                    "joiner_enqueue_allocated_bytes=${collectorPhases.joinerEnqueueAllocatedBytes} " +
                    "joiner_take_ready_allocated_bytes=${collectorPhases.joinerTakeReadyAllocatedBytes} " +
                    "unique_edge_gate_allocated_bytes=${collectorPhases.uniqueEdgeGateAllocatedBytes} " +
                    "unique_edge_suppression_diagnostic_allocated_bytes=${collectorPhases.uniqueEdgeSuppressionDiagnosticAllocatedBytes} " +
                    "unique_edge_candidate_ownership_allocated_bytes=${collectorPhases.uniqueEdgeCandidateOwnershipAllocatedBytes} " +
                    "unique_edge_candidate_diagnostic_allocated_bytes=${collectorPhases.uniqueEdgeCandidateDiagnosticAllocatedBytes} " +
                    "batch_freeze_allocated_bytes=${collectorPhases.batchFreezeAllocatedBytes} " +
                "collector_polls=${collectorPhases.polls} " +
                "collector_loop_active_polls=${collectorLoopMetrics.activePolls} " +
                "collector_loop_idle_polls=${collectorLoopMetrics.idlePolls} " +
                "collector_loop_parks=${collectorLoopMetrics.parks} " +
                "collector_loop_parked_ns=${collectorLoopMetrics.parkedNanos} " +
                "collector_loop_max_consecutive_idle_polls=${collectorLoopMetrics.maxConsecutiveIdlePolls} " +
                "collector_readiness_waits=${collectorLoopMetrics.readinessWaits} " +
                "collector_readiness_wakeups=${collectorLoopMetrics.readinessWakeups} " +
                "collector_readiness_timeouts=${collectorLoopMetrics.readinessTimeouts} " +
                "collector_readiness_spurious_wakeups=${collectorLoopMetrics.readinessSpuriousWakeups} " +
                "collector_readiness_waited_ns=${collectorLoopMetrics.readinessWaitedNanos} " +
                "collector_receipt_samples=${collectorLoopMetrics.receiptAge.samples} " +
                "collector_receipt_min_ns=${collectorLoopMetrics.receiptAge.minNanos} " +
                "collector_receipt_mean_ns=${collectorLoopMetrics.receiptAge.meanNanos} " +
                "collector_receipt_max_ns=${collectorLoopMetrics.receiptAge.maxNanos} " +
                "collector_receipt_p50_upper_ns=${collectorLoopMetrics.receiptAge.p50UpperBoundNanos} " +
                "collector_receipt_p95_upper_ns=${collectorLoopMetrics.receiptAge.p95UpperBoundNanos} " +
                "collector_receipt_p99_upper_ns=${collectorLoopMetrics.receiptAge.p99UpperBoundNanos} " +
                "openat_manifest_non_singletons=${manifestCandidateCounts.filterValues { it != 1 }.entries.take(16).joinToString(",") { "${it.key}:${it.value}" }} " +
                "attach_ns=$attachNanos terminal_ns=$terminalNanos workloadNs=$workloadNanos",
            )
            if (lifecycleScenario == "detach_while_active") {
                check(lifecycleFaultApplied) { "native lifecycle barrier was never observed" }
                check(profile.integrity.lifecycleInvalidations > 0L) {
                    "detach scenario did not invalidate the active lifecycle: ${profile.integrity}"
                }
                check(profile.integrity.ambientInactiveObservations > 0L) {
                    "detach scenario produced no explicit inactive observations: ${profile.integrity}"
                }
                check(profile.integrity.reconciliation.admittedInvocations > 0L) {
                    "detach scenario did not admit a native invocation before detachment: ${profile.integrity}"
                }
                val activeBeforeDetach = requireNotNull(kernelActiveBeforeDetach) { "detach scenario missed active-observation snapshot" }
                check(profile.integrity.reconciliation.kernelActiveObservations == activeBeforeDetach) {
                    "detach scenario retained kernel-active observations after detachment: before=$activeBeforeDetach after=${profile.integrity.reconciliation.kernelActiveObservations}"
                }
            } else if (lifecycleScenario == "terminal_while_active") {
                check(lifecycleFaultApplied) { "native terminal barrier was never observed" }
                check(!profile.integrity.coverageComplete) {
                    "terminal shutdown with an active producer was incorrectly complete: ${profile.integrity}"
                }
                check(profile.integrity.lifecycleInvalidations > 0L) {
                    "terminal shutdown with an active producer did not invalidate lifecycle evidence: ${profile.integrity}"
                }
                check(profile.integrity.reconciliation.admittedInvocations > 0L) {
                    "terminal scenario did not retain the active native invocation"
                }
            } else if (lifecycleScenario == "capture_failure") {
                val yield = requireNotNull(captureYield) { "capture failure scenario requires capture-yield evidence" }
                val join = requireNotNull(captureYieldJoin) { "capture failure scenario requires capture-yield join evidence" }
                val failedInvocationIds = yield.records
                    .filter { it.outcome == TierECaptureYield.Outcome.CAPTURE_FAILED }
                    .mapTo(HashSet()) { it.invocationId }
                check(failedInvocationIds.isNotEmpty()) { "native capture failure injection did not fire" }
                check(join.missingDefinition > 0) { "failed capture unexpectedly had an exact invocation definition: $join" }
                check(
                    profile.syscalls.none { syscall ->
                        syscall.resolutionStatus == ResolutionStatus.RESOLVED &&
                            syscall.observation.invocationId?.value in failedInvocationIds
                    },
                ) { "a failed capture invocation was emitted as exact attribution" }
                check(oracle.expected > oracle.matched && oracle.duplicates == 0L && oracle.wrong == 0L && oracle.unclassified == 0L) {
                    "capture failure did not omit its controlled operation from exact attribution: $oracle"
                }
            } else if (lifecycleScenario == "exec_transition") {
                check(lifecycleFaultApplied) { "native exec barrier was never observed" }
                check(!profile.integrity.coverageComplete) {
                    "exec transition incorrectly claimed complete lifecycle evidence: ${profile.integrity}"
                }
                check(profile.integrity.lifecycleInvalidations > 0L) {
                    "exec transition did not invalidate lifecycle evidence: ${profile.integrity}"
                }
                check(profile.integrity.ambientInactiveObservations > 0L) {
                    "post-exec syscalls did not become explicitly inactive: ${profile.integrity}"
                }
                check(profile.syscalls.none { it.resolutionStatus == ResolutionStatus.RESOLVED }) {
                    "post-exec syscall retained exact attribution from the replaced JVM"
                }
            } else if (expectRetentionExhaustion) {
                check(profile.integrity.observedLosses > 0L && !profile.integrity.coverageComplete) {
                    "retention exhaustion must remain explicit incomplete evidence: ${profile.integrity}"
                }
                check(resolved > 0) { "retention exhaustion control did not retain any resolved observations" }
            } else {
                check(profile.integrity.observedLosses == 0L) { "Tier E lost observations: ${profile.integrity}" }
                if (ringSubmissionMode == TierERingSubmissionMode.ENABLED) {
                    check(resolved > 0) { "no syscall received a logical Java stack" }
                } else {
                    check(resolved == 0 && profile.syscalls.isEmpty()) {
                        "ring submission control retained exact observation evidence"
                    }
                }
                if (lifecycleScenario == "callback_suppression") {
                    check(profile.integrity.ambientInactiveObservations > 0L) {
                        "callback probe did not produce an explicit inactive interval: ${profile.integrity}"
                    }
                    check(callbackProbeResolved > 0L) {
                        "callback probe did not establish an independently resolved nested attribution"
                    }
                    check(
                            operationOracleAvailable &&
                            oracle.expected > 0L &&
                            oracle.matched == 0L &&
                            oracle.candidates == 0L &&
                            oracle.acknowledged == 0L &&
                            oracle.suppressed == 0L &&
                            oracle.unresolved == 0L &&
                            (oracle.missing == 0L || oracle.missing == oracle.expected) &&
                            oracle.duplicates == 0L &&
                            oracle.wrong == 0L &&
                            oracle.unclassified == 0L,
                    ) {
                        "callback suppression restored or misclassified an invalidated outer operation: $oracle"
                    }
                }
            }
            check(wrong == 0) { "$wrong syscalls were attributed outside the controlled workload stack" }
            check(wronglyAttributedUnlinks == 0) {
                "$wronglyAttributedUnlinks unlink syscalls were assigned a non-deletion stack"
            }
            if (
                ringSubmissionMode == TierERingSubmissionMode.ENABLED &&
                lifecycleScenario !in setOf("detach_while_active", "callback_suppression", "terminal_while_active", "capture_failure", "exec_transition") &&
                operationOracleAvailable &&
                !expectRetentionExhaustion
            ) {
                val oracleMatchesMode = if (uniqueRepresentativeOracle) {
                    oracle.expected > 0L &&
                        oracle.matched == oracle.expected &&
                        oracle.acknowledged > 0L &&
                        oracle.missing == 0L &&
                        oracle.unresolved == 0L
                } else {
                    oracle.matched == oracle.expected
                }
                check(oracleMatchesMode && oracle.duplicates == 0L && oracle.wrong == 0L && oracle.unclassified == 0L) {
                    "Tier E write oracle mismatch: $oracle"
                }
            }
            if (args.getOrNull(4) == "virtual") {
                val virtualResolved = profile.syscalls.filter { it.resolutionStatus == ResolutionStatus.RESOLVED }
                check(
                    virtualResolved.all { it.observation.captureFlags and 1 != 0 },
                ) {
                    "virtual-thread observations were not marked as virtual"
                }
                check(virtualResolved.all { it.executionContext?.kind == ExecutionKind.VIRTUAL }) {
                    "virtual-thread observations lost their logical execution identity"
                }
                check(profile.integrity.virtualPinnedScopes > 0) {
                    "virtual-thread proxy intervals did not report their pinning cost"
                }
            }
            if (captureYield != null) {
                check(captureYield.records.isNotEmpty()) { "native capture-yield sidecar is empty" }
            }
        } catch (expectedFailure: Exception) {
            process.destroyForcibly()
            System.err.print(expectedFailure.stackTraceToString())
            exitProcess(1)
        } finally {
            strace?.let { tracer ->
                if (tracer.isAlive) tracer.destroy()
                tracer.waitFor(5, TimeUnit.SECONDS)
                if (tracer.isAlive) tracer.destroyForcibly()
            }
            receiptClock?.close()
            session.close()
            child.close()
            oracleRoot?.let { root -> runCatching { deleteTree(root) } }
            Files.deleteIfExists(gate)
            Files.deleteIfExists(oracleManifest)
            Files.deleteIfExists(lifecycleReady)
            Files.deleteIfExists(lifecycleRelease)
            captureYieldPath?.let(Files::deleteIfExists)
        }
    }

    private fun processEpoch(pid: Long): Long {
        val stat = Files.readString(Path.of("/proc/$pid/stat"))
        return stat.substringAfterLast(") ").split(' ')[19].toLong()
    }

    internal fun captureProfileOption(captureProfile: TierECaptureProfile): String = captureProfile.agentOption?.let { ",$it" }.orEmpty()

    private fun deleteTree(root: Path) {
        Files.walk(root).use { paths ->
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun pinTargetToCpu(
        cpu: Int,
        pid: Long,
    ) {
        ManagedProcessCapture
            .start(ProcessBuilder("taskset", "-pc", cpu.toString(), pid.toString()).redirectErrorStream(true))
            .use { affinity ->
                val output = affinity.readStdout(Duration.ofSeconds(AFFINITY_TIMEOUT_SECONDS))
                check(affinity.awaitExit(Duration.ofSeconds(AFFINITY_TIMEOUT_SECONDS)) == 0) {
                    "failed to pin target to CPU $cpu: $output"
                }
            }
    }

    /**
     * Hashes normalized decoded frames only after the oracle selected its declared operations.
     * The comparison is deliberately restricted to the workload-owned frame chain. Stack,
     * invocation, class-loader instance IDs, bytecode locations, and JDK implementation frames
     * are not stable across independent JVM compilation histories. Session dictionaries retain
     * the complete exact stack; this benchmark proves that both profiles preserve the declared
     * workload's decoded logical frames for every oracle-selected operation.
     */
    internal fun semanticStackDigest(records: List<io.mazewall.profiler.internal.attribution.ResolvedSyscall>): String? {
        if (records.isEmpty()) return null
        return semanticStackDefinitionDigest(
            records.map { syscall ->
                checkNotNull(syscall.stackDefinition) { "resolved syscall lacks a stack definition" }
            },
        )
    }

    internal fun semanticStackDefinitionDigest(definitions: List<io.mazewall.profiler.internal.attribution.StackDefinition>): String? {
        if (definitions.isEmpty()) return null
        val canonical = definitions
            .map { definition ->
            val workloadFrames = definition.frames.filter { it.className.startsWith("io.mazewall.profiler.tierE.stress.") }
            check(workloadFrames.isNotEmpty()) { "oracle-selected syscall lacks a workload-owned frame" }
            buildString {
                appendField(definition.captureQuality.name)
                workloadFrames.forEach { frame ->
                    appendField(frame.className)
                    appendField(frame.methodName)
                    appendField(frame.descriptor)
                    appendField(frame.kind.name)
                }
            }
        }.sorted()
            .joinToString(separator = "\n")
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.encodeToByteArray()))
    }

    private fun StringBuilder.appendField(value: String) {
        append(value.length).append(':').append(value).append(';')
    }

    private fun markerLossReason(captureFlags: Int): TierEOperationOracle.MarkerLossReason? =
        when {
            captureFlags and SyscallInvocationEvent.FLAG_TASK_STORAGE_UNAVAILABLE != 0 -> TierEOperationOracle.MarkerLossReason.TASK_STORAGE_UNAVAILABLE
            captureFlags and SyscallInvocationEvent.FLAG_MARKER_DETACHED != 0 -> TierEOperationOracle.MarkerLossReason.DETACHED
            captureFlags and SyscallInvocationEvent.FLAG_STALE_MARKER_GENERATION != 0 -> TierEOperationOracle.MarkerLossReason.STALE_GENERATION
            captureFlags and SyscallInvocationEvent.FLAG_INACTIVE_INVOCATION != 0 -> TierEOperationOracle.MarkerLossReason.INACTIVE_INVOCATION
            captureFlags and SyscallInvocationEvent.FLAG_MARKER_STATE_UNAVAILABLE != 0 -> TierEOperationOracle.MarkerLossReason.MARKER_STATE_UNAVAILABLE
            else -> null
        }

    private fun semanticBindings(applicationKind: TierECommonFileWorkload.ApplicationKind): List<TierECaptureBinding> =
        when (applicationKind) {
            TierECommonFileWorkload.ApplicationKind.FILESYSTEM_METADATA -> TierECaptureProfilePreset.FILESYSTEM_METADATA.profile.bindings
            TierECommonFileWorkload.ApplicationKind.BUFFERED_FILE_IO -> listOf(
                TierECaptureBinding.UNIX_OPEN0,
                TierECaptureBinding.UNIX_OPENAT0,
                TierECaptureBinding.UNIX_CLOSE0,
                TierECaptureBinding.UNIX_UNLINK0,
                TierECaptureBinding.FILE_READ0,
                TierECaptureBinding.FILE_WRITE0,
                TierECaptureBinding.FILE_CLOSE_INT_FD,
            )
            TierECommonFileWorkload.ApplicationKind.LOOPBACK_SOCKET -> TierECaptureProfilePreset.NETWORK_IO.profile.bindings
        }

    private const val AFFINITY_TIMEOUT_SECONDS = 10L
    private const val SYS_UNLINK: Int = 87
    private const val SYS_WRITE: Int = 1
    private const val SYS_OPENAT: Int = 257
}
