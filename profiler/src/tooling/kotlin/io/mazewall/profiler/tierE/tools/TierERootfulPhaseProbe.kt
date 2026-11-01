package io.mazewall.profiler.tierE.tools

import io.mazewall.platform.fd.CleanupFailures
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
import java.util.UUID

/** Fresh native timing acquisition without the unrelated Gradle/kernel test matrix. */
public object TierERootfulPhaseProbe {
    public fun nativeBuildKey(
        source: String,
        image: String,
        compiler: String,
        java: String,
    ): String {
        val bytes = listOf("tier-e-native-debug-v1", source, image, compiler, java).joinToString("\u0000").toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    public fun run(
        root: Path,
        output: Path,
        endpoint: ContainerEndpoint,
    ) {
        runPrepared(root, output, endpoint, null, null)
    }

    public fun runWithNativePackage(
        root: Path,
        output: Path,
        endpoint: ContainerEndpoint,
        nativePackage: Path,
    ) {
        runPrepared(root, output, endpoint, nativePackage, null)
    }

    public fun runProfile(
        root: Path,
        output: Path,
        endpoint: ContainerEndpoint,
        profile: TierERootfulGateProfile,
        nativePackage: Path? = null,
    ) {
        runPrepared(root, output, endpoint, nativePackage, profile)
    }

    private fun runPrepared(
        root: Path,
        output: Path,
        endpoint: ContainerEndpoint,
        nativePackage: Path?,
        profile: TierERootfulGateProfile?,
    ) {
        require(endpoint.engine == ContainerEngine.DOCKER && endpoint.resolvedUri?.startsWith("unix://") == true) {
            "Native phase acquisition requires local rootful Docker"
        }
        val desiredCpuSet = profile?.cpuSet ?: "0,1"
        TierEAllowedCpuVerifier.requireAvailable(desiredCpuSet)
        Files.createDirectories(output)
        require(Files.list(output).use { it.findAny().isEmpty }) { "Native phase acquisition requires fresh artifacts" }
        val artifacts = output.toRealPath()
        val repository = root.toRealPath()
        val sourceBeforeTransfer = TierESourceFingerprint.read(repository, null)
        val sourceBeforeDigest = sourceBeforeTransfer.getValue("source_digest").jsonPrimitive.content
        val sourceRevision = sourceBeforeTransfer.getValue("revision").jsonPrimitive.content
        val id = "tier-e-phase-${UUID.randomUUID()}"
        val owner = TierETrustworthyContainerOwner(endpoint, id, artifacts, verifyExport = { result ->
            val exported = result.resolve("exported-native-phase")
            require(
                Files.isRegularFile(exported.resolve("phase-full-verdict.txt")) &&
                Files.isRegularFile(exported.resolve("phase-unique-verdict.txt")) &&
                Files.isRegularFile(exported.resolve("native-identity.json")),
            ) { "Native phase export incomplete" }
            require(Files.readAllBytes(result.resolve("native-identity.json")).contentEquals(Files.readAllBytes(exported.resolve("native-identity.json")))) {
                "Native phase identity changed during export"
            }
        })

        fun docker(
            vararg args: String,
            timeout: Duration = Duration.ofMinutes(5),
            log: Path? = null,
        ): String {
            val result = runContainerCommand(endpoint.command(*args), timeout, outputFile = log)
            check(result.exitCode == 0) { "Native phase Docker command failed: ${args.take(3)} (${result.exitCode}): ${result.output}" }
            return result.output.trim()
        }
        TierEMatrixTerminal.run(artifacts) {
            var primary: Throwable? = null
            try {
                val image = profile?.image ?: "mazewall-tier-e-rootful:latest"
                val cpuSet = desiredCpuSet
                val imageId = docker("image", "inspect", image, "--format", "{{.Id}}")
                require(imageId.startsWith("sha256:")) { "Native phase image identity unavailable" }
                check(runContainerCommand(endpoint.command("volume", "inspect", owner.volume)).exitCode != 0)
                docker("volume", "create", owner.volume)
                owner.ownVolume()
                val tar = Files.createTempFile("tier-e-phase-source-", ".tar")
                try {
                    TierERootfulSourceTransfer.archive(repository, tar)
                    val transfer = ProcessBuilder(
                        endpoint.command(
                        "run",
                            "--rm",
                            "-i",
                            "--name",
                            "$id-transfer",
                            "-v",
                            "${owner.volume}:/workspace",
                        "--entrypoint",
                            "tar",
                            image,
                            "-C",
                            "/workspace",
                            "-xf",
                            "-",
                    ),
                    ).redirectInput(tar.toFile()).redirectErrorStream(true).start()
                    val message = transfer.inputStream.bufferedReader().use { it.readText() }
                    check(transfer.waitFor(5, java.util.concurrent.TimeUnit.MINUTES) && transfer.exitValue() == 0) {
                        "Native phase source transfer failed: $message"
                    }
                } finally {
                    Files.deleteIfExists(tar)
                    runContainerCommand(endpoint.command("rm", "-f", "$id-transfer"))
                }
                val source = TierESourceFingerprint
                    .read(repository, null)
                    .getValue("source_digest")
                    .jsonPrimitive.content
                require(source == sourceBeforeDigest) { "Native phase source changed during transfer" }
                val toolchain = docker("run", "--rm", "--entrypoint", "c++", image, "--version").lineSequence().first()
                val java = docker("run", "--rm", "--entrypoint", "java", image, "-version")
                val buildKey = nativeBuildKey(source, imageId, toolchain, java)
                val buildCache = "mazewall-tier-e-phase-build-${buildKey.take(24)}"
                val verifiedPackage = nativePackage?.let { packageRoot ->
                    val cmake = docker("run", "--rm", "--entrypoint", "cmake", image, "--version").lineSequence().first()
                    val architecture = docker("image", "inspect", image, "--format", "{{.Architecture}}")
                    TierENativeArtifactPackage.verify(
                        packageRoot,
                        repository.resolve("profiler-native"),
                        TierENativeArtifactPackage.Expected(imageId, "Debug", false, toolchain, java, cmake, architecture),
                    )
                }
                if (verifiedPackage == null) docker("volume", "create", buildCache)
                docker(
                    "create",
                    "--name",
                    owner.runner,
                    "--privileged",
                    "--pid=host",
                    "--network=host",
                    "--userns=host",
                    "--cpuset-cpus=$cpuSet",
                    "--security-opt",
                    "seccomp=unconfined",
                    "-v",
                    "${owner.volume}:/workspace",
                    *(
                        if (verifiedPackage == null) {
                        arrayOf("-v", "$buildCache:/workspace/profiler-native/build")
                    } else {
                        arrayOf("-v", "${requireNotNull(nativePackage).toRealPath()}:/native-package:ro")
                    }
                    ),
                    "-w",
                    "/workspace",
                    image,
                    "sleep",
                    "3600",
                )
                owner.ownRunner()
                docker("start", owner.runner)
                docker("exec", owner.runner, "mkdir", "-p", "/artifacts")
                val build = "/workspace/profiler-native/build"
                docker("exec", owner.runner, "cmake", "-S", "profiler-native", "-B", build, "-DCMAKE_BUILD_TYPE=Debug")
                if (verifiedPackage == null) {
                    docker("exec", owner.runner, "cmake", "--build", build, "-j2")
                } else {
                    for (command in TierENativeArtifactPackage.installCommands(owner.runner)) docker(*command.toTypedArray())
                }
                val identity = docker("exec", owner.runner, "sha256sum", "$build/libmazewall_stack_agent.so")
                    .substringBefore(' ')
                val workloadClass = docker("exec", owner.runner, "sha256sum", "$build/agent-test-classes/AgentWorkload.class")
                    .substringBefore(' ')
                require(verifiedPackage == null || identity == verifiedPackage.agentSha256 && workloadClass == verifiedPackage.workloadClassSha256) {
                    "Native cached bytes changed during phase staging"
                }
                Files.writeString(
                    artifacts.resolve("native-identity.json"),
                    buildJsonObject {
                    put("schema_version", 1)
                    put("source_digest", source)
                    put("source_revision", sourceRevision)
                    put("image_id", imageId)
                    put("agent_sha256", identity)
                    put("workload_class_sha256", workloadClass)
                    put("native_build_key", buildKey)
                    put("compiler", toolchain)
                    put("java", java)
                    put("native_build_cache", if (verifiedPackage == null) buildCache else "gradle:${verifiedPackage.sourceDigest}")
                    put("cpu_set", cpuSet)
                    put("run_id", id)
                    put("policy_eligibility", false)
                }.toString() +
                    "\n",
                )
                val publishedIdentity = docker("exec", owner.runner, "sha256sum", "$build/libmazewall_stack_agent.so")
                    .substringBefore(' ')
                check(publishedIdentity == identity) { "Native binary changed during acquisition" }
                val acquisitionFailures = mutableListOf<String>()
                for (mode in listOf("full", "unique")) {
                    for (sample in 1..3) {
                        val result = runContainerCommand(
                            endpoint.command(
                            "exec",
                                owner.runner,
                                "ctest",
                                "--test-dir",
                                build,
                                "-V",
                            "-R",
                                "^agent_phase_comparable_$mode$",
                        ),
                            Duration.ofMinutes(3),
                            outputFile = artifacts.resolve("$mode-$sample.log"),
                        )
                        if (result.exitCode != 0) acquisitionFailures += "$mode-$sample"
                    }
                }
                val replay = TierERootfulPhaseEvidence.verify(
                    artifacts,
                    repository.resolve("profiler-native/tier-e-benchmark-baseline.env"),
                )
                val publish = listOf("native-identity.json", "phase-full-verdict.txt", "phase-unique-verdict.txt") +
                    listOf("full", "unique").flatMap { mode -> (1..3).map { "$mode-$it.log" } }
                docker("exec", owner.runner, "mkdir", "-p", "/artifacts/exported-native-phase")
                for (file in publish) {
                    docker("cp", artifacts.resolve(file).toString(), "${owner.runner}:/artifacts/exported-native-phase/$file")
                }
                check(acquisitionFailures.isEmpty() && replay.isSuccess) {
                    "Native phase incomplete: $acquisitionFailures ${replay.exceptionOrNull()?.message.orEmpty()}"
                }
            } catch (failure: Throwable) {
                primary = failure
            } finally {
                try {
                    owner.finish(primary == null)
                } catch (cleanup: Throwable) {
                    primary = CleanupFailures.merge(primary, cleanup)
                }
            }
            primary?.let { throw it }
        }
    }
}
