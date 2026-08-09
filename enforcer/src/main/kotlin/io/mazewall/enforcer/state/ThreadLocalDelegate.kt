package io.mazewall.enforcer.state

import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.*
import io.mazewall.enforcer.diagnostics.*
import io.mazewall.enforcer.engine.*
import io.mazewall.enforcer.*

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * A property delegate for [ThreadLocal] values to provide clean syntactic access.
 */
public class ThreadLocalDelegate<T>(private val threadLocal: ThreadLocal<T>) : ReadWriteProperty<Any?, T> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): T = threadLocal.get()
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = threadLocal.set(value)
}

/**
 * Convenience builder for [ThreadLocalDelegate].
 */
public fun <T> threadLocal(initialValue: () -> T): ThreadLocalDelegate<T> =
    ThreadLocalDelegate(ThreadLocal.withInitial(initialValue))
