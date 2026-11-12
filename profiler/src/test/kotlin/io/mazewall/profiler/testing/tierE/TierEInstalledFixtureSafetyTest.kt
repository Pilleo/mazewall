package io.mazewall.profiler.testing.tierE

import io.mazewall.enforcer.api.EnabledIfLinuxAndSupported
import io.mazewall.profiler.tierE.tools.controller.TierEContainerRun
import io.mazewall.profiler.tierE.tools.controller.TierEFixtureLifecycle
import io.mazewall.testing.containers.ContainerCommandResult
import io.mazewall.testing.containers.ContainerEndpoint
import io.mazewall.testing.containers.ContainerEngine
import io.mazewall.testing.containers.runContainerCommand
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@EnabledIfLinuxAndSupported
class TierEInstalledFixtureSafetyTest {
    @TempDir lateinit var root: Path

    private val sourceBytes = "only source dictionary".toByteArray()
    private val encoded: ContainerCommandResult
        get() = ContainerCommandResult(0, Base64.getEncoder().encodeToString(sourceBytes))

    private fun retainedFailure(
        quiesce: () -> Unit = {},
        export: () -> Unit,
        primary: Throwable? = null,
    ): Throwable {
        val runId = "tier-e-fault-123"
        val scratch = Files.createTempDirectory("tier-e-owned-$runId-")
        val source = scratch.resolve("dictionary")
        Files.write(source, sourceBytes)
        val commands = mutableListOf<String>()
        var recoveryExported = false
        var restored = false
        var creationClosed = false
        val removed = mutableSetOf<String>()
        val owner = TierEContainerRun(ContainerEndpoint(ContainerEngine.DOCKER, null), runId, root, ownedShared = scratch) { command, _, output ->
            val operation = command[1]
            commands += operation
            if (operation == "rm") assertTrue(recoveryExported && creationClosed, "no deletion before verified recovery export and creation closure")
            when (operation) {
                "inspect" -> when {
                    command.contains("--format") -> ContainerCommandResult(0, "false")
                    command.last() in removed -> ContainerCommandResult(1, "No such object")
                    else -> ContainerCommandResult(0, "owned")
                }
                "stop" -> ContainerCommandResult(0, "stopped")
                "logs" -> {
                    Files.writeString(requireNotNull(output), "owned log")
                    ContainerCommandResult(0, "")
                }
                "rm" -> {
                    removed += command.last()
                    ContainerCommandResult(0, "removed")
                }
                else -> error("unexpected operation $command")
            }
        }
        try {
            val failure = assertFailsWith<Throwable> {
                TierEFixtureLifecycle(
                    owner,
                    root.resolve("creation.lock"),
                    settle = { _, _ -> creationClosed = true },
                    retainOnFailure = true,
                    extraCleanup = { Files.writeString(root.resolve("recovery.txt"), "retained source=$scratch") },
                ).run {
                    TierEInstalledFixtureSafety.finish(
                        exercise = { primary?.let { throw it } },
                        quiesce = quiesce,
                        export = export,
                        restore = { restored = true },
                    )
                }
            }
            assertFalse(restored, "failed qualification must not mutate source ownership")
            assertFalse(commands.contains("rm"))
            assertEquals(listOf("inspect", "stop", "inspect", "stop"), commands)
            assertContentEquals(sourceBytes, Files.readAllBytes(source))
            assertTrue(Files.readString(root.resolve("recovery.txt")).contains(scratch.toString()))
            assertFalse(Files.exists(root.resolve("cleanup.txt")), "retained source must not receive a successful cleanup receipt")
            // Explicit, verified recovery export precedes the unchanged owner's destructive retirement.
            TierEInstalledFixtureSafety.retainArtifact(encoded, true, root.resolve("recovered"))
            assertContentEquals(sourceBytes, Files.readAllBytes(root.resolve("recovered")))
            recoveryExported = true
            owner.close()
            assertFalse(Files.exists(scratch))
            return failure
        } finally {
            // Only this host test's allocated tree; no container or writer exists in these injected cases.
            scratch.toFile().deleteRecursively()
        }
    }

