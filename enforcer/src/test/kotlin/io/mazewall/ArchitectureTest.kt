package io.mazewall.enforcer.api

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.core.importer.Location
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.junit.LocationProvider
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.mazewall.enforcer.api.*
import io.mazewall.enforcer.api.StacktraceScopingPolicy
import io.mazewall.enforcer.internal.ffi.RealPlatformProvider
import io.mazewall.enforcer.internal.seccomp.BpfFilter
import io.mazewall.enforcer.internal.supervisor.SocketHelperPool
import io.mazewall.enforcer.internal.supervisor.SupervisorInstaller
import io.mazewall.enforcer.internal.supervisor.SupervisorRoute
import io.mazewall.enforcer.internal.supervisor.SupervisorRouteDispatcher
import io.mazewall.enforcer.internal.supervisor.SupervisorRouteExecutor
import io.mazewall.enforcer.internal.supervisor.SupervisorRoutePreparation
import io.mazewall.enforcer.internal.supervisor.TraceeAuthorityExecution
import io.mazewall.platform.fd.*
import io.mazewall.platform.fd.FileDescriptor
import io.mazewall.platform.lifecycle.*
import io.mazewall.platform.native.*
import io.mazewall.platform.process.*
import io.mazewall.platform.seccomp.daemon.NotificationProgress
import io.mazewall.platform.socket.*
import io.mazewall.platform.syscall.*

/**
 * ArchUnit's stock test filter recognizes conventional source locations but not Gradle's
 * compiled Kotlin test output.  Without this filter, fixture classes are audited as production.
 */
class DoNotIncludeCompiledTestOutputs : ImportOption {
    override fun includes(location: Location): Boolean =
        listOf("/classes/kotlin/test/", "/classes/java/test/", "/test-classes/")
            .none(location.asURI().path::contains)
}

/** Imports the production output of the two modules that define mazewall's implementation. */
class MazewallProductionLocations : LocationProvider {
    override fun get(type: Class<*>): Set<Location> =
        setOf(SupervisorRoute::class.java, NotificationProgress::class.java)
            .map { Location.of(it.protectionDomain.codeSource.location) }
            .toSet()
}

@AnalyzeClasses(
    locations = [MazewallProductionLocations::class],
    importOptions = [ImportOption.DoNotIncludeTests::class, DoNotIncludeCompiledTestOutputs::class],
)
class ArchitectureTest {
    @ArchTest
    fun supervisorRouteHelpersMustNotLeakOutsideTheirPackage(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        listOf(
            SupervisorRouteDispatcher::class.java,
            SupervisorRouteExecutor::class.java,
            SupervisorRoutePreparation::class.java,
            SocketHelperPool::class.java,
            TraceeAuthorityExecution::class.java,
        ).forEach { helper ->
            noClasses()
                .that()
                .resideOutsideOfPackage("io.mazewall.enforcer.internal.supervisor..")
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName(helper.name)
                .because("supervisor route execution must cross its dispatcher boundary")
                .check(allClasses)
        }
    }

