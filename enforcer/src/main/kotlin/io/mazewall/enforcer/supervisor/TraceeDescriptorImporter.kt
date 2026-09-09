package io.mazewall.enforcer.supervisor

import io.mazewall.NativeEngine
import io.mazewall.core.Tid

/** Imports an owned local reference; the tracee's integer is never treated as local ownership. */
internal class TraceeDescriptorImporter(
    private val engine: NativeEngine,
    private val tgid: (Int) -> Int = ProcFsInspector::getTgid,
) {
    fun import(
        tid: Tid,
        target: Int,
    ): SupervisorDescriptorResult =
        when (val process = SupervisorDescriptorResult.from(engine.process.pidfdOpen(tgid(tid.value), 0), engine)) {
            is SupervisorDescriptorResult.Denied -> process
            is SupervisorDescriptorResult.Opened -> process.descriptor.produce {
                SupervisorDescriptorResult.from(engine.process.pidfdGetFd(process.descriptor.handle, target, 0), engine)
            }
        }
}
