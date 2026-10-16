package io.mazewall.portal

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PortalWorkerReadyTest {
    private val generation = PortalWorkerGeneration("a".repeat(64), "b".repeat(64), "c".repeat(64))
    private val ready = PortalWorkerReady("d".repeat(32), 123, generation)

    @Test
    fun `exact receipt backed generation record accepts`() {
        assertEquals(ready, PortalWorkerReady.accept(ready.frame(), ready))
        assertTrue(ready.frame().payload.size <= PortalChannel.MAX_CONTROL_BYTES)
    }

    @Test
    fun `malformed unknown mismatched and legacy records reject`() {
        val text = ready
            .frame()
            .payload
            .copyToByteArray()
            .toString(Charsets.US_ASCII)
        val records = listOf(
            "MAZEWALL_PORTAL_WORKER_READY\n",
            text.replace("format=2", "format=1"),
            text + "extra=1\n",
            text.replace("processWide=1", "processWide=0"),
            text.replace("seccomp=1", "seccomp=0"),
            text.replace("filesystem=1", "filesystem=0"),
            text.replace("childPid=123", "childPid=0123"),
            text.replace("childPid=123", "childPid=124"),
            text.replace("session=", "unknown="),
            text.replace("a".repeat(64), "e".repeat(64)),
            text.replace("b".repeat(64), "e".repeat(64)),
            text.replace("c".repeat(64), "e".repeat(64)),
            text.replace(PortalWorkerReady.BASELINE, "other-policy"),
            text.replace("resources=none", "resources=${"e".repeat(64)}"),
            text.replace("\n", "\r\n"),
            text.removeSuffix("\n"),
            "x".repeat(PortalChannel.MAX_CONTROL_BYTES + 1),
            text.replace("session=", "session=é"),
        )
        records.forEach { record ->
            assertThrows(IllegalArgumentException::class.java, {
                PortalWorkerReady.accept(PortalFrame(PortalKind.Response, 0, PortalWorkerReady.METHOD, record.toByteArray(), 0), ready)
            }, record)
        }
        assertThrows(IllegalArgumentException::class.java) { PortalWorkerReady.accept(PortalParentAuthorization(ready.session, ready.childPid).acknowledgement(), ready) }
        listOf(
            PortalFrame(PortalKind.Request, 0, PortalWorkerReady.METHOD, ready.frame().payload, 0),
            PortalFrame(PortalKind.Response, 1, PortalWorkerReady.METHOD, ready.frame().payload, 0),
        ).forEach { invalid -> assertThrows(IllegalArgumentException::class.java) { PortalWorkerReady.accept(invalid, ready) } }
        assertThrows(IllegalArgumentException::class.java) { PortalFrame(PortalKind.Response, 0, PortalWorkerReady.METHOD, ready.frame().payload, 1) }
    }

    @Test
    fun `required cgroup evidence and maximum PID fit strict control bound`() {
        val required = ready.copy(childPid = Long.MAX_VALUE, resources = "e".repeat(64))
        assertTrue(required.frame().payload.size <= PortalChannel.MAX_CONTROL_BYTES)
        assertEquals(required, PortalWorkerReady.accept(required.frame(), required))
    }

    @Test
    fun `late cancelled and rejected readiness cannot publish`() {
        for (matches in listOf(false, true)) {
            for (active in listOf(false, true)) {
                for (withinDeadline in listOf(false, true)) {
                    assertEquals(
                        if (matches && active && withinDeadline) PortalReadyPhase.READY else PortalReadyPhase.REJECTED,
                        PortalReadyGate.evaluate(PortalReadyPhase.WAITING, matches, active, withinDeadline),
                    )
                }
            }
        }
        assertEquals(PortalReadyPhase.REJECTED, PortalReadyGate.evaluate(PortalReadyPhase.REJECTED, true, true, true))
        assertThrows(IllegalStateException::class.java) { PortalReadyGate.evaluate(PortalReadyPhase.READY, true, true, true) }
    }
}
