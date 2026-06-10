package io.mazewall

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses

@AnalyzeClasses(packages = ["io.mazewall"], importOptions = [ImportOption.DoNotIncludeTests::class])
class ArchitectureTest {
    @ArchTest
    fun enforcerShouldNotDependOnProfiler(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        noClasses()
            .that()
            .resideInAPackage("io.mazewall..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..profiler..")
            .check(allClasses)
    }

    @ArchTest
    fun seccompAndLandlockLogicMustUseNativeEngineTraits(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        noClasses()
            .that()
            .resideInAnyPackage("io.mazewall.seccomp..", "io.mazewall.landlock..")
            .should()
            .callMethodWhere(object : DescribedPredicate<JavaMethodCall>("calls to LinuxNative implementation methods") {
                override fun test(input: JavaMethodCall): Boolean {
                    return input.target.owner.isAssignableTo(LinuxNative::class.java) &&
                           input.target.name !in listOf("getFileSystem", "getNetworking", "getProcess", "getMemory")
                }
            })
            .because("direct calls to LinuxNative implementation bypass the testable NativeEngine abstraction")
            .check(allClasses)
    }

    @ArchTest
    fun isDirectContainmentViolationShouldOnlyBeCalledByTraversalMethods(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        methods()
            .that()
            .haveName("isDirectContainmentViolation")
            .should()
            .onlyBeCalled()
            .byMethodsThat(
                DescribedPredicate.describe("matching name") {
                it != null && (it.name.matches(Regex("isContainmentViolation|hasViolation|findViolation")))
            },
            ).because("traversal methods correctly handle cause chains which direct checks might skip")
            .check(allClasses)
    }
}
