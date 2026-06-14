package io.mazewall.enforcer

import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * A property delegate for [ThreadLocal] values to provide clean syntactic access.
 */
internal class ThreadLocalDelegate<T>(private val threadLocal: ThreadLocal<T>) : ReadWriteProperty<Any?, T> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): T = threadLocal.get()
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = threadLocal.set(value)
}

/**
 * Convenience builder for [ThreadLocalDelegate].
 */
internal fun <T> threadLocal(initialValue: () -> T): ThreadLocalDelegate<T> =
    ThreadLocalDelegate(ThreadLocal.withInitial(initialValue))
