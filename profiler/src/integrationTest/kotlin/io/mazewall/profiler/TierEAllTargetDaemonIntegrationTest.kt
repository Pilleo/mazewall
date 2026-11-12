package io.mazewall.profiler.api

import io.mazewall.platform.fd.CleanupFailures
import io.mazewall.profiler.internal.attribution.AgentDefinitionFile
import io.mazewall.profiler.internal.attribution.SessionEpoch
import io.mazewall.profiler.testing.tierE.DiagnosticAttempt
import io.mazewall.profiler.testing.tierE.DiagnosticOutcome
import io.mazewall.profiler.testing.tierE.DiagnosticReadyFacts
import io.mazewall.profiler.testing.tierE.DiagnosticStderr
import io.mazewall.profiler.testing.tierE.TierEA4DiagnosticSupport
import io.mazewall.profiler.testing.tierE.TierEInstalledFixtureSafety
import io.mazewall.profiler.testing.tierE.TracerIdentity
import io.mazewall.profiler.tierE.engine.decodeBoundaryDiagnostics
import io.mazewall.profiler.tierE.tools.controller.TierEContainerRun
import io.mazewall.profiler.tierE.tools.controller.TierEFixtureController
import io.mazewall.profiler.tierE.tools.controller.TierEFixtureLifecycle
import io.mazewall.testing.awaitCondition
import io.mazewall.testing.containers.ContainerCommandResult
import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.runContainerCommand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Called only by the owned rootful lane, never satisfied by optional host assets. */
class TierEAllTargetDaemonIntegrationTest {
    internal fun runInstalledDiagnostic(
        endpoint: ContainerEndpoint,
        repository: Path,
        native: Path,
        image: String,
    ) {
        val destination = Path.of(requireNotNull(System.getenv("TIER_E_QUALIFICATION_ARTIFACTS"))).toAbsolutePath()
        check(System.getenv("TIER_E_A4_DIAGNOSTIC_ARM") == "1")
        TierEA4DiagnosticSupport.withPreparedAttempt(destination) { attempt ->
            runDiagnosticAttempt(endpoint, repository, native, image, destination, attempt)
        }
    }

    private fun runDiagnosticAttempt(
        endpoint: ContainerEndpoint,
        repository: Path,
        native: Path,
        image: String,
        destination: Path,
        attempt: DiagnosticAttempt,
    ) {
        check(System.getenv("TIER_E_CONTAINER_ENGINE") == "docker" && System.getenv("MAZEWALL_DOCKER_HOST") == "unix:///var/run/docker.sock")
        check(endpoint.resolvedUri == "unix:///var/run/docker.sock")
        check(image == "mazewall-tier-e-rootful@sha256:8047b78d7af43aa5fa9dcfd61d5bbdcf47044e0a154221b2e8a3ba28c4fa02e1") { "diagnostic image is not the approved immutable reference" }
        TierEA4DiagnosticSupport.copyNativeProvenance(repository, native, destination)
        check(Files.isRegularFile(native.resolve("libmazewall_stack_agent.so"), NOFOLLOW_LINKS))
        check(Files.isRegularFile(native.resolve("agent-test-classes/TierEAllTargetWorkload.class"), NOFOLLOW_LINKS))
        check(Files.isDirectory(repository.resolve("profiler/build/install/tier-e-daemon/lib"), NOFOLLOW_LINKS))
        val inspected = runContainerCommand(endpoint.command("image", "inspect", "--format", "{{.Id}}", image), Duration.ofSeconds(10), outputFile = destination.resolve("prebuilt-image.txt"))
        check(inspected.exitCode == 0) { "prebuilt image unavailable; no pull/build permitted" }
        val pinnedImage = Files.readString(destination.resolve("prebuilt-image.txt")).trim().also { check(it.matches(Regex("sha256:[a-f0-9]{64}"))) }
        val runId = "a4diag-${UUID.randomUUID()}"
        val shared = Files.createTempDirectory("tier-e-owned-$runId-")
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxr-xr-x"))
        val lock = destination.resolve("creation.lock")
        Files.writeString(destination.resolve("creation.lock.state"), "open\n", CREATE_NEW)
        val fixture = InstalledFixture(endpoint, repository, native, pinnedImage, runId, shared, destination, lock, diagnostic = true, attempt = attempt)
        val owner = TierEContainerRun(endpoint, runId, destination, ownedShared = shared)
        TierEFixtureLifecycle(owner, lock, settle = { _, _ -> fixture.settleDiagnosticCreation() }, extraCleanup = fixture::recoveryReceipt, retainOnFailure = true, retainOnSuccess = true).run {
            TierEA4DiagnosticSupport.finishDiagnosticAttempt(
                exercise = { fixture.exercise("group") },
                quiesce = fixture::quiesceDiagnostic,
                retain = fixture::retainDiagnostic,
                classify = fixture::classifyDiagnostic,
                publish = { join, retained -> TierEA4DiagnosticSupport.publishDiagnosticOutcome(destination, join, retained) },
            )
        }
    }

    internal fun runInstalled(
        endpoint: ContainerEndpoint,
        repository: Path,
        native: Path,
        image: String,
        scenario: String,
    ) {
        val artifacts = requireNotNull(System.getenv("TIER_E_QUALIFICATION_ARTIFACTS")) {
            "Set TIER_E_QUALIFICATION_ARTIFACTS to a fresh retained artifact directory"
        }.let(Path::of).toAbsolutePath()
        val runId = "a4c-$scenario-${UUID.randomUUID()}"
        val destination = Files.createDirectories(artifacts.resolve(runId))
        val shared = Files.createTempDirectory("tier-e-owned-$runId-")
        Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxr-xr-x"))
        val lock = destination.resolve("creation.lock")
        Files.writeString(destination.resolve("creation.lock.state"), "open\n")
        val fixture = InstalledFixture(endpoint, repository, native, image, runId, shared, destination, lock)
        val owner = TierEContainerRun(endpoint, runId, destination, ownedShared = shared)
        var retirementAuthorized = false
        TierEFixtureLifecycle(owner, lock, extraCleanup = {
            if (retirementAuthorized && !Files.exists(shared)) {
                Files.writeString(destination.resolve("cleanup.txt"), fixture.retirementReceipt())
            } else {
                fixture.recoveryReceipt()
            }
        }, retainOnFailure = true).run {
            TierEInstalledFixtureSafety.finish({ fixture.exercise(scenario) }, fixture::quiesce, fixture::retain, fixture::restoreScratchOwner)
            retirementAuthorized = true
        }
    }

