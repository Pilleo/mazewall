package io.mazewall

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.mazewall.enforcer.ContainedExecutors
import io.mazewall.seccomp.PureJavaBpfEngine

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
        noClasses()
            .that()
            .doNotHaveFullyQualifiedName("io.mazewall.IsolatedTestRunner")
            .and()
            .doNotHaveFullyQualifiedName("io.mazewall.seccomp.PureJavaBpfEngine")
            .and()
            .doNotHaveFullyQualifiedName("io.mazewall.enforcer.ContainedExecutors")
            .should()
            .callMethod(ContainedExecutors::class.java, "installOnProcess", Policy::class.java)
            .orShould()
            .callMethod(PureJavaBpfEngine::class.java, "installOnProcess", Policy::class.java)
            .because(
                "Directly applying process-wide seccomp in unit tests poisons the long-lived Gradle Test Worker, " +
                    "causing fatal EPERM crashes (e.g. on JIT compilation) in unrelated subsequent tests. " +
                    "Use IsolatedProcessTester instead.",
            ).check(allClasses)
    }

    @ArchTest
    fun ffmApiMustBeIsolatedToFfiPackage(allClasses: JavaClasses) {
        noClasses()
            .that()
            .resideInAPackage("io.mazewall..")
            .and()
            .resideOutsideOfPackage("io.mazewall.ffi..")
            .and(object : DescribedPredicate<JavaClass>("is not NativeEngine, MockNativeEngine, RawSyscallOperations, or associated test/mocks") {
                override fun test(input: JavaClass): Boolean {
                    val name = input.name
                    return name != "io.mazewall.NativeEngine" &&
                           name != "io.mazewall.RawSyscallOperations" &&
                           !name.startsWith("io.mazewall.RawSyscallOperations$") &&
                           name != "io.mazewall.MockNativeEngine" &&
                           !name.startsWith("io.mazewall.MockNativeEngine$") &&
                           !name.startsWith("io.mazewall.NativeEngineTest") &&
                           !name.startsWith("io.mazewall.enforcer.supervisor.SupervisorSessionHandlerTest")
                }
            })
            .should()
            .dependOnClassesThat()
            .resideInAPackage("java.lang.foreign..")
            .because("The FFM API usage must be isolated to the io.mazewall.ffi package to maintain compile-time safety and architectural boundaries.")
            .check(allClasses)
    }
}
