package io.mazewall.enforcer.supervisor

import io.mazewall.enforcer.api.ContainmentViolationException
import io.mazewall.ffi.networking.SupervisorValidationChannel

/**
 * Loads every type the JVM validation ACK path needs **before** seccomp is installed.
 *
 * Dummy I/O warmup is not a fix: if a class is still unloaded when USER_NOTIF
 * fires, the listener can block on the ClassLoader lock while the tracee waits
 * in the kernel. Fail closed if a listed type cannot be initialized.
 */
internal object ValidationListenerPreload {
    internal val requiredBinaryNames: List<String> =
        listOf(
            JVMValidationListener::class.java.name,
            JvmStackInspector::class.java.name,
            ScopingValidationState::class.java.name,
            ScopingValidationState.SafeToValidate::class.java.name,
            DefaultStacktraceScopingPolicy::class.java.name,
            StacktraceScopingPolicy::class.java.name,
            SupervisorValidationChannel::class.java.name,
            ContainmentViolationException::class.java.name,
            @Suppress("DEPRECATION")
            io.mazewall.enforcer.ContainmentViolationException::class.java.name,
            SupervisorSessionHandler::class.java.name,
            SupervisorNotificationMachine::class.java.name,
            JvmVerdict.Deny::class.java.name,
            JvmVerdict.Allow::class.java.name,
            JvmVerdict.InjectFd::class.java.name,
            io.mazewall.platform.seccomp.SupervisedKind.Open::class.java.name,
            io.mazewall.platform.seccomp.SupervisedKind.Connect::class.java.name,
            io.mazewall.platform.seccomp.SupervisedKind.Accept::class.java.name,
            io.mazewall.platform.seccomp.SupervisedKind.Exec::class.java.name,
            io.mazewall.platform.seccomp.SupervisedKind.Spawn::class.java.name,
            io.mazewall.platform.seccomp.SupervisedKind.Unknown::class.java.name,
            ValidationLog::class.java.name,
        )

    fun ensureLoaded() {
        val cl = ValidationListenerPreload::class.java.classLoader
        for (name in requiredBinaryNames) {
            Class.forName(name, true, cl)
        }
    }
}
