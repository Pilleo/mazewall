package io.mazewall

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BillOfBehaviorDtoCoverageTest {
    @Test
    fun `test DTO constructors and properties`() {
        val frame = StackFrameDto(
            classLoader = "app",
            module = "java.base",
            moduleVersion = "11",
            className = "MyClass",
            methodName = "myMethod",
            fileName = "MyFile.kt",
            lineNumber = 10
        )
        assertEquals("app", frame.classLoader)
        assertEquals("java.base", frame.module)
        assertEquals("11", frame.moduleVersion)
        assertEquals("MyClass", frame.className)
        assertEquals("myMethod", frame.methodName)
        assertEquals("MyFile.kt", frame.fileName)
        assertEquals(10, frame.lineNumber)

        val entry = StackProfileEntryDto(
            syscall = "OPEN",
            paths = listOf("/tmp"),
            args = listOf(1L),
            stackTrace = listOf(frame)
        )
        assertEquals("OPEN", entry.syscall)
        assertEquals(listOf("/tmp"), entry.paths)
        assertEquals(listOf(1L), entry.args)
        assertEquals(listOf(frame), entry.stackTrace)

        val bob = BillOfBehaviorDto(
            opens = setOf("/read"),
            fsWritePaths = setOf("/write"),
            syscalls = setOf("OPEN"),
            execs = setOf("/bin/ls"),
            stackProfile = listOf(entry)
        )
        assertEquals(setOf("/read"), bob.opens)
        assertEquals(setOf("/write"), bob.fsWritePaths)
        assertEquals(setOf("OPEN"), bob.syscalls)
        assertEquals(setOf("/bin/ls"), bob.execs)
        assertEquals(listOf(entry), bob.stackProfile)
    }
}
