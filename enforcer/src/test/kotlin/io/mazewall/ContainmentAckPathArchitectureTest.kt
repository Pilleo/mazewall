package io.mazewall

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@AnalyzeClasses(packages = ["io.mazewall"], importOptions = [ImportOption.DoNotIncludeTests::class])
class ContainmentAckPathArchitectureTest {

    private val rule = noClasses()
        .that()
        .resideInAnyPackage(
            "io.mazewall.enforcer.supervisor..",
            "io.mazewall.seccomp..",
            "io.mazewall.landlock..",
            "io.mazewall.enforcer.state..",
            "io.mazewall.enforcer.engine..",
        )
        .should()
        .dependOnClassesThat()
        .resideInAPackage("kotlinx.coroutines..")
        .because("Putting coroutines on the ACK path can deadlock USER_NOTIF and poison Loom carrier threads")

    @ArchTest
    fun coroutinesMustBeBannedOnAckPath(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        rule.check(allClasses)
    }

    @Test
    fun coroutinesBannedOnAckPathDetectsViolation() {
        val classes = ClassFileImporter().importClasses(io.mazewall.enforcer.supervisor.DummyCoroutineAckViolator::class.java)
        assertThrows<AssertionError> {
            rule.check(classes)
        }
    }
}
