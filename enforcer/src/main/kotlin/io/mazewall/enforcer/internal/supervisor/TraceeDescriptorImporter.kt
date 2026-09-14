package io.mazewall.enforcer.internal.supervisor

import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.NativeEngine

/** Imports an owned local reference; the tracee's integer is never treated as local ownership. */
internal class TraceeDescriptorImporter(
    private val engine: NativeEngine,
    private val tgid: (Int) -> Int = ProcFsInspector::getTgid,
) {
    fun import(
        tid: Tid,
        target: Int,
    ): SupervisorDescriptorResult {
        val group = tgid(tid.value)
        val exactThread = tid.value != group
        val targetPid = if (exactThread) tid.value else group
        val flags = if (exactThread) io.mazewall.ffi.NativeConstants.PIDFD_THREAD else 0
        return when (val process = SupervisorDescriptorResult.from(engine.process.pidfdOpen(targetPid, flags), engine)) {
            is SupervisorDescriptorResult.Denied -> process
            is SupervisorDescriptorResult.Opened -> process.descriptor.produce {
                SupervisorDescriptorResult.from(engine.process.pidfdGetFd(process.descriptor.handle, target, 0), engine)
            }
        }
    }
}
