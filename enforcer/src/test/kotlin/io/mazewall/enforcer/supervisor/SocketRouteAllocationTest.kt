package io.mazewall.enforcer.supervisor

import io.mazewall.core.Tid
import io.mazewall.ffi.memory.FaultingNativeArena
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

class SocketRouteAllocationTest {
    @TestFactory
    fun `accept allocation failures release the already imported socket`(): List<DynamicTest> =
        listOf(1, 2).flatMap { ordinal ->
            listOf({ IllegalStateException("peer allocation failure") }, { OutOfMemoryError("fatal peer allocation failure") }).map { createFailure ->
                DynamicTest.dynamicTest("allocation $ordinal ${createFailure().javaClass.simpleName}") {
                    val fixture = SupervisorBoundaryFixture()
                    val failure = createFailure()
                    fixture.engine.networking.onAccept4 = { _, _, _, _ -> error("accept cannot run before its buffers exist") }
                    FaultingNativeArena
                        .failingAllocation(ordinal) {
                        assertEquals(listOf(51005), fixture.closed, "import must precede peer-buffer allocation")
                        throw failure
                    }.use { arena ->
                        val observed = assertThrows(failure.javaClass) {
                            with(arena) { sockets(fixture).accept(AcceptRequest(Tid(77), 55, 0, 0, 0)) }
                        }
                        assertSame(failure, observed)
                    }
                    assertEquals(listOf(51005, 51006), fixture.closed)
                    assertEquals(0, fixture.replies.size)
                }
            }
        }

    @Test
    fun `accept length initialization failure releases its imported socket`() {
        val fixture = SupervisorBoundaryFixture()
        fixture.engine.networking.onAccept4 = { _, _, _, _ -> error("accept cannot run with an uninitialized address length") }
        FaultingNativeArena.readOnlyAllocation(2).use { arena ->
            assertThrows(IllegalArgumentException::class.java) {
                with(arena) { sockets(fixture).accept(AcceptRequest(Tid(77), 55, 0, 0, 0)) }
            }
        }
        assertEquals(listOf(51005, 51006), fixture.closed)
        assertEquals(0, fixture.replies.size)
    }

    private fun sockets(fixture: SupervisorBoundaryFixture) = SocketRouteExecutor(fixture.engine, TraceeDescriptorImporter(fixture.engine) { it })
}
