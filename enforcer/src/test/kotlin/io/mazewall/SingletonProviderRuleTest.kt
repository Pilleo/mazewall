package io.mazewall

import com.tngtech.archunit.core.importer.ClassFileImporter
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SingletonProviderRuleTest {
    @Test
    fun `a mutable provider on an object violates singleton ownership`() {
        val result = singletonProviderRule().evaluate(ClassFileImporter().importClasses(MutableObject::class.java))
        assertTrue(result.hasViolation(), "mutable singleton provider must be rejected")
    }

    @Test
    fun `a mutable companion provider violates singleton ownership`() {
        val result = singletonProviderRule().evaluate(ClassFileImporter().importClasses(MutableCompanion::class.java, MutableCompanion.Companion::class.java))
        assertTrue(result.hasViolation(), "mutable companion provider must be rejected")
    }

    @Test
    fun `an immutable singleton provider and an instance provider are allowed`() {
        val result = singletonProviderRule().evaluate(ClassFileImporter().importClasses(ImmutableObject::class.java, InstanceProvider::class.java))
        assertFalse(result.hasViolation(), result.failureReport.toString())
    }

    interface ExampleProvider

    object MutableObject {
        var provider: ExampleProvider? = null
    }

    class MutableCompanion {
        companion object {
            var provider: ExampleProvider? = null
        }
    }

    object ImmutableObject {
        val provider: ExampleProvider? = null
    }

    class InstanceProvider {
        var provider: ExampleProvider? = null
    }
}
