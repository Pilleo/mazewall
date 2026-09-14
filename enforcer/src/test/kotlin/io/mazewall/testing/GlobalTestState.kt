package io.mazewall.testing

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.BpfNativeCache
import io.mazewall.enforcer.api.Platform
import io.mazewall.enforcer.api.PolicyCompilationCache
import io.mazewall.enforcer.diagnostics.MazewallEvents
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.native.LinuxNative
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import io.mazewall.seccomp.InstallSelfVerifier

/** Resets reversible process-wide test seams after every JUnit test. */
internal object GlobalTestState {
    fun resetAll() {
        InstallSelfVerifier.reset()
        BpfNativeCache.clear()
        PolicyCompilationCache.clear()
        MazewallEvents.clear()
        MazewallEvents.failOnListenerError = false
        Platform.resetToDefault()
        LinuxNative.resetToDefault()
    }
}
