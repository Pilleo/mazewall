package io.mazewall.profiler.tierE.daemon

import io.mazewall.profiler.internal.attribution.EvidenceIntegrity
import io.mazewall.profiler.internal.attribution.TierEEmissionMode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TierETargetRegistrationTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `stale registered incarnation is rejected before engine creation and reported incomplete`() {
        val target = TierERegisteredTarget(
            registrationId = "orders",
            pid = 4242,
            sessionTag = 0x4d5a,
            processIncarnation = 9_876L,
            markerPath = Path.of("/collector/agent/libmazewall_stack_agent.so"),
            markerOffset = 0x20,
            artifactFileName = "orders.mzse",
        )
        val reports = mutableListOf<TierETerminalReport>()
        val daemon = TierEDaemonService(
            stopRequested = AtomicBoolean(false),
            engineFactory = { error("engine must not be created for stale identity") },
            artifactResolver = TierEDaemonArtifactResolver(root),
            registeredTargetResolver = TierETargetRegistrationResolver(mapOf(target.registrationId to target)),
            processIdentityVerifier = TierEProcessIdentityVerifier { 9_877L },
            terminalReportSink = TierETerminalReportSink(reports::add),
        )

        val reply = daemon.executeCommand("ATTACH 4242 uprobe /collector/agent/libmazewall_stack_agent.so 20 4d5a")

        assertTrue(!reply.startsWith("OK"))
        assertEquals(1, reports.size)
        assertEquals("STALE_PROCESS_IDENTITY", reports.single().terminalReason)
        assertEquals(EvidenceIntegrity.INCOMPLETE, reports.single().evidenceIntegrity)
        assertEquals(false, reports.single().policyEligibility)
        assertEquals(false, reports.single().lifecycleValidity)
    }

    @Test
    fun `registered target admits only its exact attach command`() {
        val registration = TierERegisteredTarget(
            registrationId = "orders",
            pid = 4242,
            sessionTag = 0x4d5a,
            processIncarnation = 9_876L,
            markerPath = Path.of("/collector/agent/libmazewall_stack_agent.so"),
            markerOffset = 0x20,
            artifactFileName = "orders.mzse",
        )
        val resolver = TierETargetRegistrationResolver(mapOf(registration.registrationId to registration))
        val command = ControlCommand.Attach(
            pid = 4242,
            mode = AttachMode.AUTO,
            markerPath = "/collector/agent/libmazewall_stack_agent.so",
            markerOffset = 0x20,
            sessionTag = 0x4d5a,
            emissionMode = TierEEmissionMode.FULL_STREAM,
        )

        assertEquals(registration, resolver.resolve(command))
        assertFailsWith<IllegalArgumentException> { resolver.resolve(command.copy(pid = 4243)) }
        assertFailsWith<IllegalArgumentException> { resolver.resolve(command.copy(sessionTag = 0x4d5b)) }
        assertFailsWith<IllegalArgumentException> { resolver.resolve(command.copy(markerPath = "/tmp/other-agent.so")) }
        assertFailsWith<IllegalArgumentException> { resolver.resolve(command.copy(markerOffset = 0x21)) }
    }

    @Test
    fun `registration file is explicit bounded and rejects artifact path escape`() {
        val file = root.resolve("target.properties")
        Files.writeString(
            file,
            """
            version=1
            registration_id=orders
            pid=4242
            session_tag=4d5a
            process_incarnation=9876
            marker_path=/collector/agent/libmazewall_stack_agent.so
            marker_offset=20
            artifact_file=orders.mzse
            """.trimIndent(),
        )

        val registration = TierETargetRegistrationFile.read(file)

        assertEquals("orders", registration.registrationId)
        assertEquals(4242, registration.pid)
        assertEquals("orders.mzse", registration.artifactFileName)

        Files.writeString(file, Files.readString(file).replace("orders.mzse", "../other.mzse"))
        assertFailsWith<IllegalArgumentException> { TierETargetRegistrationFile.read(file) }
    }

    @Test
    fun `artifact resolver uses registered artifact name rather than collector pid convention`() {
        val registration = TierERegisteredTarget(
            registrationId = "orders",
            pid = 42_424,
            sessionTag = 0x4d5a,
            processIncarnation = 9_876L,
            markerPath = Path.of("/collector/agent/libmazewall_stack_agent.so"),
            markerOffset = 0x20,
            artifactFileName = "application-namespace-pid-7.mzse",
        )

        val artifact = TierEDaemonArtifactResolver(root).resolve(registration)

        assertEquals(root.resolve("application-namespace-pid-7.mzse"), artifact.path)
        assertEquals(registration.sessionEpoch, artifact.expectedSessionEpoch)
    }

    @Test
    fun `registered daemon attachment uses the registered process incarnation and artifact`() {
        val registration = TierERegisteredTarget(
            registrationId = "orders",
            pid = 42_424,
            sessionTag = 0x4d5a,
            processIncarnation = 9_876L,
            markerPath = Path.of("/collector/agent/libmazewall_stack_agent.so"),
            markerOffset = 0x20,
            artifactFileName = "orders.mzse",
        )
        val command = ControlCommand.Attach(
            pid = registration.pid,
            mode = AttachMode.AUTO,
            markerPath = registration.markerPath.toString(),
            markerOffset = 0x20,
            sessionTag = registration.sessionTag,
        )

        val attachment = resolveDaemonAttachment(
            command,
            TierETargetRegistrationResolver(mapOf(registration.registrationId to registration)),
            TierEDaemonArtifactResolver(root),
        )

        assertEquals(registration.processIncarnation, attachment.session.processIncarnation)
        assertEquals(registration.markerPath, attachment.markerPath)
        assertEquals(registration.markerOffset, attachment.markerOffset)
        assertEquals(root.resolve("orders.mzse"), attachment.artifact?.path)
    }

    @Test
    fun `target launch plan emits a terminal session-bound dictionary option`() {
        val target = TierERegisteredTarget(
            registrationId = "orders",
            pid = 4242,
            sessionTag = 0x4d5a,
            processIncarnation = 9_876L,
            markerPath = Path.of("/collector/agent/libmazewall_stack_agent.so"),
            markerOffset = 0x20,
            artifactFileName = "tier-e-4242-4d5a.mzse",
        )

        val plan = TierETargetLaunchPlan(target, Path.of("/run/mazewall/definitions"))

        assertEquals(Path.of("/run/mazewall/definitions/tier-e-4242-4d5a.mzse"), plan.definitionPath)
        assertEquals(
            "session=4d5a,session-bound-definitions,definitions=/run/mazewall/definitions/tier-e-4242-4d5a.mzse",
            plan.nativeAgentOptions(),
        )
    }
}
