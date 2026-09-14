package io.mazewall.profiler.internal.engine

import io.mazewall.profiler.api.SyscallEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.KVisibility

/** Exercises the public Kotlin construction contract through the reflection runtime. */
internal object SyscallEventConstructorContract {
    fun construct(values: Map<String, Any?>): SyscallEvent<*> {
        val type = SyscallEvent::class
        assertEquals(KVisibility.PUBLIC, type.visibility)
        val constructor =
            Class
                .forName("kotlin.reflect.full.KClasses")
                .getMethod("getPrimaryConstructor", KClass::class.java)
                .invoke(null, type) as KFunction<*>
        assertNotNull(constructor)
        assertEquals(KVisibility.PUBLIC, constructor.visibility)
        val parameters = constructor.parameters
        assertEquals(listOf("tid", "syscallName", "args", "paths", "stackTrace"), parameters.map(KParameter::name))
        assertEquals(listOf("paths", "stackTrace"), parameters.filter(KParameter::isOptional).map(KParameter::name))
        assertEquals(
            listOf(
                "io.mazewall.platform.lifecycle.Tid",
                "kotlin.String",
                "kotlin.collections.List<kotlin.Long>",
                "kotlin.collections.List<kotlin.String>",
                "kotlin.collections.List<kotlin.String>?",
            ),
            parameters.map { it.type.toString() },
        )
        val arguments = parameters.filter { values.containsKey(it.name) }.associateWith { values[it.name] }
        return constructor.callBy(arguments) as SyscallEvent<*>
    }
}
