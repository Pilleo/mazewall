package io.mazewall.enforcer.supervisor

public object JvmStackInspector {

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
}


