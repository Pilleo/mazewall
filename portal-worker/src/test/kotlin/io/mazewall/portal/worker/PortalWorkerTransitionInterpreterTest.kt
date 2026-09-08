package io.mazewall.portal.worker

import io.mazewall.portal.PortalFrame
import io.mazewall.portal.PortalKind
import io.mazewall.portal.PortalMethod
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class PortalWorkerTransitionInterpreterTest {
    @Test
    fun `protocol violation terminates rather than silently continuing`() {
        val frame = PortalFrame(PortalKind.Response, 3, PortalMethod.Echo, ByteArray(0), 0)
        val transition = PortalWorkerMachine.evaluate(PortalWorkerState.AwaitingRequest, PortalWorkerEvent.FrameReceived(frame))

        assertInstanceOf(PortalWorkerLoopAction.Terminate::class.java, PortalWorkerTransitionInterpreter.interpret(transition))
    }

    @Test
    fun `dispatch transition exposes only the request to the io adapter`() {
        val request = PortalFrame(PortalKind.Request, 3, PortalMethod.Echo, ByteArray(0), 0)
        val transition = PortalWorkerMachine.evaluate(PortalWorkerState.AwaitingRequest, PortalWorkerEvent.FrameReceived(request))

        val action = assertInstanceOf(PortalWorkerLoopAction.Dispatch::class.java, PortalWorkerTransitionInterpreter.interpret(transition))
        org.junit.jupiter.api.Assertions
            .assertEquals(request, action.request)
    }
}