    @Test fun `failed target stop retains exact source and does not export or chown`() {
        val stop = IOException("target stop failed")
        val original = IllegalStateException("capture failure")
        var exported = false
        val failure = retainedFailure(quiesce = { throw stop }, export = { exported = true }, primary = original)
        assertFalse(exported, "quiescence failure must prohibit export")
        assertSame(original, failure)
        assertTrue(failure.suppressed.contains(stop))
    }

    private fun terminatedClient(): Process =
        ProcessBuilder("true").start().also {
            assertTrue(it.waitFor(5, TimeUnit.SECONDS))
            assertFalse(it.isAlive)
        }

    @Test fun `dead exec client without startup identity fails actual stop decision and retains source`() {
        val client = terminatedClient()
        val original = IOException("readiness failed after exec transport loss")
        var exported = false
        val failure = retainedFailure(quiesce = {
            TierEInstalledFixtureSafety.stopDaemon(
                true,
                null,
                client,
                absent = { error("missing identity cannot authorize a late process observation") },
                terminate = { error("missing identity must not signal") },
            )
        }, export = { exported = true }, primary = original)
        assertFalse(exported)
        assertSame(original, failure)
        assertTrue(failure.suppressed.any { it.message.orEmpty().contains("startup identity unavailable") })
        assertFalse(Files.exists(root.resolve("cleanup.txt")))
    }

    @Test fun `attempted launch with no acquired client still requires startup identity`() {
        val failure = assertFailsWith<IllegalStateException> {
            TierEInstalledFixtureSafety.stopDaemon(
                true,
                null,
                null,
                absent = { error("must not observe an arbitrary late PID") },
                terminate = { error("must not signal") },
            )
        }
        assertTrue(failure.message.orEmpty().contains("startup identity unavailable"))
    }

    @Test fun `no daemon launch attempt needs no identity or stop effects`() {
        TierEInstalledFixtureSafety.stopDaemon(
            false,
            null,
            null,
            absent = { error("no daemon to observe") },
            terminate = { error("no daemon to signal") },
        )
    }

    @Test fun `known startup identity with verified absence needs no signal despite dead client`() {
        val identity = TierEInstalledFixtureSafety.DaemonStartupIdentity(123, 987, "0::/owned/id")
        var observations = 0
        TierEInstalledFixtureSafety.stopDaemon(true, identity, terminatedClient(), absent = {
            assertSame(identity, it)
            observations++
            true
        }, terminate = { error("verified absent daemon needs no signal") })
        assertEquals(1, observations)
    }

    @Test fun `dead client with known live daemon still requires pinned stop and physical absence`() {
        val identity = TierEInstalledFixtureSafety.DaemonStartupIdentity(123, 987, "0::/owned/id")
        var physicallyAbsent = false
        val order = mutableListOf<String>()
        TierEInstalledFixtureSafety.stopDaemon(true, identity, terminatedClient(), absent = {
            assertSame(identity, it)
            order += "absence check"
            physicallyAbsent
        }, terminate = {
            assertSame(identity, it)
            order += "pinned stop"
            physicallyAbsent = true
        })
        assertEquals(listOf("absence check", "pinned stop", "absence check"), order)
    }

    @Test fun `known identity and dead client cannot authorize export while daemon remains present`() {
        val identity = TierEInstalledFixtureSafety.DaemonStartupIdentity(123, 987, "0::/owned/id")
        var exported = false
        var stops = 0
        val failure = retainedFailure(quiesce = {
            TierEInstalledFixtureSafety.stopDaemon(true, identity, terminatedClient(), absent = { false }, terminate = { stops++ })
        }, export = { exported = true })
        assertFalse(exported)
        assertEquals(1, stops)
        assertTrue(failure.message.orEmpty().contains("physical daemon absence"))
    }

    @Test fun `unretired collector retains source without export or ownership mutation`() {
        val stop = IOException("collector still alive after timeout")
        var exported = false
        assertSame(stop, retainedFailure(quiesce = { throw stop }, export = { exported = true }))
        assertFalse(exported, "unretired writer must prohibit export")
    }