    private class InstalledFixture(
        private val endpoint: ContainerEndpoint,
        private val repository: Path,
        private val native: Path,
        private val image: String,
        private val runId: String,
        private val shared: Path,
        private val destination: Path,
        private val creationLock: Path,
        private val diagnostic: Boolean = false,
        private val attempt: DiagnosticAttempt? = null,
    ) {
        private val collector = "$runId-collector"
        private val target = "$runId-target"
        private val bin = "/installed/bin"
        private val agent = native.resolve("libmazewall_stack_agent.so").toString()
        private var sequence = 0
        private var daemon: Process? = null
        private var daemonLaunchAttempted = false
        private var daemonIdentity: TierEInstalledFixtureSafety.DaemonStartupIdentity? = null
        private var targetPid: Long? = null
        private var daemonPid: Long? = null
        private var collectorId: String? = null
        private var targetId: String? = null
        private var collectorCgroup: String? = null
        private var artifactsCreated = false
        private var tracer: Process? = null
        private var tracerLaunchAttempted = false
        private var tracerIdentity: TracerIdentity? = null
        private var targetStart: Long? = null
        private var targetCgroup: String? = null
        private var tracerStderr: DiagnosticStderr? = null
        private var writersStopped = false
        private var creationClosed = false
        private var oracleComplete = false
        private var diagnosticAttachReply: String? = null

        private fun command(
            vararg args: String,
            success: Boolean = true,
        ): ContainerCommandResult {
            val invocation = endpoint.command(*args)
            val guarded = if (args.firstOrNull() == "run") {
                listOf(
                    "flock",
                    "-x",
                    creationLock.toString(),
                    "bash",
                    "-ec",
                    "[[ \$(cat \"\$1\") == open ]]; shift; exec \"\$@\"",
                    "--",
                    "$creationLock.state",
                ) +
                invocation
            } else {
                invocation
            }
            val number = sequence++
            val output = destination.resolve("command-$number.output")
            if (diagnostic) Files.writeString(destination.resolve("command-$number.txt"), "PENDING\n${guarded.joinToString("\n")}\n")
            val summary = runContainerCommand(guarded, Duration.ofSeconds(45), outputFile = output)
            // Endpoint probe summaries are capped at 4096 characters, not evidence transport.
            val result = summary.copy(output = Files.readString(output))
            Files.writeString(destination.resolve("command-$number.txt"), "${args.joinToString(" ")}\nexit=${result.exitCode}\n${result.output}")
            if (success) assertEquals(0, result.exitCode, result.output)
            return result
        }

        private fun exec(
            vararg args: String,
            success: Boolean = true,
        ): ContainerCommandResult = command("exec", collector, *args, success = success)

        fun exercise(scenario: String) {
            val group = scenario.startsWith("group")
            val acl = scenario.endsWith("-acl")
            Files.createDirectory(shared.resolve("evidence"))
            Files.createDirectory(shared.resolve("work"))
            Files.writeString(shared.resolve("work/input"), "example")
            collectorId = command(
                "run",
                "-d",
                "--name",
                collector,
                "--privileged",
                "--pid=host",
                "--cgroupns=host",
                "--network=none",
                "--mount",
                "type=bind,source=$shared,target=/fixture",
                "--mount",
                "type=bind,source=$native,target=$native,readonly",
                "--mount",
                "type=bind,source=${repository.resolve("profiler/build/install/tier-e-daemon")},target=/installed,readonly",
                "--entrypoint",
                "sleep",
                image,
                "infinity",
            ).output.trim().also { check(it.matches(Regex("[a-f0-9]{64}"))) }
            val initPid = command("inspect", "--format", "{{.State.Pid}}", requireNotNull(collectorId)).output.trim().toLong()
            collectorCgroup = Files.readString(Path.of("/proc/$initPid/cgroup")).trim().also {
                check(it.contains(requireNotNull(collectorId))) { "collector cgroup does not bind its immutable owned container ID" }
            }
            exec("sh", "-ec", "chown 20000:20000 /fixture/work; chmod 755 /fixture/work /fixture/evidence")
            if (acl) {
                val setup = runContainerCommand(listOf("setfacl", "-m", "d:u:20001:r--,d:m::r--", shared.resolve("evidence").toString()))
                assertEquals(0, setup.exitCode, setup.output)
            }
            val beforeAcl = directoryAcl()
            Files.writeString(destination.resolve("directory-acl-before.txt"), beforeAcl)
            if (acl) {
                exec("sh", "-ec", "umask 077; echo probe > /fixture/evidence/acl-probe; chown 0:20002 /fixture/evidence/acl-probe; chmod 640 /fixture/evidence/acl-probe")
                reader("20001:20004", listOf("/fixture/evidence/acl-probe"), allowed = true, parse = false)
                exec("rm", "/fixture/evidence/acl-probe")
            }
            targetId = command(
                "run",
                "-d",
                "--name",
                target,
                "--user",
                "20000:20000",
                "--cap-drop=ALL",
                "--security-opt",
                "no-new-privileges",
                "--network=none",
                "--mount",
                "type=bind,source=$shared,target=/fixture",
                "--mount",
                "type=bind,source=$native,target=$native,readonly",
                "--entrypoint",
                if (diagnostic) "sh" else "java",
                image,
                *if (diagnostic) arrayOf("-ec", "umask 077; exec java \"\$@\"", "--") else emptyArray(),
                "-agentpath:$agent=session=4d5a,session-bound-definitions,definitions=/fixture/work/definitions.mzse,mode=full-stream" +
                    if (diagnostic) ",capture-yield=/fixture/work/capture-yield.mzcy,test-open-boundary=/fixture/work/open-calls.diag" else "",
                "-cp",
                native.resolve("agent-test-classes").toString(),
                "TierEAllTargetWorkload",
                "/fixture/work/first.gate",
                "/fixture/work/second.gate",
                "/fixture/work/ready",
                "/fixture/work/input",
                "/fixture/work/done",
            ).output.trim().also { check(it.matches(Regex("[a-f0-9]{64}"))) }
            val pid = command("inspect", "--format", "{{.State.Pid}}", target).output.trim().toLong()
            targetPid = pid
            val proc = exec("cat", "/proc/$pid/stat").output
            Files.writeString(destination.resolve("target-stat.txt"), proc)
            val start = proc.substringAfterLast(") ").split(' ')[19]
            if (diagnostic) {
                targetStart = start.toLong()
                targetCgroup = exec("cat", "/proc/$pid/cgroup").output.trim()
                check(requireNotNull(targetCgroup).contains(requireNotNull(targetId)))
            }
            val status = exec("cat", "/proc/$pid/status").output
            assertTrue(status.contains(Regex("NSpid:\\s+$pid\\s+1")), status)
            assertTrue(status.contains(Regex("Uid:\\s+20000\\s+20000")), status)
            assertTrue(status.contains("CapEff:\t0000000000000000"), status)
            awaitCondition(Duration.ofSeconds(20), "collector sees actual mapped agent") {
                exec("cat", "/proc/$pid/maps").output.contains(agent)
            }
            val mappedInode = exec("stat", "-Lc", "%d:%i", "/proc/$pid/root$agent").output.trim()
            assertEquals(mappedInode, exec("stat", "-Lc", "%d:%i", agent).output.trim(), "collector and target must map the same agent inode")
            exec("readlink", "/proc/$pid/exe", "/proc/$pid/ns/pid", "/proc/self/ns/pid")
            exec(
                "$bin/tier-e-target-helper",
                "register",
                "--registration-id",
                "orders",
                "--pid",
                "$pid",
                "--marker-path",
                agent,
                "--session-tag",
                "4d5a",
                "--artifact-file",
                "definitions.mzse",
                "--output",
                "/fixture/target.reg",
            )
            val registration = exec("cat", "/fixture/target.reg").output
            assertTrue(registration.contains("process_incarnation=$start\n"), registration)
            assertTrue(registration.startsWith("version=1\n"), registration)
            if (diagnostic) {
                TierEA4DiagnosticSupport.writeStartupRegistration(destination, registration)
            } else {
                Files.writeString(destination.resolve("target.reg"), registration)
            }
            val environment = mutableListOf(
                "TIER_E_SOCKET=/fixture/control.sock",
                "TIER_E_TARGET_REGISTRATION=/fixture/target.reg",
                "TIER_E_DEFINITION_ROOT=/fixture/work",
                "TIER_E_EVIDENCE_ROOT=/fixture/evidence",
                "TIER_E_ALL_TARGET_CAPTURE=1",
                "TIER_E_SOCKET_GID=20002",
            )
            if (group) environment += "TIER_E_EVIDENCE_GID=20002"
            if (diagnostic) environment += "TIER_E_TEST_BOUNDARY_DIAGNOSTICS=1"
            daemonLaunchAttempted = true
            daemon = ProcessBuilder(
                endpoint
                    .command(
                    "exec",
                    collector,
                    "env",
                    *environment.toTypedArray(),
                    "sh",
                    "-ec",
                    "echo \$\$ > /fixture/daemon.pid; cat /proc/\$\$/stat > /fixture/daemon.stat; cat /proc/\$\$/cgroup > /fixture/daemon.cgroup; exec $bin/tier-e-daemon",
                ).also { argv -> if (diagnostic) Files.writeString(destination.resolve("daemon.command"), argv.joinToString("\n") + "\n") },
            ).redirectErrorStream(true)
                .redirectOutput(destination.resolve("daemon.log").toFile())
                .start()
            if (diagnostic) recordExecClient("daemon", requireNotNull(daemon))
            awaitCondition(Duration.ofSeconds(20), "installed daemon socket") {
                assertTrue(requireNotNull(daemon).isAlive, Files.readString(destination.resolve("daemon.log")))
                Files.exists(shared.resolve("control.sock"))
            }
            daemonPid = Files.readString(shared.resolve("daemon.pid")).trim().toLong()
            val daemonStart = Files
                .readString(shared.resolve("daemon.stat"))
                .substringAfterLast(") ")
                .split(' ')[19]
                .toLong()
            assertEquals(collectorCgroup, Files.readString(shared.resolve("daemon.cgroup")).trim(), "exec-preserved collector must belong to owned container cgroup")
            daemonIdentity = TierEInstalledFixtureSafety.DaemonStartupIdentity(requireNotNull(daemonPid), daemonStart, requireNotNull(collectorCgroup))
            // Real installed clients with valid local files but mismatching v1 wire identities.
            for ((name, contents) in listOf(
                "wrong-session" to registration.replace("session_tag=4d5a", "session_tag=4d5b"),
                "namespace-pid" to registration
                    .replace("pid=$pid\n", "pid=1\n")
                    .replace("process_incarnation=$start\n", "process_incarnation=${exec("cat", "/proc/1/stat").output.substringAfterLast(") ").split(' ')[19]}\n"),
            )) {
                Files.writeString(shared.resolve("$name.reg"), contents)
                val denied = exec("$bin/tier-e-target-helper", "attach", "--registration", "/fixture/$name.reg", "--socket", "/fixture/control.sock", success = false)
                assertTrue(denied.exitCode != 0 && denied.output.contains("ERR"), denied.output)
                assertFalse(Files.exists(shared.resolve("work/first.gate")))
                assertFalse(Files.exists(shared.resolve("evidence/orders-19802.events.jsonl")))
            }
            val attached = exec("$bin/tier-e-target-helper", "attach", "--registration", "/fixture/target.reg", "--socket", "/fixture/control.sock", success = !diagnostic)
            if (diagnostic) {
                diagnosticAttachReply = attached.output
                check(attached.exitCode == 0 && attached.output.startsWith("OK")) { "missing successful ATTACH acknowledgement: ${attached.output}" }
            }
            if (diagnostic) prepareAndArmTracer() else exec("touch", "/fixture/work/first.gate")
            awaitCondition(Duration.ofSeconds(30), "independent first-stage oracle") {
                if (diagnostic) checkOracleLive()
                Files.exists(shared.resolve("work/ready")) && exec("cat", "/fixture/work/ready").output == "attributed-complete"
            }
            assertEquals("attributed-complete", exec("cat", "/fixture/work/ready").output)
            exec("touch", "/fixture/work/second.gate")
            assertEquals("0", command("wait", target).output.trim())
            assertEquals("completed", exec("cat", "/fixture/work/done").output)
            if (diagnostic) finishOracle()
            awaitCondition(Duration.ofSeconds(40), "both final evidence files") {
                assertTrue(requireNotNull(daemon).isAlive, "daemon exited before evidence")
                Files.exists(shared.resolve("evidence/orders-19802.events.jsonl")) && Files.exists(shared.resolve("evidence/orders-19802.tier-e.json"))
            }
            artifactsCreated = true
            val paths = listOf("/fixture/evidence/orders-19802.events.jsonl", "/fixture/evidence/orders-19802.tier-e.json")
            val events = exec("cat", paths[0]).output
            val terminal = exec("cat", paths[1]).output
            Files.writeString(destination.resolve("orders-19802.events.jsonl"), events)
            Files.writeString(destination.resolve("orders-19802.tier-e.json"), terminal)
            verifyEvents(events, terminal)
            val metadata = exec("stat", "-c", "%u:%g:%a %n", *paths.toTypedArray()).output
            assertTrue(metadata.lineSequence().filter(String::isNotBlank).all { it.startsWith(if (group) "0:20002:640 " else "0:0:600 ") }, metadata)
            reader("20003:20002", paths, allowed = group)
            reader("20001:20004", paths, allowed = false)
            val accessAcl = runContainerCommand(listOf("getfacl", "-cpn") + paths.map { shared.resolve(it.removePrefix("/fixture/")).toString() })
            assertEquals(0, accessAcl.exitCode, accessAcl.output)
            Files.writeString(destination.resolve("final-access-acl.txt"), accessAcl.output)
            if (group) assertFalse(accessAcl.output.contains("user:20001:"), "selected-group finals must remove named-user access")
            assertEquals(beforeAcl, directoryAcl(), "publication must not mutate directory default ACL")
            Files.writeString(destination.resolve("directory-acl-after.txt"), directoryAcl())
            // Retirement is physical, not inferred from signal delivery or a bounded wait.
            awaitCondition(Duration.ofSeconds(10), "collector native descriptors retired") {
                exec("sh", "-ec", "ls -l /proc/$daemonPid/fd").output.let { !it.contains("bpf") && !it.contains("perf_event") }
            }
            stopDaemon()
            assertFalse(Files.exists(Path.of("/proc/$pid")), "target still exists after container wait")
        }

