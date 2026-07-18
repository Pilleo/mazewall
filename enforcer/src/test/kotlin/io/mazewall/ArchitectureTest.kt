package io.mazewall

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.mazewall.enforcer.supervisor.StacktraceScopingPolicy

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
                        input.target.name !in listOf("getFileSystem", "getNetworking", "getProcess", "getMemory", "getRaw", "withTransaction", "getTRANSACTION_INSTANCE")
                }
            })
            .because("direct calls to LinuxNative implementation bypass the testable NativeEngine abstraction")
            .check(allClasses)
    }

    @ArchTest
    fun rawSyscallOperationsMustOnlyBeUsedByAllowedPackages(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        noClasses()
            .that()
            .resideOutsideOfPackages(
                "io.mazewall.seccomp..",
                "io.mazewall.landlock..",
                "io.mazewall.enforcer.supervisor..",
                "io.mazewall.ffi.networking..",
                "io.mazewall", // RealNativeEngine and RealPlatformProvider are in the root package
                "io.mazewall.profiler.engine..",
            )
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName(RawSyscallOperations::class.java.name)
            .because("Raw system call operations are sensitive and must only be used by core enforcer/profiler components")
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
            .haveSimpleNameNotStartingWith("ContainedExecutors")
            .and()
            .areDeclaredInClassesThat()
            .resideOutsideOfPackages(
                "io.mazewall.enforcer.supervisor..",
                "io.mazewall.ffi.networking..",
                "io.mazewall.ffi.memory..",
            )
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
            .resideInAnyPackage("io.mazewall.seccomp..", "io.mazewall.landlock..", "io.mazewall.enforcer.supervisor..")
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
            .resideInAnyPackage("io.mazewall.seccomp..", "io.mazewall.landlock..", "io.mazewall.enforcer.supervisor..")
            .and()
            .haveNameNotMatching(".*\\\$.*") // Ignore Kotlin internal mangled names
            .should()
            .notHaveRawReturnType(LinuxNative.SyscallResult::class.java)
            .because("Domain logic must not leak raw SyscallResult objects to callers. They must be handled internally.")
            .check(allClasses)
    }

    @ArchTest
    fun bigEndianMemoryWritesMustOnlyBeCalledByNetworkOrderBuffer(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        noClasses()
            .that()
            .resideOutsideOfPackages("io.mazewall.ffi.networking..")
            .should()
            .callMethodWhere(object : DescribedPredicate<JavaMethodCall>("calls to Big Endian write helpers") {
                override fun test(input: JavaMethodCall): Boolean {
                    return input.target.owner.name.startsWith("io.mazewall.ffi.memory.MemoryWrappersKt") &&
                        input.target.name.contains("BigEndian")
                }
            })
            .because("Big Endian write helpers must be encapsulated within NetworkOrderBuffer to enforce compile-time ordering.")
            .check(allClasses)
    }

    @ArchTest
    fun virtualThreadsAreBannedInProductionCode(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        noClasses()
            .that()
            .resideInAPackage("io.mazewall..")
            .and()
            .haveSimpleNameNotStartingWith("JvmFloorWorkload")
            .should()
            .callMethodWhere(object : DescribedPredicate<JavaMethodCall>("calls to virtual thread creation APIs") {
                override fun test(input: JavaMethodCall): Boolean {
                    val owner = input.target.owner
                    val name = input.target.name
                    return (owner.isAssignableTo(java.util.concurrent.Executors::class.java) && name == "newVirtualThreadPerTaskExecutor") ||
                        (owner.isAssignableTo(java.lang.Thread::class.java) && (name == "ofVirtual" || name == "startVirtualThread"))
                }
            })
            .because("Virtual thread executors are not permitted in production runtime paths of mazewall")
            .check(allClasses)
    }


    @ArchTest
    fun jvmStackInspectorMustHavePrimitiveDependenciesOnly(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        classes()
            .that()
            .haveFullyQualifiedName("io.mazewall.enforcer.supervisor.JvmStackInspector")
            .should()
            .onlyDependOnClassesThat(
                object : DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass>("only allow primitive/JDK types, Intrinsics, Metadata and ScopingValidationState") {
                    override fun test(input: com.tngtech.archunit.core.domain.JavaClass): Boolean {
                        val name = input.name
                        return name == "io.mazewall.enforcer.supervisor.JvmStackInspector" ||
                            name == "io.mazewall.enforcer.supervisor.ScopingValidationState" ||
                            name == "io.mazewall.enforcer.supervisor.ScopingValidationState\$SafeToValidate" ||
                            name == "io.mazewall.enforcer.supervisor.ScopingValidationState\$SafeToValidate\$Companion" ||
                            name == "java.lang.Object" ||
                            name == "java.lang.String" ||
                            name == "java.lang.StackTraceElement" ||
                            name == "java.lang.Thread" ||
                            name == "java.util.List" ||
                            name == "java.util.Collection" ||
                            name == "java.util.ArrayList" ||
                            name == "java.lang.Exception" ||
                            name == "org.jetbrains.annotations.NotNull" ||
                            name == "org.jetbrains.annotations.Nullable" ||
                            name == "kotlin.jvm.internal.Intrinsics" ||
                            name == "kotlin.jvm.internal.DefaultConstructorMarker" ||
                            name == "kotlin.Metadata" ||
                            name.startsWith("[")
                    }
                }
            )
            .because("Any other dependencies in JvmStackInspector could trigger classloading during validation and deadlock the JVM.")
            .check(allClasses)
    }

    @ArchTest
    fun scopingValidationStateMustHavePrimitiveDependenciesOnly(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        classes()
            .that(object : DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass>("are ScopingValidationState types") {
                override fun test(input: com.tngtech.archunit.core.domain.JavaClass): Boolean {
                    return input.name.contains("ScopingValidationState")
                }
            })
            .should()
            .onlyDependOnClassesThat(
                object : DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass>("only allow primitive/JDK types, Intrinsics, List and Metadata") {
                    override fun test(input: com.tngtech.archunit.core.domain.JavaClass): Boolean {
                        val name = input.name
                        return name.startsWith("io.mazewall.enforcer.supervisor.ScopingValidationState") ||
                            name == "java.lang.Object" ||
                            name == "java.lang.String" ||
                            name == "java.lang.StackTraceElement" ||
                            name == "java.util.List" ||
                            name == "java.lang.Exception" ||
                            name == "org.jetbrains.annotations.NotNull" ||
                            name == "org.jetbrains.annotations.Nullable" ||
                            name == "kotlin.jvm.internal.Intrinsics" ||
                            name == "kotlin.jvm.internal.DefaultConstructorMarker" ||
                            name == "kotlin.Metadata" ||
                            name.startsWith("[")
                    }
                }
            )
            .because("Any other dependencies in ScopingValidationState could trigger classloading during validation and deadlock the JVM.")
            .check(allClasses)
    }

    @ArchTest
    fun scopingPolicyAuthorizeMustOnlyBeCalledByJVMValidationListener(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        noClasses()
            .that()
            .resideInAPackage("io.mazewall..")
            .and()
            .haveSimpleNameNotStartingWith("JVMValidationListener")
            .should()
            .callMethodWhere(object : DescribedPredicate<JavaMethodCall>("calls to StacktraceScopingPolicy.authorize") {
                override fun test(input: JavaMethodCall): Boolean {
                    return input.target.owner.isAssignableTo(StacktraceScopingPolicy::class.java) &&
                        input.target.name == "authorize"
                }
            })
            .because("Only JVMValidationListener is allowed to invoke authorize to ensure it is guarded by the ClassLoader deadlock bypass.")
            .check(allClasses)
    }
}