    @Test fun `required base64 nonzero fails qualification and retains source until recovery export`() {
        val failure = retainedFailure(export = {
            TierEInstalledFixtureSafety.retainArtifact(ContainerCommandResult(1, "base64: read error: Input/output error"), true, root.resolve("lost"))
        })
        assertTrue(failure.message.orEmpty().contains("Input/output error"))
    }

    @Test fun `local export destination write failure retains source and actual cause`() {
        val denied = IOException("destination write denied")
        assertSame(
            denied,
            retainedFailure(export = {
                TierEInstalledFixtureSafety.retainArtifact(encoded, true, root.resolve("lost")) { _, _ -> throw denied }
            }),
        )
    }

    @Test fun `false successful local write is rejected before source retirement`() {
        retainedFailure(export = {
            TierEInstalledFixtureSafety.retainArtifact(encoded, true, root.resolve("missing")) { _, _ -> }
        })
    }

    @Test fun `only ENOENT before creation is optional and every required export is checked`() {
        TierEInstalledFixtureSafety.retainArtifact(ContainerCommandResult(44, ""), false, root.resolve("not-created"))
        assertFalse(Files.exists(root.resolve("not-created")))
        for (result in listOf(ContainerCommandResult(44, "ENOENT"), ContainerCommandResult(1, "EACCES"), ContainerCommandResult(1, "EIO"))) {
            assertFailsWith<IllegalStateException> { TierEInstalledFixtureSafety.retainArtifact(result, true, root.resolve("required")) }
        }
        assertFailsWith<IllegalStateException> { TierEInstalledFixtureSafety.retainArtifact(ContainerCommandResult(1, "EACCES"), false, root.resolve("optional")) }
    }

    @Test fun `successful export verifies exact bytes before ownership restoration`() {
        val order = mutableListOf<String>()
        TierEInstalledFixtureSafety.finish({ order += "exercise" }, { order += "stopped" }, {
            TierEInstalledFixtureSafety.retainArtifact(encoded, true, root.resolve("copy"))
            order += "verified export"
        }, {
            assertContentEquals(sourceBytes, Files.readAllBytes(root.resolve("copy")))
            order += "restore"
        })
        assertEquals(listOf("exercise", "stopped", "verified export", "restore"), order)
    }

    @Test fun `fatal export takes precedence and retains original qualification failure`() {
        val original = IOException("capture failed")
        val fatal = LinkageError("fatal export")
        assertSame(fatal, retainedFailure(export = { throw fatal }, primary = original))
        assertTrue(fatal.suppressed.contains(original))
    }

    @Test fun `fatal quiescence prevents every later effect and retains original failure`() {
        val original = IOException("capture failed")
        val fatal = LinkageError("fatal quiescence")
        var exported = false
        assertSame(fatal, retainedFailure(quiesce = { throw fatal }, export = { exported = true }, primary = original))
        assertFalse(exported)
        assertTrue(fatal.suppressed.contains(original))
    }

    @Test fun `fatal original keeps identity with ordinary export failure suppressed`() {
        val fatal = LinkageError("fatal capture")
        val export = IOException("export failed")
        assertSame(fatal, retainedFailure(export = { throw export }, primary = fatal))
        assertTrue(fatal.suppressed.contains(export))
    }

    @Test fun `pinned signal validates incarnation and owned cgroup before any signal`() {
        val script = TierEInstalledFixtureSafety.script("pidfd-stop")
        val program = """
            import io, os, signal
            from unittest.mock import patch
            namespace={'__name__':'fixture_test'}
            exec(${pythonLiteral(script)}, namespace)
            stat='123 (owned collector) S '+' '.join(['0']*18+['987'])
            for observed_start, observed_group, allowed in [('987','0::/owned/id',True),('988','0::/owned/id',False),('987','0::/unrelated',False)]:
                order=[]
                def read(path,*args,**kwargs):
                    order.append('read')
                    return io.StringIO(stat.replace('987',observed_start) if path.endswith('/stat') else observed_group)
                with patch('os.pidfd_open',side_effect=lambda pid,flags: order.append('open') or 17), patch('builtins.open',side_effect=read), patch('os.close') as close, patch('signal.pidfd_send_signal',side_effect=lambda *args: order.append('signal')) as send, patch('os.kill',side_effect=AssertionError('bare PID signal forbidden')):
                    try: namespace['stop_owned'](123,987,'0::/owned/id')
                    except Exception:
                        assert not allowed
                    else: assert allowed
                    assert order[0]=='open'
                    assert send.call_count==int(allowed)
                    close.assert_called_once_with(17)
            print('pinned success/stale incarnation/wrong cgroup: no unsafe signal')
        """.trimIndent()
        val result = runContainerCommand(listOf("python3", "-c", program))
        assertEquals(0, result.exitCode, result.output)
    }

