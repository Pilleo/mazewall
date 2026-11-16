package io.mazewall.profiler.api

import io.mazewall.profiler.internal.attribution.AgentDefinitionFile
import io.mazewall.profiler.internal.attribution.SessionEpoch
import io.mazewall.profiler.testing.tierE.TierEInstalledFixtureSafety
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
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Called only by the owned rootful lane, never satisfied by optional host assets. */
class TierEAllTargetDaemonIntegrationTest {
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
                "java",
                image,
                "-agentpath:$agent=session=4d5a,session-bound-definitions,definitions=/fixture/work/definitions.mzse,mode=full-stream",
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
            Files.writeString(destination.resolve("target.reg"), registration)
            val environment = mutableListOf(
                "TIER_E_SOCKET=/fixture/control.sock",
                "TIER_E_TARGET_REGISTRATION=/fixture/target.reg",
                "TIER_E_DEFINITION_ROOT=/fixture/work",
                "TIER_E_EVIDENCE_ROOT=/fixture/evidence",
                "TIER_E_ALL_TARGET_CAPTURE=1",
                "TIER_E_SOCKET_GID=20002",
            )
            if (group) environment += "TIER_E_EVIDENCE_GID=20002"
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
                ),
            ).redirectErrorStream(true)
                .redirectOutput(destination.resolve("daemon.log").toFile())
                .start()
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
            exec("$bin/tier-e-target-helper", "attach", "--registration", "/fixture/target.reg", "--socket", "/fixture/control.sock")
            exec("touch", "/fixture/work/first.gate")
            awaitCondition(Duration.ofSeconds(30), "independent first-stage oracle") {
                Files.exists(shared.resolve("work/ready")) && exec("cat", "/fixture/work/ready").output == "attributed-complete"
            }
            assertEquals("attributed-complete", exec("cat", "/fixture/work/ready").output)
            exec("touch", "/fixture/work/second.gate")
            assertEquals("0", command("wait", target).output.trim())
            assertEquals("completed", exec("cat", "/fixture/work/done").output)
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
                }
            }
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
                command("inspect", name, success = false)
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
