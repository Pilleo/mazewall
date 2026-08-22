package io.mazewall.ffi

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.foreign.MemoryLayout
import java.lang.foreign.ValueLayout

class LayoutValidatorTest {

    @Test
    fun `validate passes cleanly on standard layouts`() {
        assertDoesNotThrow {
            LayoutValidator.validate()
        }
    }

    @Test
    fun `validateLayout detects size mismatch`() {
        val dummyLayout = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("a"),
        )
        val ex = assertThrows(IllegalStateException::class.java) {
            LayoutValidator.validateLayout(dummyLayout, expectedSize = 8, expectedAlignment = 4) {
                assertOffset("a", 0)
            }
        }
        assertTrue(ex.message!!.contains("expected size 8 but got 4"))
    }

    @Test
    fun `validateLayout detects alignment mismatch`() {
        val dummyLayout = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("a"),
        )
        val ex = assertThrows(IllegalStateException::class.java) {
            LayoutValidator.validateLayout(dummyLayout, expectedSize = 8, expectedAlignment = 4) {
                assertOffset("a", 0)
            }
        }
        assertTrue(ex.message!!.contains("expected alignment 4 but got 8"))
    }

    @Test
    fun `validateLayout detects offset mismatch`() {
        val dummyLayout = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("a"),
            ValueLayout.JAVA_INT.withName("b"),
        )
        val ex = assertThrows(IllegalStateException::class.java) {
            LayoutValidator.validateLayout(dummyLayout, expectedSize = 8, expectedAlignment = 4) {
                assertOffset("a", 0)
                assertOffset("b", 8) // Wrong offset, should be 4
            }
        }
        assertTrue(ex.message!!.contains("expected offset 8 but got 4"))
    }

    @Test
    fun `validateLayout detects missing field`() {
        val dummyLayout = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("a"),
        )
        val ex = assertThrows(IllegalStateException::class.java) {
            LayoutValidator.validateLayout(dummyLayout, expectedSize = 4, expectedAlignment = 4) {
                assertOffset("nonexistent", 0)
            }
        }
        assertTrue(ex.message!!.contains("Field 'nonexistent' not found in layout"))
    }
}
