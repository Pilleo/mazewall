package io.mazewall.enforcer.supervisor

public sealed interface ScopingValidationState {

    public class SafeToValidate private constructor(
        public val rawStack: Array<StackTraceElement>,
        public val nr: Int,
        public val argsList: List<Any>
    ) : ScopingValidationState {
        internal companion object {
            fun create(rawStack: Array<StackTraceElement>, nr: Int, argsList: List<Any>) =
                SafeToValidate(rawStack, nr, argsList)
        }
    }
}

public object JvmStackInspector {

    /**
     * Inspects the target thread's stack trace and returns the appropriate type-state.
     *
     * This method must be called before invoking the scoping policy.
     */
    public fun inspect(
        nr: Int,
        argsList: List<Any>,
        targetThread: Thread?
    ): ScopingValidationState {
        val rawStack = targetThread?.stackTrace ?: emptyArray()
        return ScopingValidationState.SafeToValidate.create(rawStack, nr, argsList)
    }
}
