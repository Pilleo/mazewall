package io.mazewall.testing

import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/** Project-wide safety net for reversible globals that tests may replace. */
class ResetGlobalsExtension : AfterEachCallback {
    override fun afterEach(context: ExtensionContext) {
        GlobalTestState.resetAll()
    }
}
