package io.mazewall.testing

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/** Project-wide safety net for reversible globals that tests may replace. */
class ResetGlobalsExtension : AfterEachCallback {
    override fun afterEach(context: ExtensionContext) {
        GlobalTestState.resetAll()
    }
}
