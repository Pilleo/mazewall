package io.mazewall.enforcer

import io.mazewall.enforcer.diagnostics.ViolationMatcher

class TestFatalServiceViolationMatcher : ViolationMatcher {
    init {
        throw AssertionError("fatal matcher initialization")
    }

    override fun matches(t: Throwable): Boolean = false
}
