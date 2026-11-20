package io.mazewall.testing

import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.platform.commons.support.AnnotationSupport
import java.lang.reflect.AnnotatedElement

/**
 * Project-wide safety net for reversible globals that tests may replace.
 *
 * Tests opted into [ExecutionMode.CONCURRENT] are child-process probes that do not mutate
 * parent-JVM globals; skipping the reset for them prevents concurrent completions from wiping
 * `LinuxNative`, `Platform`, or `MazewallEvents` while a `SAME_THREAD` test is mid-flight.
 */
class ResetGlobalsExtension : AfterEachCallback {
    override fun afterEach(context: ExtensionContext) {
        if (isConcurrentExecution(context)) {
            return
        }
        GlobalTestState.resetAll()
    }

    internal fun isConcurrentExecution(context: ExtensionContext): Boolean {
        val methodMode = executionModeOf(context.testMethod.orElse(null))
        if (methodMode != null) {
            return methodMode == ExecutionMode.CONCURRENT
        }
        var current: Class<*>? = context.testClass.orElse(null)
        while (current != null) {
            val classMode = executionModeOf(current)
            if (classMode != null) {
                return classMode == ExecutionMode.CONCURRENT
            }
            current = current.enclosingClass
        }
        return false
    }

    private fun executionModeOf(element: AnnotatedElement?): ExecutionMode? = element?.let { AnnotationSupport.findAnnotation(it, Execution::class.java).orElse(null)?.value }
}
