package io.mazewall.profiler.tierE.tools

import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.ContainerEngine
import io.mazewall.testing.containers.runContainerCommand
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Separate, non-qualifying A/B/C diagnostic; never produces a reusable native phase receipt. */
public object TierENativeTimingDiagnosticRunner {
    private const val ORIGINAL_RUNNER = "tier-e-phase-a019d0fb-1203-47ea-9e5f-8c8a2ccc1f5d-runner"
    private const val ORIGINAL_SHA = "ff1e640c079de05ba407eab963cfb8939553e7f49390e60fdb20645faa3f790a"
    private const val WORKLOAD_SHA = "12ac99613bffb53253c02545063b37f01eb1860657233acf7879f90365d7c0e6"
    private const val KERNEL_CONFIG_SHA = "e75c0adab6020e72844d7431cf801a02b94ed32efc251c3b0fb80c021d065180"
    private const val IMAGE_SHA = "sha256:fc5bf5e14796e5187be873b84c1ce4049ce240415d93e4bdc8307fbbe0870c56"

    public fun run(
        root: Path,
        output: Path,
        endpoint: ContainerEndpoint,
    ) {
        execute(root, output, endpoint, readinessOnly = false)
    }

    /** Isolated, six-fork smoke of every arm/mode; never replaces the 36-fork diagnostic. */
    public fun readiness(
        root: Path,
        output: Path,
        endpoint: ContainerEndpoint,
    ) {
        execute(root, output, endpoint, readinessOnly = true)
    }

    /** The operator CLI cannot launch the 36-fork campaign without an independently complete readiness receipt. */
    public fun runWithReadiness(
        root: Path,
        output: Path,
        endpoint: ContainerEndpoint,
        readiness: Path,
    ) {
        require(endpoint.engine == ContainerEngine.DOCKER && endpoint.resolvedUri == "unix:///var/run/docker.sock") {
            "Native timing campaign requires local rootful Docker"
        }
        val source = TierESourceFingerprint.read(root.toRealPath())
        require(source.getValue("input_dirty_count").jsonPrimitive.content == "0") { "Native timing campaign source must be clean" }
        TierENativeTimingDiagnostic.requireReadiness(
            readiness,
            source.getValue("revision").jsonPrimitive.content,
            source.getValue("source_digest").jsonPrimitive.content,
        )
        execute(root, output, endpoint, readinessOnly = false)
    }

