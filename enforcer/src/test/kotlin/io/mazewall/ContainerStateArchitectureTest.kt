package io.mazewall

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import io.mazewall.enforcer.state.ContainerState

@AnalyzeClasses(packages = ["io.mazewall"], importOptions = [ImportOption.DoNotIncludeTests::class])
class ContainerStateArchitectureTest {

    @ArchTest
    fun containerStateMustRemainNonSubtypeSnapshotType(allClasses: JavaClasses) {
        classes()
            .that()
            .areAssignableTo(ContainerState::class.java)
            .should()
            .haveFullyQualifiedName(ContainerState::class.java.name)
            .because("ContainerState must remain a single non-subtype snapshot type. No subclasses allowed.")
            .check(allClasses)
    }
}
