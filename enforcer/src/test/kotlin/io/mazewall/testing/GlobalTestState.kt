package io.mazewall.testing

import io.mazewall.BpfNativeCache
import io.mazewall.LinuxNative
import io.mazewall.Platform
import io.mazewall.PolicyCompilationCache
import io.mazewall.enforcer.diagnostics.MazewallEvents
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
