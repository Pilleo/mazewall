package io.mazewall.portal

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ProcessBrokerLifecycleMachineTest {
    @Test
    fun `happy path publishes only after the whole startup batch is ready`() {
        val starting = ProcessBrokerLifecycleMachine.evaluate(ProcessBrokerLifecycle.New, ProcessBrokerLifecycleEvent.StartRequested)
        val running = ProcessBrokerLifecycleMachine.evaluate(starting.state, ProcessBrokerLifecycleEvent.StartupSucceeded)
        val closing = ProcessBrokerLifecycleMachine.evaluate(running.state, ProcessBrokerLifecycleEvent.CloseRequested)
        val closed = ProcessBrokerLifecycleMachine.evaluate(closing.state, ProcessBrokerLifecycleEvent.CleanupSucceeded)

        assertInstanceOf(ProcessBrokerLifecycle.Starting::class.java, starting.state)
        assertEquals(ProcessBrokerLifecycle.Running, running.state)
        assertEquals(ProcessBrokerLifecycle.Closing, closing.state)
        assertEquals(ProcessBrokerLifecycle.Closed, closed.state)
    }

    @Test
    fun `startup failure is terminal and cannot be published`() {
        val starting = ProcessBrokerLifecycleMachine.evaluate(ProcessBrokerLifecycle.New, ProcessBrokerLifecycleEvent.StartRequested)
        val failed = ProcessBrokerLifecycleMachine.evaluate(starting.state, ProcessBrokerLifecycleEvent.StartupFailed("second worker"))
        val publish = ProcessBrokerLifecycleMachine.evaluate(failed.state, ProcessBrokerLifecycleEvent.StartupSucceeded)

        assertEquals(ProcessBrokerLifecycle.Failed("second worker"), failed.state)
        assertInstanceOf(ProcessBrokerLifecycleTransition.ProtocolViolation::class.java, publish)
        assertEquals(ProcessBrokerLifecycle.Failed("second worker"), publish.state)
    }

    @Test
    fun `lost running worker cannot be republished and retains cleanup admission`() {
        val lost = ProcessBrokerLifecycleMachine.evaluate(ProcessBrokerLifecycle.Running, ProcessBrokerLifecycleEvent.WorkerLost("state lost"))
        assertEquals(ProcessBrokerLifecycle.Failed("state lost"), lost.state)
        val restart = ProcessBrokerLifecycleMachine.evaluate(lost.state, ProcessBrokerLifecycleEvent.StartRequested)
        assertInstanceOf(ProcessBrokerLifecycleTransition.ProtocolViolation::class.java, restart)
        val publish = ProcessBrokerLifecycleMachine.evaluate(lost.state, ProcessBrokerLifecycleEvent.StartupSucceeded)
        assertEquals(lost.state, publish.state)
        assertInstanceOf(ProcessBrokerLifecycleTransition.ProtocolViolation::class.java, publish)
        assertEquals(ProcessBrokerLifecycle.Closing, ProcessBrokerLifecycleMachine.evaluate(lost.state, ProcessBrokerLifecycleEvent.CloseRequested).state)
    }

    @Test
    fun `every lifecycle event combination is explicitly classified`() {
        val states = listOf(
            ProcessBrokerLifecycle.New,
            ProcessBrokerLifecycle.Starting,
            ProcessBrokerLifecycle.Running,
            ProcessBrokerLifecycle.Closing,
            ProcessBrokerLifecycle.Closed,
            ProcessBrokerLifecycle.Failed("x"),
        )
        val events = listOf(
            ProcessBrokerLifecycleEvent.StartRequested,
            ProcessBrokerLifecycleEvent.StartupSucceeded,
            ProcessBrokerLifecycleEvent.StartupFailed("x"),
            ProcessBrokerLifecycleEvent.WorkerLost("x"),
            ProcessBrokerLifecycleEvent.CloseRequested,
            ProcessBrokerLifecycleEvent.CleanupSucceeded,
            ProcessBrokerLifecycleEvent.CleanupFailed("x"),
        )

        states.forEach { state ->
            events.forEach { event ->
                val transition = ProcessBrokerLifecycleMachine.evaluate(state, event)
                val valid = when (state) {
                    ProcessBrokerLifecycle.New -> event is ProcessBrokerLifecycleEvent.StartRequested || event is ProcessBrokerLifecycleEvent.CloseRequested
                    ProcessBrokerLifecycle.Starting ->
                        event is ProcessBrokerLifecycleEvent.StartupSucceeded ||
                        event is ProcessBrokerLifecycleEvent.StartupFailed ||
                        event is ProcessBrokerLifecycleEvent.CloseRequested
                    ProcessBrokerLifecycle.Running -> event is ProcessBrokerLifecycleEvent.CloseRequested || event is ProcessBrokerLifecycleEvent.WorkerLost
                    ProcessBrokerLifecycle.Closing -> event is ProcessBrokerLifecycleEvent.CleanupSucceeded || event is ProcessBrokerLifecycleEvent.CleanupFailed
                    is ProcessBrokerLifecycle.Failed -> event is ProcessBrokerLifecycleEvent.CloseRequested
                    ProcessBrokerLifecycle.Closed -> false
                }
                if (valid) {
                    assertInstanceOf(ProcessBrokerLifecycleTransition.Advanced::class.java, transition, "$state + $event")
                } else {
                    assertInstanceOf(ProcessBrokerLifecycleTransition.ProtocolViolation::class.java, transition, "$state + $event")
                }
            }
        }
    }
}