        private fun directoryAcl(): String {
            val result = runContainerCommand(listOf("getfacl", "-cpn", shared.resolve("evidence").toString()))
            assertEquals(0, result.exitCode, result.output)
            return result.output
        }

        private fun closeCreation() {
            FileChannel.open(creationLock, CREATE, WRITE).use { channel ->
                channel.lock().use {
                    TierEFixtureController.awaitCreationQuiescence(null, creationLock)
                    creationClosed = true
                }
            }
        }

        fun settleDiagnosticCreation() {
            if (!creationClosed) closeCreation()
            check(Files.readString(Path.of("$creationLock.state")).trim() == "closed")
        }

        private fun tracerRow(text: String): TracerIdentity {
            val row = Json.parseToJsonElement(text).jsonObject
            check(row.keys == setOf("pid", "startTicks", "cgroup", "containerId", "targetPid", "targetStartTicks"))

            fun number(key: String) =
                row
                .getValue(key)
                .jsonPrimitive.content
                .toLong()
                .also { check(it > 0) }
            return TracerIdentity(
                number("pid"),
                number("startTicks"),
                row.getValue("cgroup").jsonPrimitive.content,
                row.getValue("containerId").jsonPrimitive.content,
                number("targetPid"),
                number("targetStartTicks"),
            )
        }

