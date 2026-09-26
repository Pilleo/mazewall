package io.mazewall.profiler.tierE.tools.controller

import io.mazewall.profiler.tierE.tools.TierEBenchmarkOperationAcceptance
import io.mazewall.profiler.tierE.tools.TierECollectorCpuSample
import io.mazewall.profiler.tierE.tools.TierEFixtureEnvelope
import io.mazewall.profiler.tierE.tools.TierEFixtureVerdict
import io.mazewall.profiler.tierE.tools.TierERealPidReuseProbe
import io.mazewall.profiler.tierE.tools.TierERunConfiguration
import io.mazewall.profiler.tierE.tools.TierESplitCoverageVerifier
import io.mazewall.profiler.tierE.tools.TierESplitOperationVerifier
import io.mazewall.profiler.tierE.tools.TierETargetCpuSample
import io.mazewall.testing.containers.ContainerCommandResult
import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.runContainerCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/** Developer-only two-container fixture. Validates the requested host before allocating resources. */
public object TierESplitFixtureController {
    public fun run(
        configuration: TierERunConfiguration,
        endpoint: ContainerEndpoint,
        repository: Path,
        artifacts: Path,
        nativeDirectory: Path? = null,
        seed: Int = 23063,
        pinCpus: Boolean = false,
        benchmarkPhases: Boolean = false,
        workloadProfile: String = "syscall_dense",
        ownedRunId: String? = null,
        ownedShared: Path? = null,
        creationLock: Path? = null,
        creationState: Path? = null,
    ) {
        configuration.validate()
        require(seed >= 0) { "Tier E seed must be nonnegative" }
        require(workloadProfile in setOf("syscall_dense", "stack_diverse", "class_churn")) {
            "Unsupported Tier E workload profile: $workloadProfile"
        }
        require(endpoint.resolvedUri?.startsWith("unix://") == true) {
            "Split Tier E fixture requires a verified local container endpoint"
        }
        require(endpoint.engine.name.equals(configuration.container.engine, ignoreCase = true)) { "Selected engine differs from profile" }
        require(configuration.container.uri == null || configuration.container.uri == endpoint.resolvedUri) { "Container URI disagrees with profile" }
        require(Files.isDirectory(repository.resolve("profiler-native"))) { "Missing native profiler source" }
        require(Files.isDirectory(artifacts)) { "Missing caller-owned artifact directory" }
        val external = ownedRunId != null
        require((ownedRunId != null) == (ownedShared != null) && (ownedRunId != null) == (creationLock != null) && (ownedRunId != null) == (creationState != null)) {
            "External Tier E ownership requires run id, shared directory, lock, and state"
        }
        if (!external) require(Files.list(artifacts).use { it.findAny().isEmpty }) { "Tier E fixture requires fresh artifact directory" }
        require(configuration.scenario.identityCase == null || configuration.scenario.identityCase in setOf("stale", "simulated-reuse")) {
            "Kotlin fixture does not yet run real guest PID reuse"
        }
        require(
            when (configuration.scenario.identityCase) {
                null -> true
                "stale" -> configuration.fixture.coverage
                "simulated-reuse" -> !configuration.fixture.coverage
                else -> false
            } &&
                !configuration.scenario.baseline &&
                !configuration.scenario.benchmark &&
                configuration.scenario.faultSyscall == null &&
                configuration.scenario.negativeArtifact == null ||
                configuration.scenario.identityCase == null,
        ) {
            "Stale identity requires coverage; simulated reuse requires the registered workflow"
        }
        require(!(configuration.fixture.coverage && configuration.scenario.benchmark)) { "Coverage and benchmark workloads cannot share one arm" }
        val runId = ownedRunId ?: "tier-e-kotlin-${UUID.randomUUID()}"
        require(runId.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,99}"))) { "Invalid scoped Tier E run ID" }
        val shared = ownedShared ?: Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")).toRealPath(), "tier-e-owned-$runId-")
        val lock = creationLock ?: artifacts.resolve("$runId.creation.lock")
        val state = creationState ?: artifacts.resolve("$runId.creation.lock.state")
        if (external) {
            val artifactRoot = artifacts.toRealPath()
            val tempRoot = Path.of(System.getProperty("java.io.tmpdir")).toRealPath()
            require(
                Files.isDirectory(shared, NOFOLLOW_LINKS) &&
                shared.toRealPath().parent == tempRoot &&
                shared.fileName.toString().startsWith("tier-e-owned-$runId-"),
            ) { "Invalid externally owned shared directory" }
            require(Files.getAttribute(shared, "unix:uid") == Files.getAttribute(artifacts, "unix:uid")) { "Shared directory owner mismatch" }
            require(
                Files.isRegularFile(state, NOFOLLOW_LINKS) &&
                state.toRealPath().parent == artifactRoot &&
                state.fileName.toString() == "$runId.creation.lock.state" &&
                Files.getAttribute(state, "unix:uid") == Files.getAttribute(artifacts, "unix:uid") &&
                Files.readString(state) == "open\n",
            ) {
                "External creation state must be open and owned by this run"
            }
            require(
                lock.parent?.toRealPath() == artifactRoot &&
                lock.fileName.toString() == "$runId.creation.lock" &&
                !Files.isSymbolicLink(lock) &&
                (
                    !Files.exists(lock, NOFOLLOW_LINKS) ||
                    Files.isRegularFile(lock, NOFOLLOW_LINKS) &&
                Files.getAttribute(lock, "unix:uid") == Files.getAttribute(artifacts, "unix:uid")
                ),
            ) {
                "External creation lock must belong to this run"
            }
            require(
                !Files.exists(artifacts.resolve("resolved-config.json"), NOFOLLOW_LINKS) &&
                !Files.exists(artifacts.resolve("$runId.shared-path"), NOFOLLOW_LINKS),
            ) { "External fixture output already exists" }
        }
        require(Files.isDirectory(shared, NOFOLLOW_LINKS)) { "Missing owned Tier E shared directory" }
        Files.writeString(artifacts.resolve("$runId.shared-path"), "${shared.toRealPath()}\n")
        if (!external) Files.writeString(state, "open\n")
        Files.writeString(artifacts.resolve("resolved-config.json"), configuration.resolvedJson() + "\n")
        val owner = TierEContainerRun(endpoint, runId, artifacts, ownedShared = shared)
        try {
            val body = {
                execute(configuration, endpoint, repository, artifacts, nativeDirectory, runId, shared, lock, state, seed, pinCpus, benchmarkPhases, workloadProfile)
            }
            if (external) {
                body()
            } else {
                TierEFixtureLifecycle(owner, lock, retainOnFailure = configuration.artifacts.retainOnFailure).run(body)
            }
        } catch (failure: Throwable) {
            try {
                Files.writeString(
                    artifacts.resolve("recovery.json"),
                    buildJsonObject {
                    put("schema_version", 1)
                    put("run_id", runId)
                    put("target_name", "$runId-target")
                    put("collector_name", "$runId-collector")
                    put("shared_path", shared.toString())
                    put("creation_lock", lock.toString())
                    put(
                        "resource_state",
                        when {
                        external -> "EXTERNALLY_OWNED"
                        configuration.artifacts.retainOnFailure -> "RETAINED_FOR_RECOVERY"
                        else -> "CLEANUP_ATTEMPTED"
                    },
                    )
                    put("policy_eligibility", false)
                }.toString() +
                    "\n",
                )
                if (!external && configuration.artifacts.retainOnFailure) {
                    failure.addSuppressed(IllegalStateException("Tier E resources retained: $runId-target, $runId-collector, $shared"))
                }
            } catch (recordFailure: Exception) {
                failure.addSuppressed(recordFailure)
            }
            throw failure
        }
    }

