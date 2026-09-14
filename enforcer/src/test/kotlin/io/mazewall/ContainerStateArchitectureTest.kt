package io.mazewall.enforcer.api

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.state.ContainerState
import io.mazewall.platform.fd.*
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*

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
