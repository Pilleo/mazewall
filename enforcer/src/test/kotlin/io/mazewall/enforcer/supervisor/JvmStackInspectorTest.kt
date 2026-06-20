package io.mazewall.enforcer.supervisor

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmStackInspectorTest {

    @Test
    fun `returns false for empty stack`() {
        assertFalse(JvmStackInspector.isClassloaderActive(emptyArray()))
    }

    @Test
    fun `returns false for normal application stack`() {
        val stack = arrayOf(
            StackTraceElement("io.mazewall.enforcer.ContainedExecutors", "wrap", "ContainedExecutors.kt", 42),
            StackTraceElement("io.mazewall.test.WorkloadTest", "doWork", "WorkloadTest.kt", 10),
            StackTraceElement("java.lang.Thread", "run", "Thread.java", 1),
        )
        assertFalse(JvmStackInspector.isClassloaderActive(stack))
    }

    @Test
    fun `returns true when java_lang_ClassLoader is on stack`() {
        val stack = arrayOf(
            StackTraceElement("java.io.FileInputStream", "open0", "FileInputStream.java", -2),
            StackTraceElement("java.io.FileInputStream", "open", "FileInputStream.java", 195),
            StackTraceElement("java.lang.ClassLoader", "loadClass", "ClassLoader.java", 520),
            StackTraceElement("io.mazewall.test.WorkloadTest", "doWork", "WorkloadTest.kt", 10),
        )
        assertTrue(JvmStackInspector.isClassloaderActive(stack))
    }

    @Test
    fun `returns true for SecureClassLoader subclass on stack`() {
        val stack = arrayOf(
            StackTraceElement("java.security.SecureClassLoader", "loadClass", "SecureClassLoader.java", 100),
            StackTraceElement("io.mazewall.test.WorkloadTest", "doWork", "WorkloadTest.kt", 10),
        )
        assertTrue(JvmStackInspector.isClassloaderActive(stack))
    }

    @Test
    fun `returns true for jdk_internal_loader frames`() {
        val stack = arrayOf(
            StackTraceElement("jdk.internal.loader.BuiltinClassLoader", "loadClass", "BuiltinClassLoader.java", 641),
            StackTraceElement("jdk.internal.loader.ClassLoaders\$AppClassLoader", "loadClass", "ClassLoaders.java", 188),
            StackTraceElement("io.mazewall.test.WorkloadTest", "doWork", "WorkloadTest.kt", 10),
        )
        assertTrue(JvmStackInspector.isClassloaderActive(stack))
    }

    @Test
    fun `returns true for sun_misc_Launcher frames`() {
        val stack = arrayOf(
            StackTraceElement("sun.misc.Launcher\$AppClassLoader", "loadClass", "Launcher.java", 350),
            StackTraceElement("io.mazewall.test.WorkloadTest", "doWork", "WorkloadTest.kt", 10),
        )
        assertTrue(JvmStackInspector.isClassloaderActive(stack))
    }

    @Test
    fun `returns false for jdk_internal_reflect frames without ClassLoader — no bypass for pure reflection`() {
        // Scenario: Method.invoke on an already-loaded class. ClassLoader lock is NOT held.
        // Adding jdk.internal.reflect would be a security bypass — it must NOT be included.
        val stack = arrayOf(
            StackTraceElement("jdk.internal.reflect.NativeMethodAccessorImpl", "invoke0", "NativeMethodAccessorImpl.java", -2),
            StackTraceElement("jdk.internal.reflect.NativeMethodAccessorImpl", "invoke", "NativeMethodAccessorImpl.java", 77),
            StackTraceElement("jdk.internal.reflect.DelegatingMethodAccessorImpl", "invoke", "DelegatingMethodAccessorImpl.java", 43),
            StackTraceElement("java.lang.reflect.Method", "invoke", "Method.java", 568),
            StackTraceElement("io.mazewall.test.EvilPayload", "exfiltrate", "EvilPayload.kt", 5),
        )
        assertFalse(
            JvmStackInspector.isClassloaderActive(stack),
            "Pure reflection invocation must NOT bypass the scoping policy — ClassLoader lock is not held"
        )
    }

    @Test
    fun `returns true when classloader frame is buried deep in stack`() {
        val stack = arrayOf(
            StackTraceElement("io.mazewall.test.WorkloadTest", "alpha", "WorkloadTest.kt", 1),
            StackTraceElement("io.mazewall.test.WorkloadTest", "beta", "WorkloadTest.kt", 2),
            StackTraceElement("io.mazewall.test.WorkloadTest", "gamma", "WorkloadTest.kt", 3),
            StackTraceElement("io.mazewall.test.WorkloadTest", "delta", "WorkloadTest.kt", 4),
            StackTraceElement("java.lang.ClassLoader", "loadClass", "ClassLoader.java", 520),
        )
        assertTrue(JvmStackInspector.isClassloaderActive(stack))
    }
}
