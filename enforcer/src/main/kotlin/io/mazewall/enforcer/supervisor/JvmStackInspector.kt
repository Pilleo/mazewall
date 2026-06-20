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
 * ### Pre-charge Requirement (OpenJDK Lazy Classloader)
 *
 * This class itself must be loaded **before** the seccomp filter is installed on the sandboxed
 * thread. On OpenJDK's strictly lazy classloader (e.g. Ubuntu 24.04), first-reference classloading
 * of `JvmStackInspector` inside the reactor would attempt to acquire the ClassLoader lock — the
 * exact scenario this class is designed to detect and short-circuit.
 *
 * `installSupervisedFilterForThread` calls `JvmStackInspector.isClassloaderActive(emptyArray())`
 * while the current thread is still unfiltered to satisfy this invariant. This is consistent with
 * the `BpfProgram` pre-charge already established in [io.mazewall.ffi.networking.SupervisorSeccompNotifInstaller].
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

