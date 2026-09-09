package io.mazewall.enforcer.supervisor

import io.mazewall.NativeEngine
import io.mazewall.core.FileDescriptor
import io.mazewall.core.NewFdFlags
import io.mazewall.core.Tid
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.writeLong

/** Prepares an owned file for injection, preserving the tracee's open ABI and dirfd context. */
internal class OpenRouteExecutor(
    private val engine: NativeEngine,
    private val descriptors: TraceeDescriptorImporter,
) {
    context(arena: NativeArena) fun execute(
        request: SupervisedOpen,
        tid: Tid,
    ): SupervisorReplyEffect {
        val plan = SupervisorOpenPlan.create(request)
        val how = arena.allocate(Layouts.OPEN_HOW_SIZE)
        how.writeLong(Layouts.OPEN_HOW_FLAGS_OFFSET, plan.flags)
        how.writeLong(Layouts.OPEN_HOW_MODE_OFFSET, plan.mode)
        how.writeLong(Layouts.OPEN_HOW_RESOLVE_OFFSET, plan.resolve)
        val path = arena.allocateFrom(plan.path)
        val opened = if (plan.useCurrentWorkingDirectory) {
            SupervisorDescriptorResult.from(engine.fileSystem.openat2(FileDescriptor.AT_FDCWD, path, how, Layouts.OPEN_HOW_SIZE), engine)
        } else {
            when (val imported = descriptors.import(tid, plan.traceeDirfd.value)) {
                is SupervisorDescriptorResult.Denied -> return SupervisorReplyEffect.Error(imported.errno)
                is SupervisorDescriptorResult.Opened -> imported.descriptor.produce {
                    SupervisorDescriptorResult.from(engine.fileSystem.openat2(imported.descriptor.handle, path, how, Layouts.OPEN_HOW_SIZE), engine)
                }
            }
        }
        return when (val result = opened) {
            is SupervisorDescriptorResult.Denied -> SupervisorReplyEffect.Error(result.errno)
            is SupervisorDescriptorResult.Opened ->
                SupervisorReplyEffect.AddFdAndReply(result.descriptor, NewFdFlags.forOpen(io.mazewall.core.OpenFlags(plan.flags.toInt())))
        }
    }
}
