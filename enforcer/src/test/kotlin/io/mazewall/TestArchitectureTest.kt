package io.mazewall

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.mazewall.enforcer.api.ContainedExecutors
import io.mazewall.seccomp.PureJavaBpfEngine
import org.junit.jupiter.api.Test

/**
 * Protects the Gradle Test Worker from being poisoned by process-wide seccomp filters
 * accidentally applied in unit tests.
 *
 * Process-wide containment (ContainedExecutors.installOnProcess or PureJavaBpfEngine.installOnProcess)
 * is irreversible and will affect ALL subsequent tests in the same JVM.
 * Such tests MUST use [io.mazewall.IsolatedProcessTester] to run in a dedicated process.
 */
@AnalyzeClasses(packages = ["io.mazewall"], importOptions = [ImportOption.OnlyIncludeTests::class])
class TestArchitectureTest {
    @ArchTest
    fun processWideContainmentMustOnlyBeCalledInIsolatedProcesses(allClasses: JavaClasses) {
        methods()
            .that()
            .areAnnotatedWith(Test::class.java)
            .and()
            .areDeclaredInClassesThat()
            .doNotHaveFullyQualifiedName("io.mazewall.IsolatedTestRunner")
            .and()
            .areDeclaredInClassesThat()
            .doNotHaveFullyQualifiedName(
                "io.mazewall.seccomp.PureJavaBpfEngineThreadStateSynchronizationTest",
            )
            .should(
                object : ArchCondition<com.tngtech.archunit.core.domain.JavaMethod>(
                    "not call installOnProcess in-process",
                ) {
                    override fun check(
                        item: com.tngtech.archunit.core.domain.JavaMethod,
                        events: ConditionEvents,
                    ) {
                        item.methodCallsFromSelf
                            .filter { call ->
                                call.target.name == "installOnProcess" &&
                                    (
                                        call.targetOwner.isAssignableTo(ContainedExecutors::class.java) ||
                                            call.targetOwner.isAssignableTo(PureJavaBpfEngine::class.java) ||
                                            call.targetOwner.name == "io.mazewall.enforcer.ContainedExecutors"
                                        )
                            }
                            .forEach { call ->
                                events.add(
                                    SimpleConditionEvent.violated(
                                        item,
                                        "${item.fullName} calls ${call.target.fullName} in-process; " +
                                            "use IsolatedProcessTester so supervisor spawn is not inherited-filter poisoned.",
                                    ),
                                )
                            }
                    }
                },
            )
            .because(
                "A @Test method that calls installOnProcess in-process poisons later tests and " +
                    "supervisor daemon children (inherited seccomp). Run the body via IsolatedProcessTester.",
            ).check(allClasses)
    }

    @ArchTest
    fun ffmApiMustBeIsolatedToFfiPackage(allClasses: JavaClasses) {
        noClasses()
            .that()
            .resideOutsideOfPackage("io.mazewall.ffi..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("java.lang.foreign..")
            .because("The FFM API usage must be isolated to the io.mazewall.ffi package to maintain compile-time safety and architectural boundaries.")
            .check(allClasses)
    }
}
