package io.mazewall.enforcer.supervisor

import kotlinx.coroutines.Dispatchers

internal class DummyCoroutineAckViolator {
    fun trigger() {
        val dispatcher = Dispatchers.IO
        println(dispatcher)
    }
}