        private fun actualTracer(expected: TracerIdentity): TracerIdentity {
            val result =
                exec(
                    "python3",
                    "-c",
                    """
                import json, pathlib, sys
                pid=int(sys.argv[1])
                stat=pathlib.Path('/proc/'+str(pid)+'/stat').read_text()
                fields=stat.rsplit(') ',1)[1].split()
                assert fields[0] not in ('Z','X','x')
                print(json.dumps(dict(pid=pid,startTicks=int(fields[19]),cgroup=pathlib.Path('/proc/'+str(pid)+'/cgroup').read_text().strip(),containerId=sys.argv[2],targetPid=int(sys.argv[3]),targetStartTicks=int(sys.argv[4]))))
            """.trimIndent(),
                        "${expected.pid}",
                    expected.containerId,
                    "${expected.targetPid}",
                    "${expected.targetStartTicks}",
                )
            return tracerRow(result.output)
        }

        private fun copyTracerStderr(process: Process) {
            tracerStderr = DiagnosticStderr(process, destination.resolve("tracer.stderr"))
        }

        private fun requireCurrentTarget() {
            val pid = requireNotNull(targetPid)
            val fields = exec("cat", "/proc/$pid/stat").output.substringAfterLast(") ").split(' ')
            check(fields[0] !in setOf("Z", "X", "x") && fields[19].toLong() == targetStart)
            check(exec("cat", "/proc/$pid/cgroup").output.trim() == targetCgroup)
            check(command("inspect", "--format", "{{.State.Running}} {{.State.Pid}}", requireNotNull(targetId)).output.trim() == "true $pid")
        }

        private fun recordExecClient(
            name: String,
            process: Process,
        ) {
            val pid = process.pid()
            val stat = Files.readString(Path.of("/proc/$pid/stat"))
            val start = stat.substringAfterLast(") ").split(' ')[19].toLong()
            val cgroup = Files.readString(Path.of("/proc/$pid/cgroup")).trim()
            check(start > 0 && cgroup.isNotEmpty())
            Files.writeString(destination.resolve("$name-exec-client.txt"), "pid=$pid start_ticks=$start cgroup=$cgroup\ncollector_id=$collectorId target_pid=$targetPid target_start=$targetStart\n")
        }

        private fun checkOracleLive() {
            requireNotNull(tracerStderr).checkFailure()
            check(
                !TierEA4DiagnosticSupport.hasTextLine(destination.resolve("tracer.stderr"), 65536) {
                !TierEA4DiagnosticSupport.oracleStderrLineHealthy(it)
            },
            ) { "tracer reports an error/unsupported diagnostic before ARM" }
            val process = requireNotNull(tracer)
            check(process.isAlive) { "tracer exec client exited before target: ${process.exitValue()}" }
            val size = exec("stat", "-c", "%s", "/fixture/evidence/ptrace-open.log").output.trim().toLong()
            check(TierEA4DiagnosticSupport.oracleHealthy(Files.size(destination.resolve("tracer.stderr")), size, null)) { "oracle size limit reached" }
        }