    private fun execute(
        config: TierERunConfiguration,
        endpoint: ContainerEndpoint,
        root: Path,
        artifacts: Path,
        native: Path?,
        runId: String,
        shared: Path,
        lock: Path,
        state: Path,
        seed: Int,
        pinCpus: Boolean,
        benchmarkPhases: Boolean,
        workloadProfile: String,
    ) {
        val timeout = Duration.ofSeconds(config.deadlines.armSeconds.toLong())

        fun command(
            arguments: List<String>,
            duration: Duration = timeout,
            output: Path? = null,
            required: Boolean = true,
        ): ContainerCommandResult {
            val result = runContainerCommand(arguments, duration, outputFile = output)
            if (required) check(result.exitCode == 0) { "Tier E command failed (${result.exitCode}): ${arguments.take(6)} ${result.output}" }
            return result
        }

        fun engine(vararg args: String): List<String> = endpoint.command(*args)
        val image = config.container.image
        val available = command(engine("image", "inspect", image), required = false)
        if (available.exitCode != 0) {
            command(
                engine("build", "-t", image, "-f", root.resolve("infra/dev/Containerfile").toString(), root.toString()),
                Duration.ofMinutes(5),
                artifacts.resolve("image-build.log"),
            )
        }
        val pidNamespace = Files.readSymbolicLink(Path.of("/proc/self/ns/pid")).toString()
        val guestNamespace = command(engine("run", "--rm", "--pid=host", "--entrypoint", "readlink", image, "/proc/self/ns/pid"), Duration.ofSeconds(20))
        check(guestNamespace.output.trim() == pidNamespace) { "Selected engine does not share local PID namespace" }
        val probe = Files.createTempDirectory("tier-e-mount-proof-")
        try {
            val token = UUID.randomUUID().toString()
            Files.writeString(probe.resolve("token"), token)
            val observed = command(
                engine(
                    "run",
                    "--rm",
                    "--mount",
                    "type=bind,source=$probe,target=/probe,readonly",
                "--entrypoint",
                    "cat",
                    image,
                    "/probe/token",
                ),
                    Duration.ofSeconds(20),
            )
            check(observed.output.trim() == token) { "Selected engine cannot see local bind mount" }
        } finally {
            probe.toFile().deleteRecursively()
        }

        Files.createDirectories(shared.resolve("definitions"))
        Files.createDirectories(shared.resolve("evidence"))
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxr-xr-x"))
        for (part in listOf("definitions", "evidence")) {
            Files.setPosixFilePermissions(shared.resolve(part), PosixFilePermissions.fromString("rwxrwx---"))
        }
        val negativeArtifact = config.scenario.negativeArtifact
        val artifactFile = when (negativeArtifact) {
            null -> "orders.mzse"
            "missing", "malformed", "symlink", "unclosed", "session-mismatch" -> "$negativeArtifact.mzse"
            else -> error("Unrecognized negative artifact: $negativeArtifact")
        }
        when (negativeArtifact) {
            "malformed" -> Files.writeString(shared.resolve("definitions/$artifactFile"), "not-an-mzse-envelope")
            "symlink" -> Files.createSymbolicLink(shared.resolve("definitions/$artifactFile"), Path.of("orders.mzse"))
            "unclosed", "session-mismatch" -> Files.write(shared.resolve("definitions/$artifactFile"), TierEFixtureEnvelope.create(negativeArtifact))
        }
        val nativeDir = native ?: TierENativeFixtureBuild.prepare(root, config.build.nativeBuildDir)
        val library = nativeDir.resolve("libmazewall_stack_agent.so")
        check(Files.isRegularFile(library)) { "Native Tier E agent missing" }
        Files.copy(library, shared.resolve("libmazewall_stack_agent.so"))
        val classes = nativeDir.resolve("agent-test-classes")
        Files.walk(classes).use { paths ->
            paths.forEach { file ->
            val destination = shared.resolve("agent-test-classes").resolve(classes.relativize(file))
            if (Files.isDirectory(file)) Files.createDirectories(destination) else Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING)
        }
        }
        val symbol = if (config.scenario.markerVersion == "v2") "mazewall_stack_marker_v2" else "mazewall_stack_marker"
        val symbols = command(listOf("nm", "-D", "--defined-only", library.toString()), Duration.ofSeconds(15), artifacts.resolve("native-symbols.log"))
        val offset = Files
            .readAllLines(artifacts.resolve("native-symbols.log"))
            .map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size >= 3 && it.last() == symbol }
            ?.firstOrNull()
            ?: error("Missing native Tier E marker: $symbol (${symbols.output})")
        val markerOffset = "0x${offset.toLong(16).toString(16)}"
        val uid = Files.getAttribute(shared, "unix:uid") as Int
        val gid = Files.getAttribute(shared, "unix:gid") as Int
        val properties = shared.resolve("target.properties")
        val markerVersion = config.scenario.markerVersion

        fun registration(
            pid: Long,
            starttime: Long,
        ) {
            Files.writeString(
                properties,
                "version=${if (markerVersion == "v2") 2 else 1}\nregistration_id=split-fixture\n" +
                "pid=$pid\nsession_tag=4d5a\nprocess_incarnation=$starttime\n" +
                "marker_path=/shared/libmazewall_stack_agent.so\nmarker_offset=$markerOffset\nartifact_file=$artifactFile\n" +
                if (markerVersion == "v2") "marker_proof_file=orders.marker-proof\n" else "",
            )
        }
        registration(1, 1)
        val options = listOfNotNull(
            "session=4d5a",
            "session-bound-definitions",
            "definitions=/shared/definitions/orders.mzse",
            if (markerVersion == "v2") "marker-version=v2" else null,
            if (markerVersion == "v2") "marker-version-proof=/shared/definitions/orders.marker-proof" else null,
            if (config.scenario.stackTransport == "inline-per-invocation") "stack-transport=inline-per-invocation" else null,
            if (config.scenario.liveSnapshots) "live-definitions" else null,
            if (benchmarkPhases) "benchmark-phases" else null,
        ).joinToString(",")
        val targetName = "$runId-target"
        val collectorName = "$runId-collector"
        val admission = listOf(
            "flock",
            "-x",
            lock.toString(),
            "bash",
            "-c",
            "[[ \$(cat \"\$1\") == open ]] || exit 87; shift; exec \"\$@\"",
            "--",
            state.toString(),
        )

        fun owned(arguments: List<String>): ContainerCommandResult = command(admission + arguments)
        val targetArguments = if (config.scenario.benchmark) {
            val benchmarkMode = (if (config.scenario.stackTransport == "dictionary") "dictionary" else "inline") +
                if (config.fixture.emissionMode == "full-stream") "-full-stream" else "-unique-edge"
            listOf(
                "io.mazewall.profiler.tierE.stress.TierESplitBenchmarkTarget",
                benchmarkMode,
                config.measurement.warmups.toString(),
                config.measurement.batches.toString(),
                config.measurement.iterations.toString(),
                "/shared/start",
                "/shared/target-host-pid",
                "/shared/control.sock",
                "/shared/libmazewall_stack_agent.so",
                markerOffset,
                "4d5a",
                "/shared/benchmark-oracle.json",
            )
        } else if (config.fixture.coverage) {
            listOf(
                "AgentCoverageWorkload",
                "gate:/shared/start",
                "/shared/coverage-oracle.json",
                "/shared/control.sock",
                "/shared/libmazewall_stack_agent.so",
                markerOffset,
                "4d5a",
                "defer-terminal",
                "/shared/target-host-pid",
            )
        } else {
            listOf(
                "AgentWorkload",
                "gate:/shared/start",
                "platform",
                "100",
                "/shared/control.sock",
                "/shared/libmazewall_stack_agent.so",
                markerOffset,
                "4d5a",
                "defer-terminal",
                "/shared/target-host-pid",
            )
        }
        val agentArgument = if (config.scenario.baseline) emptyList() else listOf("-agentpath:/shared/libmazewall_stack_agent.so=$options")
        val classpath = if (config.scenario.benchmark) "/workspace/profiler/build/install/tier-e-daemon/lib/*" else "/shared/agent-test-classes"
        val targetCpu = if (pinCpus) listOf("--cpuset-cpus", config.measurement.workloadCpu.toString()) else emptyList()
        val collectorCpu = if (pinCpus) listOf("--cpuset-cpus", config.measurement.collectorCpu.toString()) else emptyList()
        val target = engine(
            "run",
            "-d",
            "--name",
            targetName,
            "--user",
            "$uid:$gid",
            "--cap-drop",
            "ALL",
            *targetCpu.toTypedArray(),
            "-v",
            "$shared:/shared",
            "-v",
            "$root:/workspace:ro",
            "-w",
            "/shared",
            "-e",
            "TIER_E_MARKER_OFFSET=$markerOffset",
            "-e",
            "TIER_E_COVERAGE_OPERATIONS=${config.fixture.operations}",
            "-e",
            "TIER_E_COVERAGE_SEED=$seed",
            "-e",
            "JAVA_TOOL_OPTIONS=-Dmazewall.tier-e.workload-profile=$workloadProfile",
            "-e",
            "TIER_E_SPLIT_EMISSION_MODE=${config.fixture.emissionMode}",
            "-e",
            "TIER_E_SPLIT_MARKER_BACKEND=${config.scenario.markerBackend}",
            "-e",
            "TIER_E_AGENT_OPTIONS=$options",
            "-e",
            "TIER_E_SPLIT_COVERAGE_BASELINE=${if (config.scenario.baseline) 1 else 0}",
            "-e",
            "TIER_E_TARGET_CPU_OUT=target-cpu.json",
            *(
                if (config.scenario.identityCase == "simulated-reuse") {
                arrayOf("-e", "TIER_E_SPLIT_SIMULATE_PID_REUSE_MARKER=/shared/reused")
            } else {
                emptyArray()
            }
            ),
            image,
            "java",
            "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED",
            "--enable-native-access=ALL-UNNAMED",
            *agentArgument.toTypedArray(),
            "-cp",
            classpath,
            *targetArguments.toTypedArray(),
        )
        owned(target)
        val hostPid = command(engine("inspect", "-f", "{{.State.Pid}}", targetName)).output.trim().toLong()
        require(hostPid > 0)
        val start = TierERealPidReuseProbe.starttime(Files.readString(Path.of("/proc/$hostPid/stat")))
        Files.writeString(shared.resolve("target-host-pid"), "$hostPid\n")
        registration(hostPid, start)
        val oracleHash = if (config.fixture.coverage) {
            await(Duration.ofSeconds(10), "target oracle") { Files.exists(shared.resolve("coverage-oracle.json")) && Files.size(shared.resolve("coverage-oracle.json")) > 0 }
            MessageDigest
                .getInstance("SHA-256")
                .digest(Files.readAllBytes(shared.resolve("coverage-oracle.json")))
                .joinToString("") { "%02x".format(it) }
        } else {
            null
        }
        if (config.scenario.baseline) {
            Files.createFile(shared.resolve("start"))
            command(engine("wait", targetName), timeout)
            val exit = command(engine("inspect", "-f", "{{.State.ExitCode}}", targetName)).output.trim().toInt()
            command(engine("logs", targetName), Duration.ofSeconds(10), artifacts.resolve("target.log"))
            Files.copy(shared.resolve("coverage-oracle.json"), artifacts.resolve("coverage-oracle.json"))
            Files.copy(shared.resolve("target-cpu.json"), artifacts.resolve("target-cpu.json"))
            val sample = TierETargetCpuSample.validate(artifacts, hostPid, start)
            check(sample.valid) { "Baseline target CPU identity mismatch: ${sample.reason}" }
            check(exit == 0 && "tier-e-coverage-target: operations=" in Files.readString(artifacts.resolve("target.log"))) {
                "Baseline workload failed: exit=$exit"
            }
            Files.writeString(
                artifacts.resolve("verdict.env"),
                "mode=baseline\ncoverage_verdict=NOT_APPLICABLE\nterminal=NOT_APPLICABLE\n" +
                "target_exit=$exit\npolicy_eligibility=false\n",
            )
            Files.writeString(artifacts.resolve("summary.json"), "{\"schema_version\":1,\"mode\":\"baseline\",\"target_exit\":$exit,\"policy_eligibility\":false}\n")
            return
        }
        val faultArguments = config.scenario.faultSyscall
            ?.let { listOf("-e", "TIER_E_BENCHMARK_DROP_RING_SYSCALL=$it") }
            .orEmpty()
        val coverageArguments = oracleHash
            ?.let {
            listOf(
                "-e",
                "TIER_E_COVERAGE_MANIFEST=1",
            "-e",
                "TIER_E_COVERAGE_ORACLE_SHA256=$it",
                "-e",
                "TIER_E_COVERAGE_SYSCALL_NUMBER=257",
            )
        }.orEmpty()
        val retentionArguments = if (config.scenario.benchmark || config.fixture.coverage && config.fixture.operations > 72) listOf("-e", "TIER_E_BENCHMARK_RECORD_LIMIT=250000") else emptyList()
        val collector = engine(
            "run",
            "-d",
            "--name",
            collectorName,
            "--privileged",
            "--pid=host",
            "--userns=host",
            *collectorCpu.toTypedArray(),
            "-v",
            "$shared:/shared",
            "-v",
            "$root:$root:ro",
            "-w",
            root.toString(),
            "-e",
            "TIER_E_SOCKET=/shared/control.sock",
            *(if (config.scenario.socketAccess == "group") arrayOf("-e", "TIER_E_SOCKET_GID=$gid") else emptyArray()),
            "-e",
            "TIER_E_TARGET_REGISTRATION=/shared/target.properties",
            "-e",
            "TIER_E_DEFINITION_ROOT=/shared/definitions",
            "-e",
            "TIER_E_EVIDENCE_ROOT=/shared/evidence",
            "-e",
            "TIER_E_MARKER_VERSION=$markerVersion",
            "-e",
            "TIER_E_COVERAGE_RECOVERY_MODE=${config.fixture.recoveryMode}",
            "-e",
            "TIER_E_RECOVERY_MAP_CAPACITY=4096",
            "-e",
            "TIER_E_SPLIT_NEGATIVE_IDENTITY_CASE=${config.scenario.identityCase ?: ""}",
            *(
                if (config.scenario.identityCase == "simulated-reuse") {
                arrayOf("-e", "TIER_E_BENCHMARK_REUSE_MARKER=/shared/reused")
            } else {
                emptyArray()
            }
            ),
            "-e",
            "TIER_E_SPLIT_BENCHMARK=${if (config.scenario.benchmark) 1 else 0}",
            "-e",
            "TIER_E_BENCHMARK_PHASES=${if (benchmarkPhases) 1 else 0}",
            "-e",
            "TIER_E_COLLECTOR_RECEIPT_CLOCK=${if (benchmarkPhases) "monotonic" else "disabled"}",
            "-e",
            "TIER_E_LIVE_SNAPSHOT_ADMISSION=${if (config.scenario.liveSnapshots) 1 else 0}",
            *coverageArguments.toTypedArray(),
            *retentionArguments.toTypedArray(),
            "-e",
            "TIER_E_TERMINAL_ARTIFACT_WAIT_MILLIS=${if (negativeArtifact == null) 30000 else 1000}",
            *faultArguments.toTypedArray(),
            image,
            "bash",
            "-lc",
            "mountpoint -q /sys/kernel/tracing || mount -t tracefs tracefs /sys/kernel/tracing; " +
                "if [ \"\$TIER_E_SPLIT_NEGATIVE_IDENTITY_CASE\" = stale ]; then " +
                "target_pid=\$(sed -n 's/^pid=//p' /shared/target.properties); " +
                "start=\$(awk '{print \$22}' \"/proc/\${target_pid}/stat\"); " +
                "sed -i \"s/^process_incarnation=.*/process_incarnation=\$((start + 1))/\" /shared/target.properties; " +
                "fi; exec profiler/build/install/tier-e-daemon/bin/tier-e-daemon",
        )
        owned(collector)
        await(Duration.ofSeconds(10), "collector control socket") { Files.exists(shared.resolve("control.sock")) }
        val unrelated = command(
            engine(
                "run",
                "--rm",
                "--user",
                "65534:65534",
                "--cap-drop",
                "ALL",
                "-v",
                "$shared:/shared",
            image,
                "java",
                "-cp",
                "/shared/agent-test-classes",
                "AgentWorkload",
                "control",
                "/shared/control.sock",
                "shutdown",
            ),
            Duration.ofSeconds(20),
            artifacts.resolve("unrelated-user.log"),
            required = false,
        )
        check(
            unrelated.exitCode != 0 &&
            Regex("Permission denied|AccessDeniedException|ConnectException")
            .containsMatchIn(Files.readString(artifacts.resolve("unrelated-user.log"))),
        ) { "Unrelated identity was admitted or failed without denial" }
        if (config.scenario.socketAccess == "owner-only") {
            val targetUser = command(
                engine(
                    "run",
                    "--rm",
                    "--user",
                    "$uid:$gid",
                    "--cap-drop",
                    "ALL",
                    "-v",
                    "$shared:/shared",
                    image,
                    "java",
                    "-cp",
                    "/shared/agent-test-classes",
                    "AgentWorkload",
                    "control",
                    "/shared/control.sock",
                    "shutdown",
                ),
                Duration.ofSeconds(20),
                artifacts.resolve("target-user-denied.log"),
                required = false,
            )
            check(
                targetUser.exitCode != 0 &&
                    Regex("Permission denied|AccessDeniedException|ConnectException")
                        .containsMatchIn(Files.readString(artifacts.resolve("target-user-denied.log"))),
            ) { "Target identity was admitted to owner-only socket" }
            command(
                engine(
                    "run",
                    "--rm",
                    "--user",
                    "0:0",
                    "-v",
                    "$shared:/shared",
                    image,
                    "java",
                    "-cp",
                    "/shared/agent-test-classes",
                    "AgentWorkload",
                    "control",
                    "/shared/control.sock",
                    "shutdown",
                ),
                Duration.ofSeconds(20),
                artifacts.resolve("control.log"),
            )
            command(engine("wait", collectorName), timeout)
            Files.writeString(
                artifacts.resolve("verdict.env"),
                "socket_access=OWNER_ONLY\ntarget_admission=DENIED\nunrelated_user_admission=DENIED\npolicy_eligibility=false\n",
            )
            return
        }
        Files.createFile(shared.resolve("start"))
        command(engine("wait", targetName), timeout)
        val report = shared.resolve("evidence/split-fixture-19802.tier-e.json")
        await(Duration.ofSeconds(config.deadlines.terminalSeconds.toLong()), "terminal report") { Files.exists(report) }
        if (config.fixture.coverage || config.scenario.benchmark) {
            val collectorPid = command(engine("inspect", "-f", "{{.State.Pid}}", collectorName)).output.trim().toLong()
            check(command(engine("inspect", "-f", "{{.State.Running}}", collectorName)).output.trim() == "true") { "Collector stopped before CPU sample" }
            val clockHz = command(listOf("getconf", "CLK_TCK")).output.trim().toLong()
            val sampled = TierECollectorCpuSample.sample(
                collectorPid,
                clockHz,
                Path.of("/proc"),
                artifacts.resolve("collector-process-metrics.json"),
                "after_terminal_report_before_shutdown",
            )
            check(sampled.valid) { "Collector CPU sample incomplete: ${sampled.reason}" }
        }
        command(
            engine(
                "run",
                "--rm",
                "--user",
                "$uid:$gid",
                "--cap-drop",
                "ALL",
                "-v",
                "$shared:/shared",
                image,
            "java",
                "-cp",
                "/shared/agent-test-classes",
                "AgentWorkload",
                "control",
                "/shared/control.sock",
                "shutdown",
            ),
            Duration.ofSeconds(30),
            artifacts.resolve("control.log"),
        )
        command(engine("wait", collectorName), Duration.ofSeconds(30))
        val targetExit = command(engine("inspect", "-f", "{{.State.ExitCode}}", targetName)).output.trim().toInt()
        val collectorExit = command(engine("inspect", "-f", "{{.State.ExitCode}}", collectorName)).output.trim().toInt()

        fun export(
            inner: String,
            dest: String,
        ) = command(
            engine("run", "--rm", "-v", "$shared:/shared:ro", image, "cat", inner),
            Duration.ofSeconds(15),
            artifacts.resolve(dest),
        )
        if (config.scenario.identityCase == "stale") {
            check(targetExit != 0 && collectorExit == 0) { "Stale identity exits: $targetExit/$collectorExit" }
            command(engine("logs", targetName), Duration.ofSeconds(10), artifacts.resolve("target.log"))
            command(engine("logs", collectorName), Duration.ofSeconds(10), artifacts.resolve("collector.log"))
            export("/shared/evidence/split-fixture-19802.tier-e.json", "terminal-report.json")
            Files.writeString(artifacts.resolve("verdict.env"), TierEFixtureVerdict.validate(artifacts, "stale"))
            return
        }
        check(targetExit == 0 && collectorExit == 0) { "Tier E target/collector exits: $targetExit/$collectorExit" }
        command(engine("logs", targetName), Duration.ofSeconds(10), artifacts.resolve("target.log"))
        command(engine("logs", collectorName), Duration.ofSeconds(10), artifacts.resolve("collector.log"))

        export("/shared/evidence/split-fixture-19802.tier-e.json", "terminal-report.json")
        if (config.scenario.identityCase == "simulated-reuse") {
            Files.writeString(artifacts.resolve("verdict.env"), TierEFixtureVerdict.validate(artifacts, "simulated-reuse"))
            return
        }
        if (config.scenario.benchmark) {
            Files.copy(shared.resolve("benchmark-oracle.json"), artifacts.resolve("benchmark-oracle.json"))
            export("/shared/evidence/split-fixture-19802.tier-e-operations.json", "benchmark-operations.json")
            val operation = TierESplitOperationVerifier.verify(
                artifacts.resolve("benchmark-oracle.json"),
                artifacts.resolve("benchmark-operations.json"),
                artifacts.resolve("terminal-report.json"),
            )
            Files.writeString(artifacts.resolve("operation-verdict.json"), "$operation\n")
            val observations = Json.parseToJsonElement(Files.readString(artifacts.resolve("benchmark-operations.json"))).jsonObject
            check(TierEBenchmarkOperationAcceptance.accept(config.fixture.emissionMode, observations, operation)) {
                "Benchmark operation evidence incomplete: $operation"
            }
        }
        if (config.fixture.coverage) {
            export("/shared/evidence/split-fixture-19802.tier-e-coverage.json", "coverage-manifest.json")
            Files.copy(shared.resolve("coverage-oracle.json"), artifacts.resolve("coverage-oracle.json"))
            Files.copy(shared.resolve("target-cpu.json"), artifacts.resolve("target-cpu.json"))
            val sample = TierETargetCpuSample.validate(artifacts, hostPid, start)
            check(sample.valid) { "Target CPU identity mismatch: ${sample.reason}" }
            val verdict = TierESplitCoverageVerifier.verify(
                artifacts.resolve("coverage-oracle.json"),
                artifacts.resolve("coverage-manifest.json"),
                artifacts.resolve("terminal-report.json"),
            )
            Files.writeString(artifacts.resolve("coverage-verdict.json"), "$verdict\n")
            check(verdict.getValue("coverage_verdict").toString() == "\"PASS\"") { "Tier E coverage incomplete: $verdict" }
        }
        Files.writeString(
            artifacts.resolve("verdict.env"),
            TierEFixtureVerdict.validate(
                artifacts,
                when {
                    negativeArtifact != null -> "negative:$negativeArtifact"
                    config.scenario.faultSyscall != null -> "fault"
                    else -> "normal"
                },
            ) +
            "emission_mode=${config.fixture.emissionMode}\nstack_transport=${config.scenario.stackTransport}\n",
        )
    }

    private fun await(
        timeout: Duration,
        description: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!condition()) {
            if (Thread.interrupted()) throw InterruptedException("Interrupted waiting for $description")
            check(System.nanoTime() < deadline) { "Timed out waiting for $description" }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10))
        }
    }
}
