package io.mazewall.portal.worker

import io.mazewall.portal.PortalFrame
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.IOException

class PortalWorkerStartupTest {
    @Test
    fun `fatal actual receipt retirement dominates ordinary startup or execution failure`() {
        for (startup in listOf(true, false)) {
            val ordinary = IOException("ordinary failure")
            val fatal = AssertionError("fatal receipt retirement")
            val receipt = successfulReceipt()
            attachLease(receipt) { throw fatal }
            val transport = object : PortalWorkerTransport {
                override fun receive(): PortalWorkerReceive = throw ordinary

                override fun send(frame: PortalFrame) = Unit

                override fun close() = Unit
            }
            assertSame(
                fatal,
                assertThrows(AssertionError::class.java) {
                PortalWorkerMain.runWorker(transport, { receipt }, {
                    if (startup) throw ordinary
                    PortalWorkerDispatcher { _, _, _ -> ByteArray(0) }
                }, {})
            },
            )
            assertEquals(listOf(ordinary), fatal.suppressed.toList())
        }
    }

    @Test
    fun `process receipt remains owned after main return without Java retirement boundary`() {
        val events = mutableListOf<String>()
        val lifetime = PortalWorkerProcessReceiptLifetime()
        val receipt = successfulReceipt()
        attachLease(receipt) { events += "receipt-close" }
        PortalWorkerMain.runWorker(transport(events), { receipt }, { PortalWorkerDispatcher { _, _, _ -> ByteArray(0) } }, { events += "ready" }, receiptLifetime = lifetime)
        assertEquals(listOf("ready", "receive", "close"), events, "non-daemon guest threads may keep the real JVM alive after main returns")
        assertEquals(listOf("ready", "receive", "close"), events, "one completed shutdown hook is not physical end")
        assertEquals(0, events.count { it == "receipt-close" })
    }

    @Test
    fun `failed main retains actual receipt for process lifetime`() {
        val events = mutableListOf<String>()
        val lifetime = PortalWorkerProcessReceiptLifetime()
        val receipt = successfulReceipt()
        attachLease(receipt) { events += "receipt-close" }
        val fatal = AssertionError("guest constructor failed after starting a thread")
        assertSame(
            fatal,
            assertThrows(AssertionError::class.java) {
            PortalWorkerMain.runWorker(transport(events), { receipt }, { throw fatal }, { error("must not publish") }, receiptLifetime = lifetime)
        },
        )
        assertEquals(listOf("close"), events)
        assertEquals(listOf("close"), events, "failed main can coexist with held guest shutdown")
    }

    @Test
    fun `receipt lease remains owned through ready and transport physical end`() {
        val events = mutableListOf<String>()
        val receipt = successfulReceipt()
        attachLease(receipt) { events += "receipt-close" }
        PortalWorkerMain.runWorker(
            transport(events),
            installContainment = { receipt },
            createDispatcher = { PortalWorkerDispatcher { _, _, _ -> ByteArray(0) } },
            publishReady = { actual ->
                assertSame(receipt, actual)
                events += "ready"
            },
        )
        assertEquals(listOf("ready", "receive", "close", "receipt-close"), events)
        receipt.close()
        assertEquals(1, events.count { it == "receipt-close" })
    }