        private fun prepareAndArmTracer() {
            val pid = requireNotNull(targetPid)
            check(exec("readlink", "/proc/self/ns/pid").output.trim() == Files.readSymbolicLink(Path.of("/proc/self/ns/pid")).toString()) { "collector oracle TIDs are not proved host TIDs" }
            val source = requireNotNull(javaClass.getResource("/tier-e-a4-tracer.py")).readText()
            Files.writeString(shared.resolve("tracer-launcher.py"), source, CREATE_NEW)
            try {
                tracerIdentity = TierEA4DiagnosticSupport.captureStartup(creationLock, launch = {
                    tracerLaunchAttempted = true
                    val argv = endpoint.command(
                        "exec",
                        requireNotNull(collectorId),
                        "python3",
                        "/fixture/tracer-launcher.py",
                        "$pid",
                        "${requireNotNull(targetStart)}",
                        requireNotNull(targetCgroup),
                        requireNotNull(collectorId),
                        "/fixture/tracer.startup",
                    )
                    Files.writeString(destination.resolve("tracer.command"), argv.joinToString("\n") + "\n")
                    ProcessBuilder(argv).redirectOutput(destination.resolve("tracer.stdout").toFile()).start()
                }, adopt = { process ->
                    tracer = process
                    copyTracerStderr(process)
                    recordExecClient("tracer", process)
                }, observe = {
                        awaitCondition(Duration.ofSeconds(20), "owned tracer startup identity") {
                            check(requireNotNull(tracer).isAlive) { "tracer launcher exited" }
                            requireNotNull(tracerStderr).checkFailure()
                            Files.exists(shared.resolve("tracer.startup"), NOFOLLOW_LINKS)
                        }
                        tracerRow(exec("cat", "/fixture/tracer.startup").output).also {
                            check(it.containerId == collectorId && it.cgroup == collectorCgroup && it.targetPid == pid && it.targetStartTicks == targetStart)
                            check(it == actualTracer(it))
                        }
                })
            } catch (failure: Throwable) {
                Files.writeString(destination.resolve("tracer-launch-unresolved.txt"), "exec client retained; stop exact owned collector before any complete export\n${failure.message}\n")
                throw failure
            }
            val identity = requireNotNull(tracerIdentity)
            awaitCondition(Duration.ofSeconds(20), "pinned ptrace attach before cold workers") {
                requireNotNull(tracerStderr).checkFailure()
                check(requireNotNull(tracer).isAlive) { "tracer launcher exited" }
                if (!Files.exists(shared.resolve("evidence/ptrace-open.log"), NOFOLLOW_LINKS) || !Files.exists(destination.resolve("tracer.stderr"))) {
                    false
                } else {
                    checkOracleLive()
                    exec("cat", "/proc/$pid/status").output.contains(Regex("TracerPid:\\s+${identity.pid}(?:\\s|$)")) &&
                        TierEA4DiagnosticSupport.hasTextLine(destination.resolve("tracer.stderr"), 65536) { it.contains("Process $pid attached") }
                }
            }
            check(identity == actualTracer(identity))
            val cmdline = exec(
                "python3",
                "-c",
                "import pathlib,sys; print('\\n'.join(pathlib.Path('/proc/'+sys.argv[1]+'/cmdline').read_bytes().decode('ascii').rstrip('\\0').split('\\0')))",
                "${identity.pid}",
            ).output.trim().lines()
            check(cmdline == TierEA4DiagnosticSupport.tracerArgv(pid)) { "actual tracer argv differs" }
            val limits = exec("cat", "/proc/${identity.pid}/limits").output
            check(limits.contains(Regex("Max file size\\s+16777216\\s+16777216\\s+bytes")))
            val targetTime = exec("cat", "/proc/$pid/timens_offsets").output
            val tracerTime = exec("cat", "/proc/${identity.pid}/timens_offsets").output
            check(targetTime == tracerTime && targetTime.contains(Regex("monotonic\\s+0\\s+0"))) { "native/BPF monotonic time namespace unproved" }
            freezeFingerprints()
            val daemonStartup = requireNotNull(daemonIdentity)
            check(requireNotNull(daemon).isAlive)
            check(
                exec("cat", "/proc/${daemonStartup.pid}/stat")
                .output
                .substringAfterLast(") ")
                .split(' ')[19]
                .toLong() == daemonStartup.start,
            )
            check(exec("cat", "/proc/${daemonStartup.pid}/cgroup").output.trim() == daemonStartup.cgroup)
            val log = destination.resolve("daemon.log")
            check(!TierEA4DiagnosticSupport.hasTextLine(log, 65536) { it.startsWith("tier-e-boundary-control:") }) { "competing STATUS requester" }
            val watermark = Files.size(log)
            FileChannel.open(log, READ).use { channel ->
                val last = ByteBuffer.allocate(1)
                check(watermark > 0)
                channel.position(watermark - 1)
                check(channel.read(last) == 1 && last.array()[0] == '\n'.code.toByte()) { "partial pre-request log line" }
            }
            val reply = exec(
                "python3",
                "-c",
                """
                import socket, struct
                def exact(s,n):
                    b=b''
                    while len(b)<n:
                        part=s.recv(n-len(b))
                        if not part: raise RuntimeError('STATUS EOF')
                        b+=part
                    return b
                with socket.socket(socket.AF_UNIX,socket.SOCK_STREAM) as s:
                    s.settimeout(10)
                    s.connect('/fixture/control.sock')
                    s.sendall(struct.pack('>H',6)+b'STATUS')
                    length=struct.unpack('>H',exact(s,2))[0]
                    assert length<=4096
                    print(exact(s,length).decode('ascii'))
            """.trimIndent(),
            ).output.trim()
            val newLines = FileChannel.open(log, READ).use { channel ->
                check(channel.size() - watermark in 1..4096) { "missing/oversized STATUS receipt interval" }
                val bytes = ByteBuffer.allocate((channel.size() - watermark).toInt())
                channel.position(watermark)
                while (bytes.hasRemaining()) check(channel.read(bytes) > 0)
                String(bytes.array(), Charsets.US_ASCII).also { check(it.endsWith('\n')) }.lines()
            }
            val receipt = TierEA4DiagnosticSupport.liveReceipt(newLines, reply, pid, 19802)
            check(receipt.ordinal == 1L) { "unexpected previous STATUS request" }
            Files.writeString(
                destination.resolve("ready-control.txt"),
                "watermark=$watermark daemon=$daemonStartup\nreply=$reply\n${newLines.joinToString("\n")}\ninstallation=${receipt.installation} ordinal=${receipt.ordinal}\n",
            )
            checkOracleLive()
            requireCurrentTarget()
            check(requireNotNull(daemon).isAlive)
            check(!TierEA4DiagnosticSupport.hasTextLine(destination.resolve("tracer.stderr"), 65536, requireTerminated = true) { !TierEA4DiagnosticSupport.oracleStderrLineHealthy(it) })
            val facts = DiagnosticReadyFacts(
                attached = diagnosticAttachReply != null,
                gateClosed = !Files.exists(shared.resolve("work/first.gate")),
                receiptsAllocated = true,
                identityPinned = identity == actualTracer(identity),
                tracerPidMatches = exec("cat", "/proc/$pid/status").output.contains(Regex("TracerPid:\\s+${identity.pid}(?:\\s|$)")),
                tracerAlive = Files.exists(Path.of("/proc/${identity.pid}")),
                followsClones = cmdline.contains("-f"),
                limitsVerified = true,
                oracleHealthy = true,
                sameTimeNamespace = targetTime == tracerTime,
                fingerprintsComplete = false,
            )
            FileChannel.open(creationLock, CREATE, WRITE).use { channel ->
                channel.lock().use {
                    TierEA4DiagnosticSupport.publishReadyAndArm(
                        requireNotNull(attempt),
                        facts,
                        repository,
                        native,
                        destination,
                        shared.resolve("work/input"),
                        mapOf(
                            "target" to "pid=$pid start=${requireNotNull(targetStart)} cgroup=${requireNotNull(targetCgroup)}",
                            "daemon" to daemonStartup.toString(),
                            "tracer" to identity.toString(),
                            "target_container" to requireNotNull(targetId),
                            "collector_container" to requireNotNull(collectorId),
                            "image" to image,
                            "live_control" to Files.readString(destination.resolve("ready-control.txt")),
                            "attach" to requireNotNull(diagnosticAttachReply),
                            "tracer_argv" to cmdline.joinToString("\n"),
                            "time_namespace" to targetTime,
                        ),
                        { Files.readString(Path.of("$creationLock.state")).trim() == "open" },
                    ) {
                        exec("touch", "/fixture/work/first.gate")
                    }
                }
            }
        }

