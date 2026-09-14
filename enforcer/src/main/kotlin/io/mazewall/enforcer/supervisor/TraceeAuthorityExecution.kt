package io.mazewall.enforcer.supervisor

import io.mazewall.NativeEngine
import io.mazewall.core.Arch
import io.mazewall.core.NewFdFlags
import io.mazewall.core.OpenFlags
import io.mazewall.core.SyscallNumber
import io.mazewall.core.Tid
import io.mazewall.core.isUnder
import io.mazewall.ffi.Layouts
import io.mazewall.ffi.NativeConstants
import io.mazewall.ffi.memory.NativeArena
import io.mazewall.ffi.memory.writeLong
import io.mazewall.platform.seccomp.SeccompNotification
import io.mazewall.platform.seccomp.SupervisedKind
import java.nio.file.Path

/** Helper-only native execution against captured capabilities and the reactor's explicit route decision. */
internal class TraceeAuthorityExecution(
    private val engine: NativeEngine,
    private val authority: TraceeProcAuthority,
    private val notification: SeccompNotification,
    roots: List<String>,
) {
    private val arch = Arch.fromAudit(notification.arch)
    private val kind = SupervisedKind.classify(SyscallNumber(notification.nr), arch)
    private val bypass = roots.map(Path::of)
    private var capturedArguments: SyscallArguments? = null
    private var execPreparationErrno: Int? = null
    private val arguments: SyscallArguments get() = checkNotNull(capturedArguments)

    context(arena: NativeArena) fun prepare(): TraceeAuthoritySnapshot {
        capturedArguments = SupervisorArgumentExtractor.extract(
            notification.nr,
            Tid(notification.pid),
            notification.args,
            arch,
            readString = { _, address -> readPath(address) },
            readBytes = { _, address, length -> authority.read(address, length) },
        )
        val resolved = if (kind is SupervisedKind.Open && arguments.pathStr != null) resolve(checkNotNull(arguments.pathStr)) else null
        if (kind is SupervisedKind.Exec && !arguments.pathStr.isNullOrEmpty()) {
            authority.directory(arguments.dirfd, checkNotNull(arguments.pathStr).startsWith('/'))
        }
        val route = when (kind) {
            is SupervisedKind.Open -> when {
                resolved == null -> SupervisorRoute.Abort(NativeConstants.EPERM, "unresolved open path")
                bypass.any { isUnder(resolved, it) } -> SupervisorRoute.InjectFd
                else -> SupervisorRoute.AskJvm
            }
            is SupervisedKind.Exec, is SupervisedKind.Spawn, is SupervisedKind.Connect, is SupervisedKind.Accept -> SupervisorRoute.AskJvm
            is SupervisedKind.Unknown -> SupervisorRoute.Abort(NativeConstants.EPERM, "unsupported supervised operation")
        }
        return TraceeAuthoritySnapshot(authority.ppid, arguments.pathStr, resolved?.toString(), route, arguments.sockaddrBytes)
    }

    /** Parent-stack authorization is independent of pathname readability; execution is not. */
    context(arena: NativeArena) private fun readPath(address: Long): String? =
        try {
            authority.readPath(address)
        } catch (failure: TraceeAuthorityFailure) {
            if (kind !is SupervisedKind.Exec) throw failure
            execPreparationErrno = failure.errno
            null
        }

    context(arena: NativeArena) private fun resolve(path: String): Path? {
        val directory = authority.directory(arguments.dirfd, path.startsWith('/'))
        val base = Path.of("/proc/self/fd/${directory.handle.value}").toRealPath()
        val candidate = base.resolve(if (path.startsWith('/')) path.trimStart('/') else path)
        return when (val resolution = BypassPaths.resolveForPolicy(candidate)) {
            is BypassPaths.PathResolution.Resolved ->
                resolution.path.takeIf { path.startsWith('/') || isUnder(it, base) }
            is BypassPaths.PathResolution.Missing,
            is BypassPaths.PathResolution.Unsafe,
            -> null
        }
    }

    context(arena: NativeArena) fun execute(decision: TraceeAuthorityDecision): SupervisorReplyEffect =
        when (val route = decision.route) {
        is SupervisorRoute.Abort -> SupervisorReplyEffect.Error(route.errno)
        SupervisorRoute.Continue -> if (kind is SupervisedKind.Spawn) SupervisorReplyEffect.Continue else SupervisorReplyEffect.Error()
        SupervisorRoute.AskJvm -> SupervisorReplyEffect.Error()
        // accept(2) must execute in the tracee so the kernel, under the original
        // task identity, copies peer output and enforces writable-page semantics.
        SupervisorRoute.InjectFd -> if (kind is SupervisedKind.Accept) SupervisorReplyEffect.Continue else inject()
        SupervisorRoute.SecureExec -> if (kind is SupervisedKind.Exec) secureExec(decision.path) else SupervisorReplyEffect.Error()
    }

    context(arena: NativeArena) private fun inject(): SupervisorReplyEffect =
        when (val plan = SupervisorInjectionPlan.create(notification.nr, arch, notification.args, arguments)) {
        is SupervisorInjectionPlan.Deny -> SupervisorReplyEffect.Error(plan.errno)
        is SupervisorInjectionPlan.Open -> {
            val open = SupervisorOpenPlan.create(plan.request)
            descriptorEffect(openAt(open.path, open.flags, open.mode, open.resolve), NewFdFlags.forOpen(OpenFlags(open.flags.toInt())))
        }
        is SupervisorInjectionPlan.Connect -> authority.import(notification.args[0].toInt()).use { socket ->
            when (val result = SocketOperationExecutor(engine).execute(SocketOperation.Connect(plan.sockaddr), socket)) {
                SocketHelperResult.Connected -> SupervisorReplyEffect.Value(0)
                is SocketHelperResult.Failed -> SupervisorReplyEffect.Error(result.errno)
                is SocketHelperResult.Opened, is SocketHelperResult.Accepted, is SocketHelperResult.Authority -> {
                    result.dispose()
                    error("Unexpected connect result")
                }
            }
        }
        SupervisorInjectionPlan.Accept -> accept()
    }

    context(arena: NativeArena) private fun accept(): SupervisorReplyEffect {
        // process_vm_writev has no pidfd variant. /proc/mem instead forces writes through
        // read-only mappings, changing accept's EFAULT semantics. Do neither implicitly.
        if (notification.args[1] != 0L) {
            return SupervisorReplyEffect.UnsupportedAuthority(UnsupportedTraceeAuthority.AcceptPeerMemory)
        }
        val flags = if (notification.nr == arch.accept4) notification.args[3].toInt() else 0
        return authority.import(notification.args[0].toInt()).use { socket ->
            when (val result = SocketOperationExecutor(engine).execute(SocketOperation.Accept(flags), socket)) {
                is SocketHelperResult.Failed -> SupervisorReplyEffect.Error(result.errno)
                is SocketHelperResult.Accepted -> SupervisorReplyEffect.AddFdAndReply(result.descriptor, NewFdFlags.forAccept(flags))
                SocketHelperResult.Connected, is SocketHelperResult.Opened, is SocketHelperResult.Authority -> {
                    result.dispose()
                    error("Unexpected accept result")
                }
            }
        }
    }

    context(arena: NativeArena) private fun secureExec(jvmPath: String?): SupervisorReplyEffect {
        val args = notification.args
        val request = SecureExecRequest(Tid(notification.pid), args[if (notification.nr == arch.execve) 1 else 2], args[if (notification.nr == arch.execve) 2 else 3], arch.execveat.toLong())
        execPreparationErrno?.let { errno ->
            return SupervisorReplyEffect.SecureExecAndReply(request, SupervisorDescriptorResult.Denied(errno), 0)
        }
        return captureExecutable(request, jvmPath)
    }

    context(arena: NativeArena) private fun captureExecutable(
        request: SecureExecRequest,
        jvmPath: String?,
    ): SupervisorReplyEffect {
        val args = notification.args
        val path = when (val plan = planExecRewrite(arch, arguments.pathStr, jvmPath)) {
            is ExecRewritePlan.Ready -> plan.path
            ExecRewritePlan.MissingPath, ExecRewritePlan.UnsupportedArch -> return SupervisorReplyEffect.SecureExecAndReply(request, SupervisorDescriptorResult.Denied(NativeConstants.EPERM), 0)
        }
        val nul = authority.readOnlyNul()
        if (nul == 0L) return SupervisorReplyEffect.SecureExecAndReply(request, SupervisorDescriptorResult.Denied(NativeConstants.EPERM), 0)
        val execFlags = if (notification.nr == arch.execveat) args[4].toInt() else 0
        val target = if (path.isEmpty() && execFlags and NativeConstants.AT_EMPTY_PATH != 0) {
            SupervisorDescriptorResult.Opened(authority.import(arguments.dirfd.value))
        } else {
            val flags = NativeConstants.O_PATH or NativeConstants.O_CLOEXEC or if (execFlags and NativeConstants.AT_SYMLINK_NOFOLLOW != 0) NativeConstants.O_NOFOLLOW else 0
            openAt(path, flags.toLong(), 0, 0)
        }
        return SupervisorReplyEffect.SecureExecAndReply(request, target, nul)
    }

    context(arena: NativeArena) private fun openAt(
        path: String,
        flags: Long,
        mode: Long,
        resolve: Long,
    ): SupervisorDescriptorResult {
        val absolute = path.startsWith('/')
        // Retain the existing openat2 BENEATH rejection for an absolute input.
        if (absolute && resolve and NativeConstants.RESOLVE_BENEATH.toLong() != 0L) return SupervisorDescriptorResult.Denied(NativeConstants.EXDEV)
        val directory = authority.directory(arguments.dirfd, absolute)
        val how = arena.allocate(Layouts.OPEN_HOW_SIZE)
        how.writeLong(Layouts.OPEN_HOW_FLAGS_OFFSET, flags)
        how.writeLong(Layouts.OPEN_HOW_MODE_OFFSET, mode)
        val confinement = if (absolute) NativeConstants.RESOLVE_IN_ROOT else NativeConstants.RESOLVE_BENEATH
        how.writeLong(Layouts.OPEN_HOW_RESOLVE_OFFSET, resolve or confinement)
        return SupervisorDescriptorResult.from(engine.fileSystem.openat2(directory.handle, arena.allocateFrom(path), how, Layouts.OPEN_HOW_SIZE), engine)
    }

    private fun descriptorEffect(
        result: SupervisorDescriptorResult,
        flags: NewFdFlags,
    ): SupervisorReplyEffect =
        when (result) {
        is SupervisorDescriptorResult.Denied -> SupervisorReplyEffect.Error(result.errno)
        is SupervisorDescriptorResult.Opened -> SupervisorReplyEffect.AddFdAndReply(result.descriptor, flags)
    }
}
