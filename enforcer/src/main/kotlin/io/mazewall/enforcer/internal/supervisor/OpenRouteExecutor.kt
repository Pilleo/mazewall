package io.mazewall.enforcer.internal.supervisor

import io.mazewall.ffi.Layouts
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.writeLong
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.lifecycle.NewFdFlags
import io.mazewall.platform.lifecycle.Tid
import io.mazewall.platform.native.NativeEngine

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
        val opened = if (plan.path.startsWith('/')) {
            SupervisorDescriptorResult.from(engine.fileSystem.openat2(FileDescriptor.AT_FDCWD, path, how, Layouts.OPEN_HOW_SIZE), engine)
        } else {
            val directory = if (plan.useCurrentWorkingDirectory) {
                SupervisorDescriptorResult.from(
                    engine.fileSystem.open(
                        arena.allocateFrom("/proc/${tid.value}/cwd"),
                        io.mazewall.platform.lifecycle
                            .OpenFlags(io.mazewall.ffi.NativeConstants.O_PATH or io.mazewall.ffi.NativeConstants.O_CLOEXEC),
                    ),
                    engine,
                )
            } else {
                descriptors.import(tid, plan.traceeDirfd.value)
            }
            when (val imported = directory) {
                is SupervisorDescriptorResult.Denied -> return SupervisorReplyEffect.Error(imported.errno)
                is SupervisorDescriptorResult.Opened -> imported.descriptor.produce {
                    SupervisorDescriptorResult.from(engine.fileSystem.openat2(imported.descriptor.handle, path, how, Layouts.OPEN_HOW_SIZE), engine)
                }
            }
        }
        return when (val result = opened) {
            is SupervisorDescriptorResult.Denied -> SupervisorReplyEffect.Error(result.errno)
            is SupervisorDescriptorResult.Opened ->
                SupervisorReplyEffect.AddFdAndReply(
                    result.descriptor,
                    NewFdFlags.forOpen(
                        io.mazewall.platform.lifecycle
                    .OpenFlags(plan.flags.toInt()),
                    ),
                )
        }
    }
}
