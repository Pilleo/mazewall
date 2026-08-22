package io.mazewall.enforcer.supervisor

import kotlinx.coroutines.Dispatchers

@Suppress("unused")
internal class DummyCoroutineAckViolator {
    fun trigger() {
        val dispatcher = Dispatchers.IO
        println(dispatcher)
    }
}