        private fun freezeFingerprints() {
            TierEA4DiagnosticSupport.checkNativeProvenance(repository, native, destination)
            val cache = Files.readString(native.resolve("CMakeCache.txt"))
            check(cache.contains("CMAKE_HOME_DIRECTORY:INTERNAL=${repository.resolve("profiler-native")}"))
            val buildFields = cache
                .lineSequence()
                .filter {
                it.matches(Regex("(?:CMAKE_HOME_DIRECTORY|CMAKE_BUILD_TYPE|CMAKE_CXX_COMPILER|CMAKE_CXX_FLAGS(?:_[A-Z]+)?|MAZEWALL_ENABLE_TIER_E_USDT):[^=]+=.*"))
            }.joinToString("\n")
            Files.writeString(destination.resolve("native-build-settings.txt"), "$buildFields\n", CREATE_NEW)
            val compiler = cache.lineSequence().single { it.startsWith("CMAKE_CXX_COMPILER:FILEPATH=") }.substringAfter('=')
            val compilerResult = runContainerCommand(listOf(compiler, "--version"), Duration.ofSeconds(5), outputFile = destination.resolve("native-compiler-version.txt"))
            check(compilerResult.exitCode == 0) { "native compiler fingerprint unavailable" }
            command("image", "inspect", "--format", "{{.Id}} {{json .RepoDigests}}", image)
            exec("strace", "-V")
            exec("strace", "-h")
            exec("java", "-version")
            exec("uname", "-srvmo")
            exec("sha256sum", agent, "/usr/bin/strace", "/sys/kernel/btf/vmlinux")
            exec("sha256sum", native.resolve("agent-test-classes/TierEAllTargetWorkload.class").toString())
            val tracerPid = requireNotNull(tracerIdentity).pid
            check(exec("sha256sum", "/proc/$tracerPid/exe").output.substringBefore(' ') == exec("sha256sum", "/usr/bin/strace").output.substringBefore(' '))
            check(exec("readelf", "-n", agent).output.contains(Regex("Build ID: [a-f0-9]+"))) { "native ELF build identity unavailable" }
            val sourcePaths = TierEA4DiagnosticSupport.diagnosticSources
            Files.writeString(destination.resolve("source-sha256.txt"), sourcePaths.joinToString("\n") { "${hash(repository.resolve(it))}  $it" } + "\n")
            val activeLsm = Files.readString(Path.of("/sys/kernel/security/lsm")).trim()
            val fingerprints = exec(
                "python3",
                "-c",
                requireNotNull(javaClass.getResource("/tier-e-a4-fingerprints.py")).readText(),
                "${requireNotNull(targetPid)}",
                agent,
                "${requireNotNull(daemonPid)}",
                "$tracerPid",
                activeLsm,
            ).output
            Files.writeString(destination.resolve("runtime-fingerprints.txt"), fingerprints)
            check(
                TierEA4DiagnosticSupport.hasTextLine(destination.resolve("daemon.log"), 65536) {
                it.startsWith("tier-e-boundary-ready:") &&
                it.contains(Regex("main_sha256=[a-f0-9]{64}")) &&
                it.contains(Regex("marker_sha256=[a-f0-9]{64}"))
            },
            )
            check(exec("sha256sum", agent).output.substringBefore(' ') == exec("sha256sum", "/proc/${requireNotNull(targetPid)}/root$agent").output.substringBefore(' '))
        }

        private fun finishOracle() {
            val process = requireNotNull(tracer)
            check(process.waitFor(15, TimeUnit.SECONDS)) { "tracer exec client did not exit naturally" }
            requireNotNull(tracerStderr).finish()
            val identity = requireNotNull(tracerIdentity)
            check(TierEA4DiagnosticSupport.tracerStopped(!process.isAlive, Files.notExists(Path.of("/proc/${identity.pid}")))) { "tracer physical stop unproved" }
            val bytes = exec("stat", "-c", "%s", "/fixture/evidence/ptrace-open.log").output.trim().toLong()
            check(TierEA4DiagnosticSupport.oracleHealthy(Files.size(destination.resolve("tracer.stderr")), bytes, process.exitValue()))
            check(
                !TierEA4DiagnosticSupport.hasTextLine(destination.resolve("tracer.stderr"), 65536, requireTerminated = true) {
                it.contains(Regex("(?i)file size limit|write.*error|no space|detached|killed|failed|operation not permitted|permission denied"))
            },
            )
            Files.writeString(destination.resolve("tracer-exit.txt"), "exit=${process.exitValue()} pinned physical absence verified\n")
            oracleComplete = true
        }

        fun quiesceDiagnostic() {
            closeCreation()
            var primary: Throwable? = null
            for (effect in listOf<() -> Unit>({ quiesce() }, { stopTracer() })) {
                try {
                    effect()
                } catch (failure: Throwable) {
                    primary = CleanupFailures.merge(primary, failure)
                }
            }
            if (primary == null) {
                writersStopped = true
                Files.writeString(destination.resolve("writer-closure.txt"), "target/daemon/tracer and tracked exec clients absent; creation closed\n")
            }
            primary?.let { throw it }
        }

        private fun stopTracer() {
            if (!tracerLaunchAttempted) return
            val identity = tracerIdentity
            if (identity == null) {
                command("stop", "-t", "2", requireNotNull(collectorId))
                check(command("inspect", "--format", "{{.State.Running}}", requireNotNull(collectorId)).output.trim() == "false")
                tracer?.let { check(it.waitFor(15, TimeUnit.SECONDS)) }
                error("tracer startup unresolved; exact collector stopped; no complete writer proof")
            }
            TierEInstalledFixtureSafety.stopDaemon(
                true,
                TierEInstalledFixtureSafety.DaemonStartupIdentity(identity.pid, identity.startTicks, identity.cgroup),
                tracer,
                absent = { Files.notExists(Path.of("/proc/${it.pid}")) },
                terminate = {
                    TierEA4DiagnosticSupport.stopPinned(identity, actualTracer(identity)) {
                        exec("python3", "-c", TierEInstalledFixtureSafety.script("pidfd-stop"), "${identity.pid}", "${identity.startTicks}", identity.cgroup)
                    }
                    awaitCondition(Duration.ofSeconds(15), "pinned tracer physical stop") { Files.notExists(Path.of("/proc/${identity.pid}")) }
                },
            )
            requireNotNull(tracerStderr).finish()
        }

