package io.mazewall.enforcer.internal.supervisor

import io.mazewall.enforcer.api.*
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*
import kotlinx.coroutines.Dispatchers

internal class DummyCoroutineAckViolator {
    fun trigger() {
        val dispatcher = Dispatchers.IO
        println(dispatcher)
    }
}
