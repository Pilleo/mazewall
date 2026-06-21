package io.mazewall.enforcer.supervisor

public sealed interface ScopingValidationState {
    public data object ClassloaderActive : ScopingValidationState

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
     * Inspects the target thread's stack trace for classloader activity and returns the appropriate type-state.
     *
     * This method must be called before invoking the scoping policy.
     */
    public fun inspect(
        nr: Int,
        argsList: List<Any>,
        targetThread: Thread?
    ): ScopingValidationState {
        val rawStack = targetThread?.stackTrace ?: emptyArray()
        return if (isClassloaderActive(rawStack)) {
            ScopingValidationState.ClassloaderActive
        } else {
            ScopingValidationState.SafeToValidate.create(rawStack, nr, argsList)
        }
    }

    private fun startsWith(str: String, prefix: String): Boolean {
        if (str.length < prefix.length) return false
        for (i in 0 until prefix.length) {
            if (str[i] != prefix[i]) return false
        }
        return true
    }

    /**
     * Returns `true` if [stack] contains a frame from a JVM class-loader class,
     * indicating the thread likely holds the `ClassLoader` monitor.
     *
     * The check is implemented as a primitive indexed loop to guarantee zero new class
     * loads are triggered during validation. Callers must obtain the raw `Array<StackTraceElement>`
     * directly from [Thread.getStackTrace] and pass it here without converting to a `List` first.
     *
     * @param stack The raw stack trace obtained from [Thread.getStackTrace].
     *              Passing an empty array always returns `false`.
     */
    public fun isClassloaderActive(stack: Array<StackTraceElement>): Boolean {
        for (i in 0 until stack.size) {
            val className = stack[i].className ?: continue
            if (startsWith(className, "java.lang.ClassLoader") ||
                startsWith(className, "java.security.SecureClassLoader") ||
                startsWith(className, "jdk.internal.loader.") ||
                startsWith(className, "sun.misc.Launcher")
            ) {
                return true
            }
        }
        return false
    }

    /**
     * Forces the JVM to load this class and all its associated state classes and methods
     * so they are available without triggering class loading during seccomp validation.
     */
    public fun precharge() {
        val stack = emptyArray<StackTraceElement>()
        isClassloaderActive(stack)
        val s1 = ScopingValidationState.ClassloaderActive
        val emptyList = java.util.ArrayList<Any>(0)
        val s2 = ScopingValidationState.SafeToValidate.create(stack, -1, emptyList)
        inspect(-1, emptyList, null)
    }
}


