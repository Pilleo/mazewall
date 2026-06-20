package io.mazewall.enforcer.supervisor

/**
 * Inspects a raw JVM stack trace to detect whether a thread is actively executing
 * inside a class-loading operation and therefore likely holds the JVM [ClassLoader] monitor.
 *
 * ### Why this exists — the Classloader Lock Deadlock
 *
 * When a sandboxed thread ($T_1$) issues an `openat` syscall during lazy class loading:
 * 1. $T_1$ acquires the JVM `ClassLoader` lock.
 * 2. The seccomp supervisor intercepts the `openat`, blocking $T_1$ in kernel space.
 * 3. The supervisor validation thread ($S_1$) attempts to execute policy code.
 * 4. If that policy code (or any Kotlin stdlib helper it calls) itself requires a class load,
 *    $S_1$ blocks waiting for the same `ClassLoader` lock that $T_1$ holds — **deadlock**.
 *
 * This inspector solves the problem by letting $S_1$ detect classloading frames *before*
 * invoking any code that could trigger its own classloading. When detected, the syscall is
 * immediately allowed, releasing $T_1$ so it can finish class loading and release the lock.
 *
 * ### Safety Invariants
 *
 * > [!CAUTION]
 * > Every method in this object MUST use only primitive Java constructs:
 * > raw array indexing, plain `for` loops over indices, and `String` literal comparisons.
 * > **No Kotlin stdlib extensions. No lambdas. No collection allocations.**
 * > Violating this constraint reintroduces the exact deadlock this class is designed to prevent,
 * > because Kotlin collection helpers may themselves require classloading on first use.
 *
 * ### Why `jdk.internal.reflect.*` is intentionally excluded
 *
 * There are two distinct scenarios where reflection appears on the stack:
 *
 * **A. Reflection-triggered class loading** (`Class.forName`, `Constructor.newInstance`):
 * These always flow through `ClassLoader.loadClass()`, which means `java.lang.ClassLoader`
 * is already on the stack. The existing prefix check catches them.
 *
 * **B. Pure method invocation** (`Method.invoke` on an already-loaded class):
 * The `ClassLoader` lock is **not held**. Adding `jdk.internal.reflect.` would
 * unconditionally bypass the scoping policy for any file open during any reflective call —
 * a significant security regression. It is deliberately absent.
 */
public object JvmStackInspector {

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
            val className = stack[i].className
            if (className.startsWith("java.lang.ClassLoader") ||
                className.startsWith("java.security.SecureClassLoader") ||
                className.startsWith("jdk.internal.loader.") ||
                className.startsWith("sun.misc.Launcher")
            ) {
                return true
            }
        }
        return false
    }
}
