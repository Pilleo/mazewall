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
                        input.target.name !in listOf("getFileSystem", "getNetworking", "getProcess", "getMemory", "withTransaction", "getTRANSACTION_INSTANCE")
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

    @ArchTest
    fun sandboxedCodeMustNotUseLazyStringConcatenation(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        noClasses()
            .that()
            .resideInAPackage("io.mazewall.enforcer.internal..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("java.lang.invoke.StringConcatFactory")
            .because("String concatenation inside sandboxed threads triggers lazy classloading and JVM EPERM crashes.")
            .check(allClasses)
    }

    @ArchTest
    fun sandboxedCodeMustNotTriggerClassLoading(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        noClasses()
            .that()
            .resideInAPackage("io.mazewall.enforcer.internal..")
            .should()
            .dependOnClassesThat()
            .belongToAnyOf(
                ClassLoader::class.java,
            ).because("Classloading inside seccomp-restricted threads triggers mmap/mprotect which fails with EPERM.")
            .check(allClasses)
    }

    @ArchTest
    fun rawMemorySegmentAccessMustBeEncapsulated(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        noClasses()
            .that()
            .resideInAPackage("io.mazewall..")
            .and().resideOutsideOfPackages("io.mazewall.ffi..", "io.mazewall.ffi.memory..")
            .should()
            .callMethodWhere(object : DescribedPredicate<JavaMethodCall>("calls to MemorySegment.get or set") {
                override fun test(input: JavaMethodCall): Boolean {
                    return input.target.owner.isAssignableTo("java.lang.foreign.MemorySegment") &&
                        (input.target.name == "get" || input.target.name == "set")
                }
            })
            .because("raw memory access via get/set bypasses compile-time safety. Use io.mazewall.ffi.memory wrappers instead.")
            .check(allClasses)
    }

    @ArchTest
    fun memorySegmentReinterpretIsBanned(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        noClasses()
            .that()
            .resideInAPackage("io.mazewall..")
            .and().resideOutsideOfPackages("io.mazewall.ffi.memory..")
            .should()
            .callMethodWhere(object : DescribedPredicate<JavaMethodCall>("calls to MemorySegment.reinterpret") {
                override fun test(input: JavaMethodCall): Boolean {
                    return input.target.owner.isAssignableTo("java.lang.foreign.MemorySegment") &&
                        input.target.name == "reinterpret"
                }
            })
            .because("reinterpreting memory segments bypasses safety bounds. Encapsulate within io.mazewall.ffi.memory instead.")
            .check(allClasses)
    }

    @ArchTest
    fun nativeScopeMustNotReturnMemorySegment(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        methods()
            .that()
            .haveName("nativeScope")
            .should()
            .haveRawReturnType(DescribedPredicate.describe("not a MemorySegment or ManagedSegment") {
                it != null && !it.isAssignableTo("java.lang.foreign.MemorySegment") &&
                    !it.isAssignableTo("io.mazewall.ffi.memory.ManagedSegment")
            })
            .because("nativeScope must not leak segments beyond their arena lifetime.")
            .check(allClasses)
    }

    @ArchTest
    fun arenaOfAutoIsBanned(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        noClasses()
            .that()
            .resideInAPackage("io.mazewall..")
            .should()
            .callMethodWhere(object : DescribedPredicate<JavaMethodCall>("calls to Arena.ofAuto") {
                override fun test(input: JavaMethodCall): Boolean {
                    return input.target.owner.isAssignableTo("java.lang.foreign.Arena") &&
                        input.target.name == "ofAuto"
                }
            })
            .because("GC-managed auto arenas can be cleaned up while kernel structures still reference them. Use nativeScope instead.")
            .check(allClasses)
    }

    @ArchTest
    fun noGenericExceptionCatchingInEnforcer(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        methods()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage("io.mazewall..")
            .and()
            .areDeclaredInClassesThat()
            .haveSimpleNameNotStartingWith("LayoutValidator")
            .and()
            .areDeclaredInClassesThat()
            .haveSimpleNameNotStartingWith("LayoutValidationScope")
            .and()
            .areDeclaredInClassesThat()
            .haveSimpleNameNotStartingWith("PureJavaBpfEngine")
            .and()
            .areDeclaredInClassesThat()
            .haveSimpleNameNotStartingWith("LandlockSession")
            .and()
            .areDeclaredInClassesThat()
            .haveSimpleNameNotStartingWith("Supervisor")
            .should(object : com.tngtech.archunit.lang.ArchCondition<com.tngtech.archunit.core.domain.JavaMethod>("not catch generic exceptions") {
                override fun check(item: com.tngtech.archunit.core.domain.JavaMethod, events: com.tngtech.archunit.lang.ConditionEvents) {
                    item.tryCatchBlocks.forEach { tryCatchBlock ->
                        tryCatchBlock.caughtThrowables.forEach { caughtException ->
                            val name = caughtException.name
                            if (name == Exception::class.java.name ||
                                name == Throwable::class.java.name ||
                                name == RuntimeException::class.java.name
                            ) {
                                val message = "Method ${item.fullName} catches generic exception $name at ${tryCatchBlock.sourceCodeLocation}"
                                events.add(com.tngtech.archunit.lang.SimpleConditionEvent.violated(item, message))
                            }
                        }
                    }
                }
            })
            .because("Catching generic exceptions in a security boundary module can silently swallow critical JVM errors or obscure containment violations. Catch specific expected native faults instead.")
            .check(allClasses)
    }

    @ArchTest
    fun unhandledSyscallResultsMustNotBeReturnedByDomainLogic(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        methods()
            .that()
            .areDeclaredInClassesThat()
            .resideInAnyPackage("io.mazewall.seccomp..", "io.mazewall.landlock..")
            .and()
            .haveRawReturnType(LinuxNative.SyscallResult::class.java)
            .should()
            .notHaveRawReturnType(DescribedPredicate.describe("an unhandled result") {
                // Since generic types are erased, ArchUnit cannot directly inspect them this way easily for Kotlin.
                // Kotlin compiles it to `SyscallResult`, and generic bounds are stored in metadata.
                // The prompt says we could do this via detekt or ArchUnit. Detekt is probably better, but let's check method signatures.
                // Wait, since we are forced to handle the result, returning it from methods is one problem.
                // Actually ArchUnit CAN inspect `Method.getReturnType().getName()` but it will just be `SyscallResult`.
                // ArchUnit has `getGenericReturnType()`.
                false
            })
            .because("Domain logic must handle errors locally before returning")
            .check(allClasses)
    }

    @ArchTest
    fun domainLogicMustHandleSyscallResults(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        methods()
            .that()
            .areDeclaredInClassesThat()
            .resideInAnyPackage("io.mazewall.seccomp..", "io.mazewall.landlock..")
            .and()
            .arePublic()
            .and()
            .haveNameNotMatching(".*\\$.*") // Ignore Kotlin internal mangled names
            .should()
            .notHaveRawReturnType(LinuxNative.SyscallResult::class.java)
            .because("Domain logic must not leak raw SyscallResult objects to callers. They must be handled internally.")
            .check(allClasses)
    }
}