    @Test fun `stale pinned identity failure enters stopped-source recovery without host signal`() {
        val script = TierEInstalledFixtureSafety.script("pidfd-stop")
        val program = """
            import io, os, signal, sys
            from unittest.mock import patch
            namespace={'__name__':'fixture_test'}
            exec(${pythonLiteral(script)}, namespace)
            stat='123 (owned collector) S '+' '.join(['0']*18+['988'])
            with patch('os.pidfd_open',return_value=17), patch('builtins.open',return_value=io.StringIO(stat)), patch('os.close'), patch('signal.pidfd_send_signal') as send, patch('os.kill',side_effect=AssertionError('bare PID signal forbidden')):
                try: namespace['stop_owned'](123,987,'0::/owned/id')
                except RuntimeError as failure:
                    send.assert_not_called()
                    print(str(failure),flush=True)
                    sys.exit(42)
                raise AssertionError('stale identity accepted')
        """.trimIndent()
        val result = runContainerCommand(listOf("python3", "-c", program))
        assertEquals(42, result.exitCode, result.output)
        val failure = retainedFailure(quiesce = { check(result.exitCode == 0) { result.output } }, export = { error("no live export") })
        assertTrue(failure.message.orEmpty().contains("incarnation mismatch; no signal sent"))
    }

    @Test fun `export script reserves ENOENT and preserves complete opened source bytes`() {
        val source = root.resolve("dictionary")
        Files.write(source, ByteArray(8192) { (it % 251).toByte() })
        val script = TierEInstalledFixtureSafety.script("export")
        val output = root.resolve("transport")
        val raw = runContainerCommand(listOf("python3", "-c", script, source.toString()), outputFile = output)
        val result = raw.copy(output = Files.readString(output))
        assertEquals(0, result.exitCode, result.output)
        TierEInstalledFixtureSafety.retainArtifact(result, true, root.resolve("copy"))
        assertContentEquals(Files.readAllBytes(source), Files.readAllBytes(root.resolve("copy")))
        val absent = runContainerCommand(listOf("python3", "-c", script, root.resolve("absent").toString()))
        assertEquals(44, absent.exitCode)
        Files.createSymbolicLink(root.resolve("unsafe-link"), source)
        val unsafe = runContainerCommand(listOf("python3", "-c", script, root.resolve("unsafe-link").toString()))
        assertTrue(unsafe.exitCode != 0 && unsafe.exitCode != 44, unsafe.output)
    }

    @Test fun `base64 subprocess failure is not ENOENT and cannot retire only source copy`() {
        val source = root.resolve("dictionary")
        Files.write(source, sourceBytes)
        val script = TierEInstalledFixtureSafety.script("export")
        val program = """
            import sys
            from types import SimpleNamespace
            from unittest.mock import patch
            sys.argv=['export',${pythonLiteral(source.toString())}]
            with patch('subprocess.run',return_value=SimpleNamespace(returncode=1)):
                exec(${pythonLiteral(script)},{'__name__':'__main__'})
        """.trimIndent()
        val result = runContainerCommand(listOf("python3", "-c", program))
        assertEquals(1, result.exitCode, result.output)
        retainedFailure(export = { TierEInstalledFixtureSafety.retainArtifact(result, true, root.resolve("lost")) })
    }

    private fun pythonLiteral(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'"
}