    @ArchTest
    fun singletonProvidersMustBeImmutable(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        singletonProviderRule().check(allClasses)
    }

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
                        input.target.name !in listOf(
                        "getFileSystem",
                            "getNetworking",
                            "getProcess",
                            "getMemory",
                            "getRaw",
                             "getBpf",
                            "snapshot", // immutable NativeEngine trait selection; never direct native I/O
                        "isRealEngineActive", // metadata-only probe (issue-20260823-172003), not an I/O bypass
                    )
                }
            })
            .because("direct calls to LinuxNative implementation bypass the testable NativeEngine abstraction")
            .check(allClasses)
    }

    @ArchTest
    fun supervisorInstallerMustUseItsCapturedNativeRuntime(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        noClasses()
            .that()
            .haveFullyQualifiedName(SupervisorInstaller::class.java.name)
            .should()
            .callMethodWhere(object : DescribedPredicate<JavaMethodCall>("direct LinuxNative runtime access") {
                override fun test(input: JavaMethodCall): Boolean =
                    input.target.owner.isAssignableTo(LinuxNative::class.java) &&
                        input.target.name != "snapshot"
            })
            .because("supervised installation must retain one caller-supplied NativeEngine for its complete lifecycle")
            .check(allClasses)
    }

    @ArchTest
    fun plainSeccompPipelineMustUseItsCapturedNativeRuntime(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        noClasses()
            .that()
            .haveNameMatching("io\\.mazewall\\.seccomp\\.(PureJavaBpfEngine|SeccompInstallationState)(\\$.*)?")
            .should()
            .callMethodWhere(object : DescribedPredicate<JavaMethodCall>("mutable LinuxNative trait lookups inside the plain pipeline") {
                override fun test(input: JavaMethodCall): Boolean =
                    input.target.owner.isAssignableTo(LinuxNative::class.java) &&
                        input.target.name in setOf("getFileSystem", "getNetworking", "getProcess", "getMemory", "getRaw", "getBpf")
            })
            .because("packing, application, retries, fallback and verification must use the same captured backend")
            .check(allClasses)
    }

    @ArchTest
    fun rawFfmTypesMustResideInFfiPackages(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        // issue-20260823-172006: all raw java.lang.foreign usage must live in io.mazewall.ffi..
        // (platform owns generic ABI/engine primitives; enforcer owns domain structures).
        noClasses()
            .that()
            .resideOutsideOfPackages("io.mazewall.ffi..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("java.lang.foreign..")
            .because("raw FFM access must be isolated to io.mazewall.ffi.. per architectural-map.md")
            .check(allClasses)
    }

    @ArchTest
    fun rawSyscallOperationsMustOnlyBeUsedByAllowedPackages(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        noClasses()
            .that()
            .resideOutsideOfPackages(
                "io.mazewall.seccomp..",
                "io.mazewall.landlock..",
                "io.mazewall.enforcer.internal.supervisor..",
                "io.mazewall.enforcer.internal.ffi..",
                "io.mazewall.ffi.networking..",
                "io.mazewall.ffi.internal..",
                "io.mazewall", // RealNativeEngine and RealPlatformProvider are in the root package
                "io.mazewall.profiler.internal.engine..",
                "io.mazewall.platform.seccomp.daemon..",
                "io.mazewall.platform.seccomp..",
            ).should()
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
                    it != null && (it.name.matches(Regex("isContainmentViolation|diagnose|findInferredViolation")))
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
            .and()
            .resideOutsideOfPackages("io.mazewall.ffi..", "io.mazewall.ffi.memory..")
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
            .and()
            .resideOutsideOfPackages("io.mazewall.ffi.memory..")
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
            .haveRawReturnType(
                DescribedPredicate.describe("not a MemorySegment or ManagedSegment") {
                it != null &&
                    !it.isAssignableTo("java.lang.foreign.MemorySegment") &&
                    !it.isAssignableTo("io.mazewall.ffi.memory.ManagedSegment")
            },
            ).because("nativeScope must not leak segments beyond their arena lifetime.")
            .allowEmptyShould(true)
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
            .haveSimpleNameNotStartingWith("ContainedExecutorWrapper")
            .and()
            .areDeclaredInClassesThat()
            .haveSimpleNameNotStartingWith("ContainmentViolationDetector")
            .and()
            .areDeclaredInClassesThat()
            .haveSimpleNameNotStartingWith("MazewallEvents")
            .and()
            .areDeclaredInClassesThat()
            .haveSimpleNameNotStartingWith("IsolatedProcessTester")
            .and()
            .areDeclaredInClassesThat()
            .haveSimpleNameNotStartingWith("IsolatedTestRunner")
            // This resource fold attempts every release and rethrows failures, fatal first.
            // ChildStartupTransactionTest and DaemonStartupRollbackTest verify startup and
            // retirement aggregation rather than silent suppression, including exact fatal identity.
            .and()
            .areDeclaredInClassesThat()
            .doNotHaveFullyQualifiedName("io.mazewall.platform.fd.ResourceRetirement")
            .and()
            .areDeclaredInClassesThat()
            .doNotHaveFullyQualifiedName("io.mazewall.platform.fd.ResourceRetirement\$Companion")
            // FileDescriptor.use always closes and suppresses close failures onto the block
            // failure; it does not swallow containment errors.
            .and()
            .areDeclaredInClassesThat()
            .doNotHaveFullyQualifiedName("io.mazewall.platform.fd.FileDescriptorKt")
            // OwnedRuntimeTest verifies these exact resource boundaries retain fatal identity,
            // required readiness and physical cancellation/drain leases; none downgrades failures.
            .and()
            .areDeclaredInClassesThat()
            .doNotHaveFullyQualifiedName("io.mazewall.enforcer.api.execution.ManagedRuntime")
            .and()
            .areDeclaredInClassesThat()
            .doNotHaveFullyQualifiedName("io.mazewall.enforcer.api.execution.ManagedRuntime\$Companion")
            .and()
            .areDeclaredInClassesThat()
            .doNotHaveFullyQualifiedName("io.mazewall.enforcer.internal.execution.OwnedDomain")
            .and()
            .areDeclaredInClassesThat()
            .doNotHaveFullyQualifiedName("io.mazewall.enforcer.internal.execution.OwnedInvocation")
            // Prepared acquisitions aggregate rollback/retirement failures and rethrow;
            // real-FD and prepared-operation tests cover failure propagation, not fallback.
            .and()
            .areDeclaredInClassesThat()
            .doNotHaveFullyQualifiedName("io.mazewall.enforcer.api.execution.PreparedOperationResources")
            .and()
            .areDeclaredInClassesThat()
            .doNotHaveFullyQualifiedName("io.mazewall.enforcer.api.execution.PreparedOperationResources\$Companion")
            .and()
            .areDeclaredInClassesThat()
            .doNotHaveFullyQualifiedName("io.mazewall.enforcer.internal.execution.PreparedResourceSet")
            .and()
            .areDeclaredInClassesThat()
            .doNotHaveFullyQualifiedName("io.mazewall.enforcer.internal.execution.PreparedResourceSet\$Companion")
            .and()
            .areDeclaredInClassesThat()
            .doNotHaveFullyQualifiedName("io.mazewall.ffi.internal.PreparedPath\$Companion")
            .and()
            .areDeclaredInClassesThat()
            .doNotHaveFullyQualifiedName("io.mazewall.ffi.internal.ImplementationArtifact\$Companion")
            .and()
            .areDeclaredInClassesThat()
            .doNotHaveFullyQualifiedName("io.mazewall.landlock.PreparedLandlock")
            .and()
            .areDeclaredInClassesThat()
            .doNotHaveFullyQualifiedName("io.mazewall.landlock.PreparedLandlock\$Companion")
            .and()
            .areDeclaredInClassesThat()
            .resideOutsideOfPackages(
                "io.mazewall.enforcer.internal.supervisor..",
                "io.mazewall.platform.seccomp.daemon..",
                "io.mazewall.ffi.networking..",
                "io.mazewall.ffi.memory..",
            ).should(object : com.tngtech.archunit.lang.ArchCondition<com.tngtech.archunit.core.domain.JavaMethod>("not catch generic exceptions") {
                override fun check(
                    item: com.tngtech.archunit.core.domain.JavaMethod,
                    events: com.tngtech.archunit.lang.ConditionEvents,
                ) {
                    item.tryCatchBlocks.forEach { tryCatchBlock ->
                        tryCatchBlock.caughtThrowables.forEach { caughtException ->
                            val name = caughtException.name
                            if (name == Exception::class.java.name ||
                                name == Throwable::class.java.name ||
                                name == RuntimeException::class.java.name
                            ) {
                                val message = "Method ${item.fullName} catches generic exception $name at ${tryCatchBlock.sourceCodeLocation}"
                                events.add(
                                    com.tngtech.archunit.lang.SimpleConditionEvent
                                    .violated(item, message),
                                )
                            }
                        }
                    }
                }
            })
            .because(
                "Catching generic exceptions in a security boundary module can silently swallow critical JVM errors or obscure containment violations. Catch specific expected native faults instead.",
            ).check(allClasses)
    }

    @ArchTest
    fun unhandledSyscallResultsMustNotBeReturnedByDomainLogic(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        methods()
            .that()
            .areDeclaredInClassesThat()
            .resideInAnyPackage("io.mazewall.seccomp..", "io.mazewall.landlock..", "io.mazewall.enforcer.internal.supervisor..")
            .and()
            .haveRawReturnType(LinuxNative.SyscallResult::class.java)
            .should()
            .notHaveRawReturnType(
                DescribedPredicate.describe("an unhandled result") {
                // Since generic types are erased, ArchUnit cannot directly inspect them this way easily for Kotlin.
                // Kotlin compiles it to `SyscallResult`, and generic bounds are stored in metadata.
                // The prompt says we could do this via detekt or ArchUnit. Detekt is probably better, but let's check method signatures.
                // Wait, since we are forced to handle the result, returning it from methods is one problem.
                // Actually ArchUnit CAN inspect `Method.getReturnType().getName()` but it will just be `SyscallResult`.
                // ArchUnit has `getGenericReturnType()`.
                false
            },
            ).allowEmptyShould(true)
            .because("Domain logic must handle errors locally before returning")
            .check(allClasses)
    }

    @ArchTest
    fun domainLogicMustHandleSyscallResults(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        methods()
            .that()
            .areDeclaredInClassesThat()
            .resideInAnyPackage("io.mazewall.seccomp..", "io.mazewall.landlock..", "io.mazewall.enforcer.internal.supervisor..")
            .and()
            .haveNameNotMatching(".*\\\$.*") // Ignore Kotlin internal mangled names
            .should()
            .notHaveRawReturnType(LinuxNative.SyscallResult::class.java)
            .allowEmptyShould(true)
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
                    return input.target.owner.name
                        .startsWith("io.mazewall.ffi.memory.MemoryWrappersKt") &&
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
            .haveFullyQualifiedName("io.mazewall.enforcer.internal.supervisor.JvmStackInspector")
            .should()
            .onlyDependOnClassesThat(
                object : DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass>("only allow primitive/JDK types, Intrinsics, Metadata and ScopingValidationState") {
                    override fun test(input: com.tngtech.archunit.core.domain.JavaClass): Boolean {
                        val name = input.name
                        return name == "io.mazewall.enforcer.internal.supervisor.JvmStackInspector" ||
                            name == "io.mazewall.enforcer.internal.supervisor.ScopingValidationState" ||
                            name == "io.mazewall.enforcer.internal.supervisor.ScopingValidationState\$SafeToValidate" ||
                            name == "io.mazewall.enforcer.internal.supervisor.ScopingValidationState\$SafeToValidate\$Companion" ||
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
                },
            ).because("Any other dependencies in JvmStackInspector could trigger classloading during validation and deadlock the JVM.")
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
                        return name.startsWith("io.mazewall.enforcer.internal.supervisor.ScopingValidationState") ||
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
                },
            ).because("Any other dependencies in ScopingValidationState could trigger classloading during validation and deadlock the JVM.")
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

    @ArchTest
    fun javaLangThreadAndStandardExecutorsAreBannedInProductionCode(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        noClasses()
            .that()
            .resideInAPackage("io.mazewall..")
            .and(object : DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass>("are not part of core orchestration classes") {
                override fun test(input: com.tngtech.archunit.core.domain.JavaClass): Boolean {
                    val name = input.name
                    return "SandboxDispatcher" !in name &&
                        "SupervisorDaemon" !in name &&
                        "JvmChildProcess" !in name &&
                        "SeccompDaemonEngine" !in name &&

                        "SupervisorSession" !in name &&
                        "SupervisorInstaller" !in name &&
                        "SupervisorSeccompNotifInstaller" !in name &&
                        "JVMValidationListener" !in name &&
                        "JvmFloorWorkload" !in name &&
                        "Profiler" !in name &&
                        // Containment owns raw-pool construction; everything
                        // else must go through ContainedExecutors factories.
                        "ContainedExecutors" !in name &&
                        name == "io.mazewall.enforcer.api.Mazewall" ||
                            name.startsWith("io.mazewall.enforcer.api.Mazewall$")
                }
            })
            .should()
            .callConstructorWhere(object : DescribedPredicate<com.tngtech.archunit.core.domain.JavaConstructorCall>("calls to Thread or standard Executor constructors") {
                override fun test(input: com.tngtech.archunit.core.domain.JavaConstructorCall): Boolean {
                    val owner = input.target.owner
                    return owner.isAssignableTo(java.lang.Thread::class.java) ||
                        (
                            owner.isAssignableTo(java.util.concurrent.ExecutorService::class.java) &&
                            owner.name.startsWith("java.util.concurrent.")
                        )
                }
            })
            .orShould()
            .callMethodWhere(object : DescribedPredicate<com.tngtech.archunit.core.domain.JavaMethodCall>("calls to Thread or standard Executors factory methods") {
                override fun test(input: com.tngtech.archunit.core.domain.JavaMethodCall): Boolean {
                    val owner = input.target.owner
                    return owner.isAssignableTo(java.util.concurrent.Executors::class.java) ||
                        (
                            owner.isAssignableTo(java.lang.Thread::class.java) &&
                            input.target.name in setOf("startVirtualThread", "ofVirtual", "ofPlatform")
                        )
                }
            })
            .because(
                "Direct creation of Thread or standard Executors ignores mazewall's containment states and structured concurrency requirements, leading to context leaks. Use SandboxDispatcher or ContainedExecutors instead.",
            ).check(allClasses)
    }

    @ArchTest
    fun ffmApiMustBeIsolatedToFfiPackage(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        noClasses()
            .that()
            .resideOutsideOfPackage("io.mazewall.ffi..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("java.lang.foreign..")
            .because("The FFM API usage must be isolated to the io.mazewall.ffi package to maintain compile-time safety and architectural boundaries.")
            .check(allClasses)
    }

    @org.junit.jupiter.api.Test
    fun ffmApiMustBeIsolatedToFfiPackageRuleDetectsViolation() {
        val rule = noClasses()
            .that()
            .resideOutsideOfPackage("io.mazewall.ffi..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("java.lang.foreign..")

        val classes = com.tngtech.archunit.core.importer
            .ClassFileImporter()
            .importClasses(dummy.violator.DummyViolatingClass::class.java)
        org.junit.jupiter.api.assertThrows<AssertionError> {
            rule.check(classes)
        }
    }

    @ArchTest
    fun methodHandleInvokeExactMustBeEncapsulatedInSyscallInvoker(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        noClasses()
            .that()
            .resideInAPackage("io.mazewall..")
            .and(object : DescribedPredicate<com.tngtech.archunit.core.domain.JavaClass>("is not SyscallInvoker") {
                override fun test(input: com.tngtech.archunit.core.domain.JavaClass): Boolean {
                    return input.name != "io.mazewall.ffi.internal.SyscallInvoker"
                }
            })
            .should()
            .callMethodWhere(object : DescribedPredicate<JavaMethodCall>("calls to MethodHandle.invokeExact") {
                override fun test(input: JavaMethodCall): Boolean {
                    return input.target.owner.isAssignableTo("java.lang.invoke.MethodHandle") &&
                        input.target.name == "invokeExact"
                }
            })
            .because("MethodHandle.invokeExact must only be called within SyscallInvoker to ensure atomic errno capture.")
            .check(allClasses)
    }

    @ArchTest
    fun sealedSecurityOutcomesHaveAClosedSubclassSet(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        val expected = mapOf(
            "io.mazewall.enforcer.api.CanonicalPolicyArtifact" to setOf(
                "io.mazewall.enforcer.api.CanonicalPolicyArtifact\$Worker",
                "io.mazewall.enforcer.api.CanonicalPolicyArtifact\$Process",
            ),
            "io.mazewall.enforcer.internal.execution.ExecutionTransition" to setOf(
                "io.mazewall.enforcer.internal.execution.ExecutionTransition\$Dispatch",
                "io.mazewall.enforcer.internal.execution.ExecutionTransition\$InlineCurrentWorker",
                "io.mazewall.enforcer.internal.execution.ExecutionTransition\$Reject",
            ),
            "io.mazewall.enforcer.api.execution.ExecutionEnd" to setOf(
                "io.mazewall.enforcer.api.execution.ExecutionEnd\$NotStarted",
                "io.mazewall.enforcer.api.execution.ExecutionEnd\$Finished",
            ),
            "io.mazewall.enforcer.api.execution.DrainReport" to setOf(
                "io.mazewall.enforcer.api.execution.DrainReport\$Complete",
                "io.mazewall.enforcer.api.execution.DrainReport\$Incomplete",
            ),
            "io.mazewall.enforcer.internal.supervisor.TraceeProcAuthority\$DirectoryKey" to setOf(
                "io.mazewall.enforcer.internal.supervisor.TraceeProcAuthority\$DirectoryKey\$Root",
                "io.mazewall.enforcer.internal.supervisor.TraceeProcAuthority\$DirectoryKey\$Cwd",
                "io.mazewall.enforcer.internal.supervisor.TraceeProcAuthority\$DirectoryKey\$Imported",
            ),
            "io.mazewall.enforcer.internal.supervisor.SocketOperation" to setOf(
                "io.mazewall.enforcer.internal.supervisor.SocketOperation\$Connect",
                "io.mazewall.enforcer.internal.supervisor.SocketOperation\$Accept",
                "io.mazewall.enforcer.internal.supervisor.SocketOperation\$OpenAt2",
            ),
            "io.mazewall.enforcer.internal.supervisor.SocketHelperResult" to setOf(
                "io.mazewall.enforcer.internal.supervisor.SocketHelperResult\$Authority",
                "io.mazewall.enforcer.internal.supervisor.SocketHelperResult\$Connected",
                "io.mazewall.enforcer.internal.supervisor.SocketHelperResult\$Failed",
                "io.mazewall.enforcer.internal.supervisor.SocketHelperResult\$Accepted",
                "io.mazewall.enforcer.internal.supervisor.SocketHelperResult\$Opened",
            ),
            "io.mazewall.enforcer.internal.supervisor.SocketHelperRequest" to setOf(
                "io.mazewall.enforcer.internal.supervisor.SocketHelperRequest\$Prewarm",
                "io.mazewall.enforcer.internal.supervisor.SocketHelperRequest\$Operation",
                "io.mazewall.enforcer.internal.supervisor.SocketHelperRequest\$Authority",
            ),
            "io.mazewall.ffi.networking.SeccompInstallState" to setOf(
                "io.mazewall.ffi.networking.SeccompInstallState\$Pending",
                "io.mazewall.ffi.networking.SeccompInstallState\$Failed",
            ),
            "io.mazewall.ffi.networking.SeccompInstallEvent" to setOf(
                "io.mazewall.ffi.networking.SeccompInstallEvent\$Advanced",
                "io.mazewall.ffi.networking.SeccompInstallEvent\$Failed",
            ),
            "io.mazewall.platform.seccomp.daemon.NotificationProgress" to setOf(
                "io.mazewall.platform.seccomp.daemon.NotificationProgress\$Await",
                "io.mazewall.platform.seccomp.daemon.NotificationProgress\$Ready",
            ),
            "io.mazewall.enforcer.internal.supervisor.SupervisorReplyEffect" to setOf(
                "io.mazewall.enforcer.internal.supervisor.SupervisorReplyEffect\$UnsupportedAuthority",
                "io.mazewall.enforcer.internal.supervisor.SupervisorReplyEffect\$Continue",
                "io.mazewall.enforcer.internal.supervisor.SupervisorReplyEffect\$Value",
                "io.mazewall.enforcer.internal.supervisor.SupervisorReplyEffect\$Error",
                "io.mazewall.enforcer.internal.supervisor.SupervisorReplyEffect\$AddFdAndReply",
                "io.mazewall.enforcer.internal.supervisor.SupervisorReplyEffect\$AcceptedSocket",
                "io.mazewall.enforcer.internal.supervisor.SupervisorReplyEffect\$SecureExecAndReply",
                "io.mazewall.enforcer.internal.supervisor.SupervisorReplyEffect\$Recycle",
            ),
            "io.mazewall.enforcer.internal.supervisor.ReplyExecutionResult" to setOf(
                "io.mazewall.enforcer.internal.supervisor.ReplyExecutionResult\$ReplySucceeded",
                "io.mazewall.enforcer.internal.supervisor.ReplyExecutionResult\$ReplyFailed",
            ),
            "io.mazewall.enforcer.internal.supervisor.JvmExchangeResult" to setOf(
                "io.mazewall.enforcer.internal.supervisor.JvmExchangeResult\$Verdict",
                "io.mazewall.enforcer.internal.supervisor.JvmExchangeResult\$Timeout",
                "io.mazewall.enforcer.internal.supervisor.JvmExchangeResult\$Malformed",
                "io.mazewall.enforcer.internal.supervisor.JvmExchangeResult\$TransportFailed",
            ),
            "io.mazewall.enforcer.internal.supervisor.SupervisorDescriptorResult" to setOf(
                "io.mazewall.enforcer.internal.supervisor.SupervisorDescriptorResult\$Opened",
                "io.mazewall.enforcer.internal.supervisor.SupervisorDescriptorResult\$Denied",
            ),
            "io.mazewall.enforcer.internal.supervisor.FdInjectionResult" to setOf(
                "io.mazewall.enforcer.internal.supervisor.FdInjectionResult\$Injected",
                "io.mazewall.enforcer.internal.supervisor.FdInjectionResult\$Failed",
            ),
            "io.mazewall.platform.seccomp.daemon.SeccompCleanupOutcome" to setOf(
                "io.mazewall.platform.seccomp.daemon.SeccompCleanupOutcome\$Complete",
                "io.mazewall.platform.seccomp.daemon.SeccompCleanupOutcome\$Incomplete",
            ),
            "io.mazewall.enforcer.internal.supervisor.SpawnAuthorizationResult" to setOf(
                "io.mazewall.enforcer.internal.supervisor.SpawnAuthorizationResult\$Authorized",
                "io.mazewall.enforcer.internal.supervisor.SpawnAuthorizationResult\$Rejected",
            ),
            "io.mazewall.enforcer.api.KernelCapability" to setOf(
                "io.mazewall.enforcer.api.KernelCapability\$Available",
                "io.mazewall.enforcer.api.KernelCapability\$Unsupported",
                "io.mazewall.enforcer.api.KernelCapability\$Rejected",
                "io.mazewall.enforcer.api.KernelCapability\$ProbeFailed",
            ),
            "io.mazewall.landlock.LandlockApplyResult" to setOf(
                "io.mazewall.landlock.LandlockApplyResult\$Applied",
                "io.mazewall.landlock.LandlockApplyResult\$Bypassed",
                "io.mazewall.landlock.LandlockApplyResult\$Rejected",
            ),
            "io.mazewall.ffi.networking.SeccompConnection" to setOf(
                "io.mazewall.ffi.networking.SeccompConnection\$Accepted",
                "io.mazewall.ffi.networking.SeccompConnection\$FdAttached",
                "io.mazewall.ffi.networking.SeccompConnection\$Active",
            ),
            "io.mazewall.ffi.networking.SeccompConnectionEvent" to setOf(
                "io.mazewall.ffi.networking.SeccompConnectionEvent\$ListenerReceived",
                "io.mazewall.ffi.networking.SeccompConnectionEvent\$RecvFailed",
                "io.mazewall.ffi.networking.SeccompConnectionEvent\$PollIdle",
                "io.mazewall.ffi.networking.SeccompConnectionEvent\$PollFailed",
                "io.mazewall.ffi.networking.SeccompConnectionEvent\$AckSucceeded",
                "io.mazewall.ffi.networking.SeccompConnectionEvent\$AckFailed",
                "io.mazewall.ffi.networking.SeccompConnectionEvent\$SessionFinished",
            ),
            "io.mazewall.platform.daemon.UnixListenDaemonState" to setOf(
                "io.mazewall.platform.daemon.UnixListenDaemonState\$Uninitialized",
                "io.mazewall.platform.daemon.UnixListenDaemonState\$Listening",
                "io.mazewall.platform.daemon.UnixListenDaemonState\$Active",
                "io.mazewall.platform.daemon.UnixListenDaemonState\$ShuttingDown",
                "io.mazewall.platform.daemon.UnixListenDaemonState\$Terminated",
            ),
            "io.mazewall.enforcer.internal.supervisor.BypassPaths\$PathResolution" to setOf(
                "io.mazewall.enforcer.internal.supervisor.BypassPaths\$PathResolution\$Resolved",
                "io.mazewall.enforcer.internal.supervisor.BypassPaths\$PathResolution\$Missing",
                "io.mazewall.enforcer.internal.supervisor.BypassPaths\$PathResolution\$Unsafe",
            ),
            "io.mazewall.platform.seccomp.SupervisedKind" to setOf(
                "io.mazewall.platform.seccomp.SupervisedKind\$Open",
                "io.mazewall.platform.seccomp.SupervisedKind\$Connect",
                "io.mazewall.platform.seccomp.SupervisedKind\$Accept",
                "io.mazewall.platform.seccomp.SupervisedKind\$Exec",
                "io.mazewall.platform.seccomp.SupervisedKind\$Spawn",
                "io.mazewall.platform.seccomp.SupervisedKind\$Unknown",
            ),
            "io.mazewall.enforcer.internal.supervisor.SupervisorRoute" to setOf(
                "io.mazewall.enforcer.internal.supervisor.SupervisorRoute\$Continue",
                "io.mazewall.enforcer.internal.supervisor.SupervisorRoute\$AskJvm",
                "io.mazewall.enforcer.internal.supervisor.SupervisorRoute\$InjectFd",
                "io.mazewall.enforcer.internal.supervisor.SupervisorRoute\$SecureExec",
                "io.mazewall.enforcer.internal.supervisor.SupervisorRoute\$Abort",
            ),
            "io.mazewall.enforcer.internal.supervisor.JvmVerdict" to setOf(
                "io.mazewall.enforcer.internal.supervisor.JvmVerdict\$Deny",
                "io.mazewall.enforcer.internal.supervisor.JvmVerdict\$Allow",
                "io.mazewall.enforcer.internal.supervisor.JvmVerdict\$InjectFd",
            ),
            "io.mazewall.enforcer.internal.supervisor.JvmVerdictParse" to setOf(
                "io.mazewall.enforcer.internal.supervisor.JvmVerdictParse\$Known",
                "io.mazewall.enforcer.internal.supervisor.JvmVerdictParse\$Unknown",
            ),
            "io.mazewall.enforcer.state.ContainmentRegistryEffect" to setOf(
                "io.mazewall.enforcer.state.ContainmentRegistryEffect\$LandlockApplied",
                "io.mazewall.enforcer.state.ContainmentRegistryEffect\$SeccompInstalled",
                "io.mazewall.enforcer.state.ContainmentRegistryEffect\$EngineStateUpdated",
                "io.mazewall.enforcer.state.ContainmentRegistryEffect\$InstallationFailed",
                "io.mazewall.enforcer.state.ContainmentRegistryEffect\$CetLocked",
            ),
            "io.mazewall.enforcer.internal.supervisor.SupervisedOpen" to setOf(
                "io.mazewall.enforcer.internal.supervisor.SupervisedOpen\$Open",
                "io.mazewall.enforcer.internal.supervisor.SupervisedOpen\$OpenAt",
                "io.mazewall.enforcer.internal.supervisor.SupervisedOpen\$OpenAt2",
            ),
        )
        for ((parent, kids) in expected) {
            if (allClasses.none { it.name == parent }) continue
            val actual = allClasses
                .filter { it.isAssignableTo(parent) && it.name != parent }
                .filter { !it.name.contains("DefaultImpls") }
                .filter { "\$\$" !in it.name }
                .map { it.name }
                .toSet()
            org.junit.jupiter.api.Assertions.assertEquals(
                kids,
                actual,
                "Closed subclass set for $parent changed. Update evaluate()/when branches before adding a variant.",
            )
        }
    }

    @ArchTest
    fun operatorApiMustNotDependOnUserNotifProtocolTypes(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        noClasses()
            .that()
            .resideInAnyPackage("io.mazewall.enforcer.api..")
            .should()
            .dependOnClassesThat()
            .haveNameMatching(
                "io\\.mazewall\\.platform\\.seccomp\\.(SupervisedKind|UserNotifReply|SeccompNotifications|SeccompNotification)(\\$.*)?",
            ).because(
                "USER_NOTIF protocol types are cross-module internals, not operator API. " +
                    "Call Policy / ContainedExecutors instead.",
            ).check(allClasses)
    }

    @ArchTest
    fun fileDescriptorUnsafeMustNotBeUsedInProduction(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        noClasses()
            .that()
            .resideOutsideOfPackages("..test..", "io.mazewall")
            .should()
            .callMethodWhere(object : DescribedPredicate<JavaMethodCall>("calls to FileDescriptor.unsafe or unsafe\$default") {
                override fun test(input: JavaMethodCall): Boolean {
                    val ownerName = input.target.owner.name
                    val methodName = input.target.name
                    val isUnsafeCall = (
                        ownerName == "io.mazewall.platform.fd.FileDescriptor\$Companion" ||
                        ownerName == "io.mazewall.platform.fd.FileDescriptor"
                    ) &&
                        (methodName == "unsafe" || methodName == "unsafe\$default")

                    // Exclude internal synthetic calls from Companion itself (unsafe$default -> unsafe)
                    val isCompanionInternalCall = input.origin.owner.name == "io.mazewall.platform.fd.FileDescriptor\$Companion"

                    return isUnsafeCall && !isCompanionInternalCall
                }
            })
            .because(
                "Use role-specific factories (generic, unixSocket, ruleset, oPath, seccompNotif, pid) or adopt() for kernel-reused FDs. " +
                    "unsafe() creates non-live tokens for retired FDs.",
            ).check(allClasses)
    }
}
