package io.mazewall.enforcer

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.diagnostics.ViolationMatcher
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*

class TestFatalServiceViolationMatcher : ViolationMatcher {
    init {
        throw AssertionError("fatal matcher initialization")
    }

    override fun matches(t: Throwable): Boolean = false
}