    private fun execute(
        root: Path,
        output: Path,
        endpoint: ContainerEndpoint,
        readinessOnly: Boolean,
    ) {
        require(endpoint.engine == ContainerEngine.DOCKER && endpoint.resolvedUri == "unix:///var/run/docker.sock") {
            "Native diagnostic requires the explicit local rootful Docker endpoint"
        }
        val repository = root.toRealPath()
        val pinned = TierERootfulPinnedImageProfile.load(repository.resolve("config/testing/tier-e/rootful-gate-pinned.json"))
        require(pinned.profile.cpuSet == "0,2") { "Native diagnostic CPU set differs from predeclared 0,2" }
        require(pinned.imageId == IMAGE_SHA) { "Native diagnostic image ID differs from the predeclared image" }
        require(System.getProperty("os.name") == "Linux") { "Native diagnostic requires Linux" }
        require(Files.readString(Path.of("/proc/sys/kernel/osrelease")).trim() == "7.0.0-34-generic") { "Unexpected kernel" }
        require(sha(Path.of("/boot/config-7.0.0-34-generic")) == KERNEL_CONFIG_SHA) { "Unexpected kernel config" }
        val source = TierESourceFingerprint.read(repository)
        require(source.getValue("input_dirty_count").jsonPrimitive.content == "0") { "Native diagnostic source must be clean" }
        require(!Files.exists(output) || Files.list(output).use { it.findAny().isEmpty }) { "Native diagnostic output must be fresh" }
        Files.createDirectories(output)
        val artifacts = output.toRealPath()
        val id = "tier-e-timing-${if (readinessOnly) "readiness-" else ""}${UUID.randomUUID()}"
        val schedule = if (readinessOnly) TierENativeTimingDiagnostic.readinessSchedule() else TierENativeTimingDiagnostic.schedule()
        val duration = if (readinessOnly) 20L else 45L
        val owner = TierETrustworthyContainerOwner(endpoint, id, artifacts, verifyExport = { exported ->
            TierENativeTimingDiagnostic.verifyExport(artifacts, exported.resolve("diagnostic"), schedule.size)
        })

        fun docker(
            vararg args: String,
            timeout: Duration = Duration.ofMinutes(5),
            log: Path? = null,
        ): String {
            val result = runContainerCommand(endpoint.command(*args), timeout, outputFile = log)
            check(result.exitCode == 0) { "Native diagnostic Docker command failed ${args.take(3)} (${result.exitCode}): ${result.output}" }
            return result.output.trim()
        }

        fun sensor(file: String): String = runCatching { Files.readString(Path.of(file)).trim() }.getOrElse { "unavailable" }

        fun snapshot(): Pair<String, TierENativeTimingDiagnostic.Telemetry> {
            val cpuStat = docker("exec", owner.runner, "cat", "/sys/fs/cgroup/cpu.stat")
            val cpuMax = docker("exec", owner.runner, "cat", "/sys/fs/cgroup/cpu.max")
            val cpus = docker("exec", owner.runner, "cat", "/sys/fs/cgroup/cpuset.cpus.effective")
            val pressure = sensor("/proc/pressure/cpu")
            val telemetry = TierENativeTimingDiagnostic.requireTelemetry(cpuStat, cpuMax, cpus, pressure)
            val report = buildJsonObject {
                put("utc", Instant.now().toString())
                put("cpu_stat", cpuStat)
                put("cpu_max", cpuMax)
                put("cpuset_effective", cpus)
                put("host_cpu_pressure", pressure)
                put("cpu0_khz", sensor("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq"))
                put("cpu2_khz", sensor("/sys/devices/system/cpu/cpu2/cpufreq/scaling_cur_freq"))
                put("thermal_zone0_millidegrees", sensor("/sys/class/thermal/thermal_zone0/temp"))
            }.toString()
            return report to telemetry
        }

        var failure: Throwable? = null
        try {
            val resolvedImage = docker("image", "inspect", pinned.profile.image, "--format", "{{.Id}}")
            require(resolvedImage == pinned.imageId) { "Native diagnostic image tag differs from immutable image ID" }
            val original = artifacts.resolve("original-agent.so")
            docker("cp", "$ORIGINAL_RUNNER:/workspace/profiler-native/build/libmazewall_stack_agent.so", original.toString())
            require(sha(original) == ORIGINAL_SHA) { "Original native agent differs from retained timing receipt" }
            require(runContainerCommand(endpoint.command("volume", "inspect", owner.volume)).exitCode != 0) { "Diagnostic volume already exists" }
            docker("volume", "create", owner.volume)
            owner.ownVolume()
            docker(
                "create",
                "--name",
                owner.runner,
                "--privileged",
                "--pid=host",
                "--network=host",
                "--userns=host",
                "--cpuset-cpus=0,2",
                "--security-opt",
                "seccomp=unconfined",
                "-v",
                "${owner.volume}:/workspace",
                "-v",
                "$repository:/source:ro",
                "-w",
                "/workspace",
                pinned.imageId,
                "sleep",
                "3600",
            )
            owner.ownRunner()
            docker("start", owner.runner)
            val java = docker("exec", owner.runner, "java", "-version")
            val compiler = docker("exec", owner.runner, "c++", "--version").lineSequence().first()
            TierENativeTimingDiagnostic.requireToolchain(java, compiler)
            val cmake = docker("exec", owner.runner, "cmake", "--version").lineSequence().first()
            docker("exec", owner.runner, "cmake", "-S", "/source/profiler-native", "-B", "/workspace/profiler-native/build", "-DCMAKE_BUILD_TYPE=Debug")
            docker("exec", owner.runner, "cmake", "--build", "/workspace/profiler-native/build", "-j2")
            val build = "/workspace/profiler-native/build"
            val diagnosticSha = docker("exec", owner.runner, "sha256sum", "$build/libmazewall_stack_agent.so").substringBefore(' ')
            val workload = docker("exec", owner.runner, "sha256sum", "$build/agent-test-classes/AgentWorkload.class").substringBefore(' ')
            TierENativeTimingDiagnostic.requireBinaryIdentities(sha(original), diagnosticSha, workload)
            docker("cp", original.toString(), "${owner.runner}:$build/original-agent.so")
            require(docker("exec", owner.runner, "sha256sum", "$build/original-agent.so").substringBefore(' ') == ORIGINAL_SHA) {
                "Original agent changed during diagnostic staging"
            }
            val plan = buildJsonObject {
                put("schema_version", 1)
                put("kind", if (readinessOnly) "NON_QUALIFYING_TIMING_READINESS" else "NON_QUALIFYING_TIMING_DIAGNOSTIC")
                put("run_id", id)
                put("source_revision", source.getValue("revision").jsonPrimitive.content)
                put("source_digest", source.getValue("source_digest").jsonPrimitive.content)
                put("image_id", pinned.imageId)
                put("kernel", "7.0.0-34-generic")
                put("kernel_config_sha256", KERNEL_CONFIG_SHA)
                put("cpu_set", "0,2")
                put("original_agent_sha256", ORIGINAL_SHA)
                put("diagnostic_agent_sha256", diagnosticSha)
                put("workload_class_sha256", WORKLOAD_SHA)
                put("compiler", compiler)
                put("java", java)
                put("cmake", cmake)
                put("samples", schedule.size)
                put("timeout_seconds_per_fork", 180)
                put("campaign_timeout_minutes", duration)
                put("policy_eligibility", false)
            }
            Files.writeString(artifacts.resolve("plan.json"), "$plan\n")
            Files.writeString(
                artifacts.resolve("schedule.jsonl"),
                schedule.joinToString("\n", postfix = "\n") { sample ->
                    buildJsonObject {
                        put("index", sample.index)
                        put("block", sample.block)
                        put("arm", sample.arm.toString())
                        put("mode", sample.mode)
                    }.toString()
                },
            )
            Files.writeString(
                artifacts.resolve("preflight.sha256"),
                "${sha(artifacts.resolve("plan.json"))}  plan.json\n${sha(artifacts.resolve("schedule.jsonl"))}  schedule.jsonl\n",
            )
            val started = System.nanoTime()
            val rows = mutableListOf<String>()
            for (sample in schedule) {
                check(Duration.ofNanos(System.nanoTime() - started) < Duration.ofMinutes(duration)) { "Native timing acquisition exceeded $duration-minute cap" }
                val mode = if (sample.mode == "full") "full-stream" else "unique-stack-syscall"
                val binary = if (sample.arm == 'A') "original-agent.so" else "libmazewall_stack_agent.so"
                val option = if (sample.arm == 'C') ",capture-timing-diagnostic" else ""
                val before = snapshot()
                val sampleName = "sample-${sample.index.toString().padStart(2, '0')}"
                Files.writeString(artifacts.resolve("$sampleName-before.json"), "${before.first}\n")
                val log = artifacts.resolve("$sampleName.log")
                val result = runContainerCommand(
                    endpoint.command(
                        "exec",
                        owner.runner,
                        "java",
                        "-agentpath:$build/$binary=benchmark-phases$option,mode=$mode,definitions=$build/diagnostic-${sample.index}.mzsd",
                        "-cp",
                        "$build/agent-test-classes",
                        "AgentWorkload",
                        "0",
                        "platform",
                        "100",
                    ),
                    Duration.ofMinutes(3),
                    outputFile = log,
                )
                val after = snapshot()
                Files.writeString(artifacts.resolve("$sampleName-after.json"), "${after.first}\n")
                TierENativeTimingDiagnostic.requireProgress(before.second, after.second)
                check(result.exitCode == 0) { "Diagnostic JVM sample ${sample.index} failed: ${result.exitCode}: ${result.output}" }
                val observed = TierENativeTimingDiagnostic.parse(sample.arm, sample.mode, Files.readString(log))
                rows += buildJsonObject {
                    put("index", sample.index)
                    put("block", sample.block)
                    put("arm", sample.arm.toString())
                    put("mode", sample.mode)
                    put("stack_ns", observed.stackNs)
                    put("workload_ns", observed.workloadNs)
                    put("capture_elapsed_ns", observed.captureElapsedNs)
                    put("capture_cpu_ns", observed.captureCpuNs)
                    put("clock_pair_ns", observed.clockPairNs)
                    put("before", before.first)
                    put("after", after.first)
                    put("sample_log_sha256", sha(log))
                    put("policy_eligibility", false)
                }.toString()
                Files.writeString(artifacts.resolve("observations.jsonl"), rows.joinToString("\n", postfix = "\n"))
            }
            require(rows.size == schedule.size && docker("image", "inspect", pinned.profile.image, "--format", "{{.Id}}") == pinned.imageId) {
                "Diagnostic sample count or image ID changed"
            }
            val status = if (readinessOnly) "READINESS_COMPLETE" else "DIAGNOSTIC_COMPLETE"
            Files.writeString(artifacts.resolve("diagnostic-terminal.json"), """{"status":"$status","policy_eligibility":false}""" + "\n")
            docker("exec", owner.runner, "mkdir", "-p", "/artifacts/diagnostic")
            docker("cp", "$artifacts/.", "${owner.runner}:/artifacts/diagnostic/")
            owner.export()
            owner.retire()
        } catch (caught: Throwable) {
            failure = caught
            Files.writeString(artifacts.resolve("diagnostic-terminal.json"), """{"status":"INCOMPLETE","policy_eligibility":false}""" + "\n")
            throw caught
        } finally {
            if (failure != null) {
                try {
                    owner.retainForRecovery(failure.message.orEmpty())
                } catch (cleanup: Throwable) {
                    failure.addSuppressed(cleanup)
                }
            }
        }
    }

    private fun sha(path: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
}