    @Test
    fun `missing partial thread only and policy mismatched evidence never enters guest`() {
        val policy = successfulReceipt().requestedPolicy
        val invalid = listOf(
            io.mazewall.enforcer.api
                .InstallationReceipt(
                    false,
                    policy,
                    landlockApplied = true,
                    progress = io.mazewall.enforcer.api
                    .InstallationProgress(false, true, true),
                ),
            io.mazewall.enforcer.api
                .InstallationReceipt(
                    true,
                    policy,
                    landlockApplied = false,
                    progress = io.mazewall.enforcer.api
                    .InstallationProgress(true, false, true),
                ),
            io.mazewall.enforcer.api
                .InstallationReceipt(true, policy, landlockApplied = true, progress = null),
            io.mazewall.enforcer.api
                .InstallationReceipt(
                    true,
                    policy,
                    landlockApplied = true,
                    progress = io.mazewall.enforcer.api
                    .InstallationProgress(true, true, false),
                ),
        )
        invalid.forEach { receipt ->
            val events = mutableListOf<String>()
            attachLease(receipt) { events += "receipt-close" }
            assertThrows(IllegalStateException::class.java) {
                PortalWorkerMain.runWorker(transport(events), { receipt }, { error("guest entered") }, { error("early readiness") })
            }
            assertEquals(listOf("close", "receipt-close"), events)
        }
        val events = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            PortalWorkerMain.runWorker(
                transport(events),
                { successfulReceipt() },
                { error("wrong-policy guest entered") },
                { error("early readiness") },
                requiredPolicy = {
                    io.mazewall.enforcer.api
                    .PolicyDefinition<io.mazewall.enforcer.api.PolicyScope.ProcessWideSafe>(allowNonThreadClone = true)
                },
            )
        }
        assertEquals(listOf("close"), events)
    }

    @Test
    fun `fatal ready failure retires actual receipt and preserves cleanup evidence`() {
        val events = mutableListOf<String>()
        val receipt = successfulReceipt()
        val fatal = AssertionError("ready failed")
        val cleanup = IOException("receipt failed")
        attachLease(receipt) {
            events += "receipt-close"
            throw cleanup
        }
        assertSame(
            fatal,
            assertThrows(AssertionError::class.java) {
            PortalWorkerMain.runWorker(transport(events), { receipt }, { PortalWorkerDispatcher { _, _, _ -> ByteArray(0) } }, { throw fatal })
        },
        )
        assertEquals(listOf("close", "receipt-close"), events)
        assertEquals(listOf(cleanup), fatal.suppressed.toList())
    }

    // The receipt's registration constructor is enforcer-internal. Attach only a test retirement
    // observer to the actual receipt; no replacement receipt or production lifecycle hook is used.
    private fun attachLease(
        receipt: io.mazewall.enforcer.api.InstallationReceipt,
        close: () -> Unit,
    ) {
        receipt.javaClass
            .getDeclaredField("supervisorRegistration")
            .also { it.isAccessible = true }
            .set(receipt, AutoCloseable(close))
    }

    @Test
    fun `incomplete real receipt rejects before guest construction and readiness`() {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("seccomp installation failed")
        val receipt = io.mazewall.enforcer.api.InstallationReceipt(
            processWide = true,
            requestedPolicy = io.mazewall.enforcer.api
                .PolicyDefinition<io.mazewall.enforcer.api.PolicyScope.ProcessWideSafe>(),
            installed = false,
            landlockApplied = true,
            failure = failure,
        )
        assertSame(
            failure,
            assertThrows(IllegalStateException::class.java) {
            PortalWorkerMain.runWorker(
                transport(events),
                installContainment = { receipt },
                createDispatcher = {
                    events += "guest"
                    PortalWorkerDispatcher { _, _, _ -> ByteArray(0) }
                },
                publishReady = { events += "ready" },
            )
        },
        )
        assertEquals(listOf("close"), events)
    }

    @Test
    fun `required parent setup precedes containment and every guest initialization`() {
        val events = mutableListOf<String>()
        PortalWorkerMain.runWorker(
            transport(events),
            authorizeParent = { events += "parent" },
            installContainment = {
                events += "containment"
                successfulReceipt()
            },
            createDispatcher = {
                events += "guest"
                PortalWorkerDispatcher { _, _, _ -> ByteArray(0) }
            },
            publishReady = { events += "ready" },
        )
        assertEquals(listOf("parent", "containment", "guest", "ready", "receive", "close"), events)
    }

    @Test
    fun `failed parent setup retires transport without containment guest or ready`() {
        val events = mutableListOf<String>()
        val rejected = IllegalStateException("parent authorization rejected")
        assertSame(
            rejected,
            assertThrows(IllegalStateException::class.java) {
            PortalWorkerMain.runWorker(
                transport(events),
                authorizeParent = { throw rejected },
                installContainment = { error("must not install") },
                createDispatcher = { error("must not construct") },
                publishReady = { error("must not publish") },
            )
        },
        )
        assertEquals(listOf("close"), events)
    }

    @Test
    fun `readiness follows containment and guest construction before requests`() {
        val events = mutableListOf<String>()
        val transport = transport(events)
        PortalWorkerMain.runWorker(
            transport,
            installContainment = {
                events += "containment"
                successfulReceipt()
            },
            createDispatcher = {
                events += "guest"
                PortalWorkerDispatcher { _, _, _ -> ByteArray(0) }
            },
            publishReady = { events += "ready" },
        )
        assertEquals(listOf("containment", "guest", "ready", "receive", "close"), events)
    }

    @Test
    fun `each startup failure closes transport suppresses cleanup and never serves`() {
        for (failedStage in listOf("containment", "guest", "ready")) {
            val events = mutableListOf<String>()
            val primary = AssertionError("failed $failedStage")
            val cleanup = IOException("failed close")
            val step: (String) -> Unit = { stage ->
                events += stage
                if (stage == failedStage) throw primary
            }
            assertSame(
                primary,
                assertThrows(AssertionError::class.java) {
                PortalWorkerMain.runWorker(
                    transport(events, cleanup),
                    installContainment = {
                        step("containment")
                        successfulReceipt()
                    },
                    createDispatcher = {
                        step("guest")
                        PortalWorkerDispatcher { _, _, _ -> ByteArray(0) }
                    },
                    publishReady = { step("ready") },
                )
            },
            )
            assertEquals(listOf(cleanup), primary.suppressed.toList())
            assertEquals(1, events.count { it == "close" })
            assertFalse("receive" in events)
            if (failedStage != "ready") assertFalse("ready" in events)
        }
    }

    private fun successfulReceipt() =
        io.mazewall.enforcer.api.InstallationReceipt(
        processWide = true,
        requestedPolicy = io.mazewall.enforcer.api
            .PolicyDefinition<io.mazewall.enforcer.api.PolicyScope.ProcessWideSafe>(),
        landlockApplied = true,
        progress = io.mazewall.enforcer.api
            .InstallationProgress(true, true, true),
    )

    private fun transport(
        events: MutableList<String>,
        failure: IOException? = null,
    ) = object : PortalWorkerTransport {
        override fun receive(): PortalWorkerReceive {
            events += "receive"
            return PortalWorkerReceive.PeerClosed
        }

        override fun send(frame: PortalFrame) = error("no request")

        override fun close() {
            events += "close"
            failure?.let { throw it }
        }
    }
}
