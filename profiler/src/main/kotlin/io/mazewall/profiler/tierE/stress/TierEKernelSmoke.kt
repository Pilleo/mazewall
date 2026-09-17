package io.mazewall.profiler.tierE.stress

import io.mazewall.ffi.internal.RealNativeEngine
import io.mazewall.profiler.internal.attribution.AgentDefinitionFile
import io.mazewall.profiler.internal.attribution.ExecutionKind
import io.mazewall.profiler.internal.attribution.ResolutionStatus
import io.mazewall.profiler.internal.attribution.TierEEmissionMode
import io.mazewall.profiler.internal.attribution.TierEOptions
import io.mazewall.profiler.internal.attribution.TierEProfilerSession
import io.mazewall.profiler.internal.attribution.TierEStackTransport
import io.mazewall.profiler.internal.attribution.UniqueEdgeDecision
import io.mazewall.profiler.tierE.daemon.ElfSymbolOffset
import io.mazewall.profiler.tierE.engine.TierEbpfEngine
import io.mazewall.profiler.triage.ManagedProcessCapture
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
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
        val definitions = Path.of(args[3]).toAbsolutePath()
        val captureYieldPath = System.getProperty("mazewall.tier-e.capture-yield")?.let(Path::of)?.toAbsolutePath()
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
        Files.deleteIfExists(gate)
        Files.deleteIfExists(oracleManifest)
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
        val benchmarkOptions = if (benchmark) {
            ",benchmark-phases" +
                (if (System.getProperty("mazewall.tier-e.canonical-cache") == "disabled") ",canonical-cache=disabled" else "") +
                (if (System.getProperty("mazewall.tier-e.live-publication") == "true") ",live-definitions" else "") +
                System
                    .getProperty("mazewall.tier-e.capture-profile")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { ",capture-profile=$it" }
                    .orEmpty()
        } else {
            ""
        }
        val captureYieldOption = captureYieldPath?.let { ",capture-yield=$it" } ?: ""
        val command = mutableListOf(
            java,
            "-agentpath:$agent=definitions=$definitions,session=${sessionTag.toString(
                16,
            )},mode=${args.getOrElse(7) { "full-stream" }},stack-transport=${args.getOrElse(8) { "dictionary" }}$benchmarkOptions$captureYieldOption",
            "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED",
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
        val processEpoch = processEpoch(process.pid())
        val uniqueEdgeDecisions = mutableListOf<UniqueEdgeDecision>()
        val session = TierEProfilerSession(
            TierEbpfEngine(RealNativeEngine),
            processEpoch,
            TierEOptions(emissionMode, stackTransport),
            uniqueEdgeDecisionObserver = uniqueEdgeDecisions::add,
        )
        var oracleRoot: Path? = null
        try {
            val expectedOpenatFds = if (sharedOpenatOracleWorkload) {
            if (sharedOpenatOracleWorkload) {
                while (!Files.exists(oracleManifest) || Files.size(oracleManifest) == 0L) Thread.sleep(1)
            }
                val manifest = Files.readAllLines(oracleManifest)
                oracleRoot = manifest.first { it.startsWith("root=") }.removePrefix("root=").let(Path::of)
                manifest.filter { it.startsWith("fd=") }.map { it.removePrefix("fd=").toInt() }.toSet()
            } else {
                emptySet()
            }
            val attachStarted = System.nanoTime()
            session.installAndAttach(process.pid().toInt(), sessionTag, agent, markerOffset)
            val attachNanos = System.nanoTime() - attachStarted
            args.getOrNull(6)?.toInt()?.let { cpu ->
                pinTargetToCpu(cpu, process.pid())
            }
            val workloadStarted = System.nanoTime()
            Files.writeString(gate, "go")
            while (process.isAlive) {
                session.poll()
                if (!benchmark) {
                    Thread.sleep(5)
                }
            }
            check(process.waitFor() == 0) { "agent workload failed" }
            val captureYield = captureYieldPath?.let { TierECaptureYield.read(it) }
            val captureYieldJoin = captureYield?.let { TierECaptureYield.join(it, AgentDefinitionFile.read(definitions).invocations) }
            val terminalStarted = System.nanoTime()
            val profile = session.finish(definitions)
            val terminalNanos = System.nanoTime() - terminalStarted
            val workloadNanos = System.nanoTime() - workloadStarted
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
            val oracle = if (oracleEnabled) {
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
                        uniqueEdgeDecisions
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
                            )
                        },
                )
                }
            } else {
                TierEOperationOracle.Report(0, 0, 0, 0, 0)
            }
            // The shared workload declares one delete per batch. This verifies a stable
            // operation count, but path pointers are not copied from the ring record, so it
            // cannot prove a per-operation identity mapping. Keep this explicitly unverified.
            val expectedWorkloadUnlinks: Long = if (oracleEnabled) {
                oracle.expected
            } else if (commonWorkload && benchmark) {
                args.getOrElse(11) { "3" }.toLong() + args.getOrElse(12) { "10" }.toLong()
            } else if (commonWorkload) {
                1L
            } else {
                0L
            }
            val matchedOperations: Long = if (oracleEnabled) oracle.matched else workloadUnlinksResolved.toLong()
            val missingWorkloadUnlinks = if (oracleEnabled) oracle.missing else (expectedWorkloadUnlinks - matchedOperations).coerceAtLeast(0L)
            val extraWorkloadUnlinks = (matchedOperations - expectedWorkloadUnlinks).coerceAtLeast(0L)
            val uniqueRepresentativeOracle = oracleEnabled && emissionMode == TierEEmissionMode.UNIQUE_STACK_SYSCALL
            val suppressedRepresentatives = if (uniqueRepresentativeOracle) oracle.suppressed else 0L
            val operationOracleExpectation = if (uniqueRepresentativeOracle) {
                "unique_representative"
            } else {
                "full_exact"
            }
            println(
                "tier-e-kernel-smoke: markerTransitions=${session.markerTransitionCount()} " +
                "lastMarkerPidTgid=${session.lastMarkerPidTgid().toULong().toString(16)} " +
                "observations=${profile.syscalls.size} resolved=$resolved workload_resolved=$workloadResolved " +
                "workload_unlinks_resolved=$workloadUnlinksResolved unknown_no_active=$unknown " +
                "ambient_inactive=${profile.integrity.ambientInactiveObservations} " +
                "reconciliation_admitted_invocations=${profile.integrity.reconciliation.admittedInvocations} " +
                "reconciliation_exact_invocations=${profile.integrity.reconciliation.exactInvocations} " +
                "reconciliation_failed_invocations=${profile.integrity.reconciliation.failedInvocations} " +
                "reconciliation_kernel_active_observations=${profile.integrity.reconciliation.kernelActiveObservations} " +
                "unknown_other=$unexplainedUnknown wrong=$wrong lost=${profile.integrity.observedLosses} " +
                "failures=${profile.integrity.attributionFailures} evidence_complete=${profile.integrity.coverageComplete} " +
                "expected_operations=$expectedWorkloadUnlinks matched_operations=$matchedOperations " +
                "missing_operations=$missingWorkloadUnlinks extra_operations=$extraWorkloadUnlinks " +
                "candidate_operations=${oracle.candidates} acknowledged_operations=${oracle.acknowledged} " +
                "suppressed_representatives=$suppressedRepresentatives " +
                "unresolved_operations=${oracle.unresolved} " +
                "duplicate_operations=${oracle.duplicates} wrong_operations=${oracle.wrong} " +
                "unclassified_operations=${oracle.unclassified} operation_oracle_available=$oracleEnabled " +
                "operation_oracle_expectation=$operationOracleExpectation " +
                "capture_yield_records=${captureYield?.records?.size ?: 0} " +
                "capture_yield_captured=${captureYield?.captured ?: 0} " +
                "capture_yield_capture_failed=${captureYield?.captureFailed ?: 0} " +
                "capture_yield_admission_failed=${captureYield?.admissionFailed ?: 0} " +
                "capture_yield_excluded=${captureYield?.excluded ?: 0} " +
                "capture_yield_exact_joined=${captureYieldJoin?.exactJoined ?: 0} " +
                "capture_yield_missing_definition=${captureYieldJoin?.missingDefinition ?: 0} " +
                "capture_yield_outcome_mismatch=${captureYieldJoin?.outcomeMismatch ?: 0} " +
                "capture_yield_unrecorded_definitions=${captureYieldJoin?.unrecordedDefinitions ?: 0} " +
                "openat_manifest_non_singletons=${manifestCandidateCounts.filterValues { it != 1 }.entries.take(16).joinToString(",") { "${it.key}:${it.value}" }} " +
                "attach_ns=$attachNanos terminal_ns=$terminalNanos workloadNs=$workloadNanos",
            )
            check(profile.integrity.observedLosses == 0L) { "Tier E lost observations: ${profile.integrity}" }
            check(resolved > 0) { "no syscall received a logical Java stack" }
            check(wrong == 0) { "$wrong syscalls were attributed outside the controlled workload stack" }
            check(wronglyAttributedUnlinks == 0) {
                "$wronglyAttributedUnlinks unlink syscalls were assigned a non-deletion stack"
            }
            if (oracleEnabled) {
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
            session.close()
            child.close()
            oracleRoot?.let { root -> runCatching { deleteTree(root) } }
            Files.deleteIfExists(gate)
            Files.deleteIfExists(oracleManifest)
            captureYieldPath?.let(Files::deleteIfExists)
        }
    }

    private fun processEpoch(pid: Long): Long {
        val stat = Files.readString(Path.of("/proc/$pid/stat"))
        return stat.substringAfterLast(") ").split(' ')[19].toLong()
    }

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

    private const val AFFINITY_TIMEOUT_SECONDS = 10L
    private const val SYS_UNLINK: Int = 87
    private const val SYS_WRITE: Int = 1
    private const val SYS_OPENAT: Int = 257
}