        private fun hash(path: Path): String {
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n == -1) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun retainDiagnostic() {
            Files.writeString(destination.resolve("export-status.txt"), "INCOMPLETE writersStopped=$writersStopped creationClosed=$creationClosed source=$shared\n")
            command("logs", requireNotNull(targetId), success = false)
            command("logs", requireNotNull(collectorId), success = false)
            val listing = exec(
                "python3",
                "-c",
                """
                import os, stat
                files=[]
                for root,dirs,names in os.walk('/fixture',followlinks=False):
                    for name in sorted(names):
                        path=os.path.join(root,name)
                        assert '\n' not in path
                        mode=os.lstat(path).st_mode
                        if stat.S_ISSOCK(mode):
                            assert path=='/fixture/control.sock'
                            continue
                        files.append(path)
                        assert len(files)<=256
                print('\n'.join(files))
            """.trimIndent(),
            ).output.lines().filter(String::isNotBlank)
            val required =
                setOf(
                    "/fixture/target.reg",
                    "/fixture/daemon.pid",
                    "/fixture/daemon.stat",
                    "/fixture/daemon.cgroup",
                    "/fixture/tracer.startup",
                    "/fixture/work/definitions.mzse",
                    "/fixture/work/capture-yield.mzcy",
                    "/fixture/work/open-calls.diag",
                    "/fixture/evidence/boundary.bpfdiag",
                    "/fixture/evidence/ptrace-open.log",
                    "/fixture/evidence/orders-19802.events.jsonl",
                    "/fixture/evidence/orders-19802.tier-e.json",
                    "/fixture/work/input",
                    "/fixture/work/first.gate",
                    "/fixture/work/second.gate",
                    "/fixture/work/ready",
                    "/fixture/work/done",
                ) +
                if (diagnosticAttachReply?.contains("BPF_PROG_LOAD failed") == true) setOf("/fixture/evidence/prog-load-failure.diag") else emptySet()
            var primary: Throwable? = null
            for (path in (listing + required).distinct()) {
                try {
                    exportDiagnosticFile(path)
                } catch (failure: Throwable) {
                    primary = CleanupFailures.merge(primary, failure)
                }
            }
            if (!writersStopped || !creationClosed) {
                val failure = IllegalStateException("bounded partial export only; writer/creation closure unproved")
                primary = CleanupFailures.merge(primary, failure)
            }
            if (diagnosticAttachReply?.contains("BPF_PROG_LOAD failed") == true) {
                primary = CleanupFailures.merge(primary, IllegalStateException("BPF_PROG_LOAD failed; retained capture is incomplete causal evidence"))
            }
            primary?.let { throw it }
            val kernel = decodeBoundaryDiagnostics(Files.readAllBytes(destination.resolve("evidence-boundary.bpfdiag")))
            check(kernel.complete) { "kernel quiescence/loss/commit export incomplete" }
            Files.writeString(destination.resolve("export-status.txt"), "COMPLETE writer and creation closure; kernel quiescent/zero-loss/reconciled; source retained=$shared\n")
        }

        private fun exportDiagnosticFile(path: String) {
            check(path.startsWith("/fixture/") && !path.contains(".."))
            val name = path.removePrefix("/fixture/").replace('/', '-')
            val encoded = destination.resolve("$name.encoded.tmp")
            val argv = endpoint.command("exec", collector, "python3", "-c", TierEInstalledFixtureSafety.script("export"), path)
            val receipt = destination.resolve("$name.export-command.txt")
            Files.writeString(receipt, "PENDING\n${argv.joinToString("\n")}\n", CREATE_NEW)
            val result = runContainerCommand(argv, Duration.ofSeconds(45), outputFile = encoded)
            Files.writeString(receipt, "exit=${result.exitCode} output=${encoded.fileName}\n", java.nio.file.StandardOpenOption.APPEND)
            val output = destination.resolve(name)
            val maximum = TierEA4DiagnosticSupport.diagnosticArtifactMaximum(path)
            TierEA4DiagnosticSupport.retainEncodedArtifact(result.exitCode, encoded, output, maximum)
            Files.writeString(destination.resolve("$name.sha256"), "${hash(output)} length=${Files.size(output)} byte-compared\n")
        }

        fun classifyDiagnostic(): io.mazewall.profiler.testing.tierE.DiagnosticJoin {
            check(writersStopped && creationClosed && oracleComplete)
            val dictionary = AgentDefinitionFile.readForSession(destination.resolve("work-definitions.mzse"), SessionEpoch(19802))
            val yield = io.mazewall.profiler.tierE.stress.TierECaptureYield
                .read(destination.resolve("work-capture-yield.mzcy"))
            val methods = TierEA4DiagnosticSupport.selectColdMethods(dictionary, yield)
            val native = TierEA4DiagnosticSupport.decodeNativeCalls(Files.readAllBytes(destination.resolve("work-open-calls.diag")))
            val inputHex = "/fixture/work/input".toByteArray(Charsets.US_ASCII).joinToString("") { "%02x".format(it) }
            check(native.calls.filter { it.invocation in methods }.all { it.pathHex == inputHex })
            val yieldIds = yield.records.map { it.invocationId }
            check(yieldIds.toSet().size == yieldIds.size && methods.keys.all { it in yieldIds })
            check(
                native.calls.filter { it.invocation in methods }.all { call ->
                dictionary.invocations
                    .single { it.invocationId.value == call.invocation }
                    .executionContext
                    ?.executionId
                    ?.value ==
                call.execution
            },
            )
            val daemonLog = Files.readString(destination.resolve("daemon.log"))
            val targetLog = command("logs", requireNotNull(targetId)).output
            check(targetLog.contains("capture-yield=written capture-yield-dropped=0") && targetLog.contains("open-boundary=written"))
            check(dictionary.agentLosses == 0L && !daemonLog.contains("tier-e-finalization-failure"))
            val trace = Files.readAllLines(destination.resolve("evidence-ptrace-open.log"))
            check(
                Files.newByteChannel(destination.resolve("evidence-ptrace-open.log"), READ).use { channel ->
                val last = ByteBuffer.allocate(1)
                channel.position(channel.size() - 1)
                channel.read(last)
                last.array()[0] == '\n'.code.toByte()
            },
            )
            return TierEA4DiagnosticSupport.classifyRetainedProduction(
                native,
                decodeBoundaryDiagnostics(Files.readAllBytes(destination.resolve("evidence-boundary.bpfdiag"))),
                trace,
                methods,
                Files.readAllLines(destination.resolve("evidence-orders-19802.events.jsonl")),
                Files.readString(destination.resolve("evidence-orders-19802.tier-e.json")),
                requireNotNull(targetPid),
                requireNotNull(targetStart),
                destination.resolve("work-definitions.mzse"),
            )
        }

        private fun reader(
            identity: String,
            paths: List<String>,
            allowed: Boolean,
            parse: Boolean = true,
        ) {
            val program = """
                import os, json
                print('uid=',os.getuid(),'gid=',os.getgid(),'groups=',os.getgroups(),flush=True)
                status=open('/proc/self/status').read()
                print(status,flush=True)
                print('uid_map=',open('/proc/self/uid_map').read(),'gid_map=',open('/proc/self/gid_map').read(),flush=True)
                assert os.getuid() == ${identity.substringBefore(':')}
                assert os.getgid() == ${identity.substringAfter(':')}
                assert os.getgroups() == []
                for cap in ['CapEff:', 'CapPrm:', 'CapBnd:', 'CapInh:', 'CapAmb:']:
                    assert int(next(x.split()[1] for x in status.splitlines() if x.startswith(cap)),16)==0
                for path in ${paths.joinToString(prefix = "[", postfix = "]") { "'$it'" }}:
                    print('traversal-ok',os.listdir(os.path.dirname(path)),flush=True)
                    try:
                        data=open(path).read()
                    except PermissionError as e:
                        print(path,repr(e),flush=True)
                        assert ${if (allowed) "False" else "True"}
                    else:
                        assert ${if (allowed) "True" else "False"}, 'unexpected read: '+path
                        if ${if (parse) "True" else "False"}:
                            for line in data.splitlines(): json.loads(line)
                        print(path,'read-and-parse-ok',flush=True)
            """.trimIndent()
            val result =
                command(
                    "exec",
                    collector,
                    "setpriv",
                    "--reuid=${identity.substringBefore(':')}",
                    "--regid=${identity.substringAfter(':')}",
                    "--clear-groups",
                    "--bounding-set=-all",
                    "--inh-caps=-all",
                    "--ambient-caps=-all",
                    "--no-new-privs",
                    "python3",
                    "-c",
                    program,
                )
            Files.writeString(destination.resolve("reader-$identity-$sequence.txt"), result.output)
        }

