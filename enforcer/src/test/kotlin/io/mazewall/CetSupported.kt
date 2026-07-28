package io.mazewall

import org.junit.jupiter.api.extension.ConditionEvaluationResult
import org.junit.jupiter.api.extension.ExecutionCondition
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.extension.ExtensionContext

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@ExtendWith(CetSupportedCondition::class)
public annotation class EnabledIfCetSupported

public class CetSupportedCondition : ExecutionCondition {
    override fun evaluateExecutionCondition(context: ExtensionContext): ConditionEvaluationResult {
        val osName = System.getProperty("os.name")
        return when {
            !osName.equals("Linux", ignoreCase = true) ->
                ConditionEvaluationResult.disabled("Only supported on Linux (current: $osName)")

            !Platform.isCpuCetSupported() ->
                ConditionEvaluationResult.disabled("Intel CET is not supported by CPU or Kernel on this environment")

            else ->
                ConditionEvaluationResult.enabled("Intel CET is supported")
        }
    }
}
