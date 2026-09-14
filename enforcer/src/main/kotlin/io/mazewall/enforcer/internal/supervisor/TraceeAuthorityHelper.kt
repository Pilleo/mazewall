package io.mazewall.enforcer.internal.supervisor

import io.mazewall.ffi.memory.NativeArena
import io.mazewall.platform.native.LinuxNative
import java.io.DataInputStream
import java.io.DataOutputStream

/** Serial child interpreter. Proc/pidfd/mem/cwd handles never leave this request's scope. */
internal object TraceeAuthorityHelper {
    // SUPPRESSION-RATIONALE: Nested arena, authority and reply owners enclose the serial helper transaction so every exceptional exit releases acquired descriptors.
    fun serve(
        input: DataInputStream,
        output: DataOutputStream,
        send: (SupervisorOwnedFd) -> Unit,
    ) {
        val (notification, roots) = TraceeAuthorityProtocol.readRequest(input)
        NativeArena.ofConfined().use { arena ->
            with(arena) {
                var authority: TraceeProcAuthority? = null
                try {
                    var execution: TraceeAuthorityExecution? = null
                    val snapshot = try {
                        val acquired = TraceeProcAuthority.capture(notification.pid, LinuxNative).also { authority = it }
                        TraceeAuthorityExecution(LinuxNative, acquired, notification, roots).also { execution = it }.prepare()
                    } catch (failure: TraceeAuthorityFailure) {
                        TraceeAuthoritySnapshot(0, null, null, SupervisorRoute.Abort(failure.errno, "authority acquisition failed"))
                    }
                    output.writeLong(notification.id)
                    TraceeAuthorityProtocol.writeSnapshot(output, snapshot)
                    val decision = TraceeAuthorityProtocol.readDecision(input)
                    val authorized = decision.copy(route = TraceeAuthorityDecisionMachine.evaluate(snapshot.route, decision.route))
                    val effect = try {
                        if (authorized.route is SupervisorRoute.Abort) {
                            SupervisorReplyEffect.Error(authorized.route.errno)
                        } else {
                            checkNotNull(execution).execute(authorized)
                        }
                    } catch (failure: TraceeAuthorityFailure) {
                        SupervisorReplyEffect.Error(failure.errno)
                    }
                    SupervisorReplyHandoff(effect).use {
                        output.writeLong(notification.id)
                        TraceeAuthorityProtocol.writeEffect(output, effect, send)
                    }
                } finally {
                    authority?.close()
                }
            }
        }
    }
}