        private fun verifyEvents(
            events: String,
            terminal: String,
        ) {
            val rows = events
                .lineSequence()
                .filter(String::isNotBlank)
                .map { Json.parseToJsonElement(it).jsonObject }
                .toList()
            val footer = rows.last()
            for (field in listOf("event_count", "capture_admitted_count", "capture_submitted_count")) {
                assertEquals((rows.size - 2).toString(), footer.getValue(field).jsonPrimitive.content, field)
            }
            for (field in listOf("capture_complete", "export_complete", "transport_complete")) assertEquals("true", footer.getValue(field).jsonPrimitive.content, field)
            assertEquals("COMPLETE", footer.getValue("terminal_reason").jsonPrimitive.content)
            assertEquals("0", footer.getValue("observed_loss_count").jsonPrimitive.content)
            assertEquals("0", footer.getValue("marker_state_unavailable_count").jsonPrimitive.content)
            assertEquals("false", footer.getValue("policy_eligibility").jsonPrimitive.content)
            assertTrue(rows.any { it["resolution_status"]?.jsonPrimitive?.content == "NO_ACTIVE_INVOCATION" })
            val resolved = rows.filter { it["resolution_status"]?.jsonPrimitive?.content == "RESOLVED" }
            assertEquals(resolved.size.toString(), footer.getValue("attributed_count").jsonPrimitive.content)
            assertTrue(rows.drop(1).dropLast(1).all { it.getValue("resolution_status").jsonPrimitive.content in setOf("RESOLVED", "NO_ACTIVE_INVOCATION") })
            for (method in listOf("readConfiguration", "loadTemplate")) {
                assertEquals(
                    20,
                    resolved.count { row ->
                        row.getValue("syscall_number").jsonPrimitive.content == "257" &&
                            row.getValue("frames").jsonArray.any {
                                it.jsonObject
                                    .getValue("method_name")
                                    .jsonPrimitive.content == method
                            }
                    },
                    "independent workload declares exactly 20 openat calls through $method",
                )
            }
            val report = Json.parseToJsonElement(terminal).jsonObject
            assertEquals("COMPLETE", report.getValue("terminal_reason").jsonPrimitive.content)
            assertEquals("false", report.getValue("policy_eligibility").jsonPrimitive.content)
            assertEquals(targetPid.toString(), report.getValue("target_pid").jsonPrimitive.content)
            for (field in listOf("capture_domain_validity", "transport_completeness", "dictionary_integrity", "lifecycle_validity")) {
                assertEquals("true", report.getValue(field).jsonPrimitive.content, field)
            }
            assertEquals("false", report.getValue("attribution_coverage").jsonPrimitive.content)
            assertEquals("INCOMPLETE", report.getValue("evidence_integrity").jsonPrimitive.content)
            val artifact = exec("sha256sum", "/fixture/work/definitions.mzse").output.substringBefore(' ')
            assertEquals(artifact, report.getValue("artifact_sha256").jsonPrimitive.content)
            assertEquals(exec("stat", "-c", "%s", "/fixture/work/definitions.mzse").output.trim(), report.getValue("artifact_byte_length").jsonPrimitive.content)
        }

        fun retain() {
            for (name in listOf("target", "collector")) command("inspect", "$runId-$name")
            exec("sh", "-ec", "id; java -version; uname -a; cat /proc/self/status; find /fixture -maxdepth 3 -printf '%m %U:%G %p\\n'")
            command("image", "inspect", image)
            for (name in listOf("target.reg", "daemon.pid", "daemon.stat", "daemon.cgroup", "work/definitions.mzse", "evidence/orders-19802.events.jsonl", "evidence/orders-19802.tier-e.json")) {
                val bytes = exec("python3", "-c", TierEInstalledFixtureSafety.script("export"), "/fixture/$name", success = false)
                TierEInstalledFixtureSafety.retainArtifact(bytes, artifactsCreated, destination.resolve(name.replace('/', '-')))
            }
            exec("sha256sum", agent, "/installed/bin/tier-e-target-helper", "/installed/bin/tier-e-daemon")
            exec("ps", "-eo", "pid,ppid,uid,gid,stat,args")
        }

        fun restoreScratchOwner() {
            val uid = Files.getAttribute(shared, "unix:uid")
            val gid = Files.getAttribute(shared, "unix:gid")
            exec("chown", "-R", "$uid:$gid", "/fixture")
        }

        fun quiesce() {
            val id = targetId
            if (id != null) {
                command("stop", "-t", "2", id)
                assertEquals("false", command("inspect", "--format", "{{.State.Running}}", id).output.trim(), "target stop must be verified")
                targetPid?.let { assertFalse(Files.exists(Path.of("/proc/$it")), "target still exists after stop") }
            } else {
                val absent = command("inspect", target, success = false)
                check(absent.exitCode != 0 && absent.output.contains("no such", ignoreCase = true)) { "target creation/retirement is unresolved" }
            }
            stopDaemon()
        }

        private fun stopDaemon() {
            TierEInstalledFixtureSafety.stopDaemon(
                daemonLaunchAttempted,
                daemonIdentity,
                daemon,
                absent = { Files.notExists(Path.of("/proc/${it.pid}")) },
                terminate = { identity ->
                    check(identity.cgroup.contains(requireNotNull(collectorId)))
                    exec("python3", "-c", TierEInstalledFixtureSafety.script("pidfd-stop"), "${identity.pid}", "${identity.start}", identity.cgroup)
                    awaitCondition(Duration.ofSeconds(15), "physical daemon retirement after pinned signal") {
                        Files.notExists(Path.of("/proc/${identity.pid}"))
                    }
                },
            )
        }

        fun recoveryReceipt() {
            val location = "retained source=$shared target=$target target_id=$targetId collector=$collector collector_id=$collectorId; " +
                "creation closure and stop-for-recovery are separate from verified writer stop/export; no retirement authorized\n"
            System.err.print(location)
            Files.writeString(destination.resolve("recovery.txt"), location)
            for (name in listOf(target, collector)) {
                if (diagnostic) command("inspect", "--format", "{{.Id}} {{.Name}} {{.State.Running}} {{.State.Pid}}", name, success = false) else command("inspect", name, success = false)
            }
        }

        fun retirementReceipt(): String {
            for (name in listOf(target, collector)) assertTrue(command("inspect", name, success = false).exitCode != 0, "owned container remains: $name")
            assertFalse(Files.exists(shared), "owned scratch remains")
            for (pid in listOfNotNull(targetPid, daemonPid)) assertFalse(Files.exists(Path.of("/proc/$pid")), "owned process remains: $pid")
            return "containers absent; shared scratch absent; recorded target=$targetPid collector=$daemonPid absent from host /proc; creation closed\n"
        }
    }
}
