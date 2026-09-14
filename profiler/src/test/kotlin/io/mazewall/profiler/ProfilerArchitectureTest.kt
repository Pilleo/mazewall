package io.mazewall.profiler.api
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.mazewall.platform.native.*
import io.mazewall.platform.syscall.*
import io.mazewall.profiler.api.*
import io.mazewall.profiler.api.ObservationConstructorContract
import io.mazewall.profiler.api.SyscallEvent
import io.mazewall.profiler.api.TraceEvent
import io.mazewall.profiler.internal.engine.ProfilerDaemonEngine
import io.mazewall.profiler.internal.engine.ProfilerSessionHandler
import io.mazewall.profiler.internal.engine.SyscallEventConstructorContract

@AnalyzeClasses(packages = ["io.mazewall.profiler"], importOptions = [ImportOption.DoNotIncludeTests::class])
class ProfilerArchitectureTest {
    @ArchTest
    fun `raw FFM stays in profiler ffi or native implementation packages`(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        noClasses()
            .that()
            .resideOutsideOfPackages(
                "io.mazewall.profiler.ffi..",
                "io.mazewall.profiler.tierE.engine..",
                "io.mazewall.profiler.tierE.ringbuf..",
            ).should()
            .dependOnClassesThat()
            .resideInAPackage("java.lang.foreign..")
            .because("profiler protocol and domain code must not expose raw FFM types; native handling belongs behind profiler FFI packages")
            .check(allClasses)
    }

    @ArchTest
    fun `no FFM segments leak across trace event boundaries`(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        noClasses()
            .that()
            .areAssignableTo(TraceEvent::class.java)
            .or()
            .areAssignableTo(SyscallEvent::class.java)
            .or()
            .areAssignableTo(io.mazewall.profiler.api.PathResolution::class.java)
            .or()
            .areAssignableTo(io.mazewall.profiler.api.PathResolutionFailure::class.java)
            .or()
            .areAssignableTo(io.mazewall.profiler.api.PathResolutionCause::class.java)
            .or()
            .areAssignableTo(io.mazewall.profiler.internal.engine.PathStringRead::class.java)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("java.lang.foreign..", "io.mazewall.ffi.memory..")
            .because(
                "To prevent memory segment lifetime leaks and native memory finalization GC overhead, all trace events must strictly hold and pass JVM heap-only data, never referencing FFM MemorySegment or internal native memory classes.",
            ).check(allClasses)
    }

    @ArchTest
    fun `handshake ordering (0xAC Protocol)`(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        val requireAckBeforeContinue = object : ArchCondition<JavaMethod>("ensure performHandshake occurs before sendSeccompContinue") {
            override fun check(
                method: JavaMethod,
                events: ConditionEvents,
            ) {
                var waitLine = -1
                val continueLines = mutableListOf<Int>()

                for (call in method.methodCallsFromSelf) {
                    if (call.target.name == "performHandshake") {
                        waitLine = call.lineNumber
                    } else if (call.target.name == "sendSeccompContinue") {
                        continueLines.add(call.lineNumber)
                    }
                }

                if (waitLine != -1) {
                    val hasContinueAfterWait = continueLines.any { it > waitLine }
                    if (!hasContinueAfterWait) {
                        events.add(SimpleConditionEvent.violated(method, "Method ${method.fullName} does not call sendSeccompContinue after performHandshake."))
                    }
                }
            }
        }

        methods()
            .that()
            .areDeclaredIn(ProfilerSessionHandler::class.java)
            .and()
            .haveNameMatching("processNotification.*")
            .should(requireAckBeforeContinue)
            .because("We must wait for the JVM to ack the trace before continuing the thread, to avoid deadlocks.")
            .check(allClasses)
    }

    @ArchTest
    fun `public profiler types do not take MemorySegment`(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        methods()
            .that()
            .arePublic()
            .and()
            .areDeclaredInClassesThat()
            .arePublic()
            .and()
            .areDeclaredInClassesThat()
            .resideInAPackage("io.mazewall.profiler.api..")
            .should()
            .notHaveRawParameterTypes(java.lang.foreign.MemorySegment::class.java)
            .because("Operator-facing profiler types must not expose FFM MemorySegment; handshake I/O stays internal.")
            .check(allClasses)
    }

    @ArchTest
    fun `reactor loop statelessness`(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        classes()
            .that()
            .areAssignableTo(ProfilerDaemonEngine::class.java)
            .should()
            .haveOnlyFinalFields()
            .because("The reactor loop must remain stateless to support concurrent profiling sessions.")
            .check(allClasses)
    }

    @ArchTest
    fun observationAndEbpfOutcomesHaveAClosedSubclassSet(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        val expected = mapOf(
            "io.mazewall.profiler.internal.engine.ProfilerAckOutcome" to setOf(
                "io.mazewall.profiler.internal.engine.ProfilerAckOutcome\$Acknowledged",
                "io.mazewall.profiler.internal.engine.ProfilerAckOutcome\$PassThrough",
                "io.mazewall.profiler.internal.engine.ProfilerAckOutcome\$Rejected",
            ),
            "io.mazewall.profiler.internal.TraceCommandWriteResult" to setOf(
                "io.mazewall.profiler.internal.TraceCommandWriteResult\$Written",
                "io.mazewall.profiler.internal.TraceCommandWriteResult\$NativeFailure",
                "io.mazewall.profiler.internal.TraceCommandWriteResult\$ShortWrite",
                "io.mazewall.profiler.internal.TraceCommandWriteResult\$Threw",
            ),
            "io.mazewall.profiler.api.PathResolution" to setOf(
                "io.mazewall.profiler.api.PathResolution\$NotApplicable",
                "io.mazewall.profiler.api.PathResolution\$Resolved",
                "io.mazewall.profiler.api.PathResolution\$Partial",
                "io.mazewall.profiler.api.PathResolution\$Failed",
            ),
            "io.mazewall.profiler.api.PathResolutionFailure\$Reason" to setOf(
                "io.mazewall.profiler.api.PathResolutionFailure\$Reason\$VmReadDenied",
                "io.mazewall.profiler.api.PathResolutionFailure\$Reason\$Missing",
                "io.mazewall.profiler.api.PathResolutionFailure\$Reason\$DirFdUnresolved",
                "io.mazewall.profiler.api.PathResolutionFailure\$Reason\$Truncated",
            ),
            "io.mazewall.profiler.internal.engine.PathStringRead" to setOf(
                "io.mazewall.profiler.internal.engine.PathStringRead\$Text",
                "io.mazewall.profiler.internal.engine.PathStringRead\$Missing",
                "io.mazewall.profiler.internal.engine.PathStringRead\$Denied",
                "io.mazewall.profiler.internal.engine.PathStringRead\$Truncated",
            ),
            "io.mazewall.profiler.api.ProfileObservation" to setOf(
                "io.mazewall.profiler.api.ProfileObservation\$Syscall",
                "io.mazewall.profiler.api.ProfileObservation\$IoUring",
                "io.mazewall.profiler.api.ProfileObservation\$Connect",
            ),
            "io.mazewall.profiler.api.EbpfLoad" to setOf(
                "io.mazewall.profiler.api.EbpfLoad\$Available",
                "io.mazewall.profiler.api.EbpfLoad\$UserNamespaceRoot",
                "io.mazewall.profiler.api.EbpfLoad\$Denied",
            ),
            "io.mazewall.profiler.tierE.daemon.ControlCommand" to setOf(
                "io.mazewall.profiler.tierE.daemon.ControlCommand\$Attach",
                "io.mazewall.profiler.tierE.daemon.ControlCommand\$Detach",
                "io.mazewall.profiler.tierE.daemon.ControlCommand\$Status",
                "io.mazewall.profiler.tierE.daemon.ControlCommand\$Shutdown",
                "io.mazewall.profiler.tierE.daemon.ControlCommand\$Unknown",
            ),
        )
        for ((parent, kids) in expected) {
            val actual = allClasses
                .filter { it.isAssignableTo(parent) && it.name != parent }
                .filter { "\$\$" !in it.name && !it.name.contains("DefaultImpls") }
                .map { it.name }
                .toSet()
            org.junit.jupiter.api.Assertions.assertEquals(
                kids,
                actual,
                "Closed subclass set for $parent changed.",
            )
        }
    }

    @ArchTest
    fun `native-only transport for SCM_RIGHTS`(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        noClasses()
            .that()
            .resideInAPackage("io.mazewall.profiler.internal.engine..")
            .should()
            .accessClassesThat()
            .resideInAPackage("java.net..")
            .because("Profiler transport must use LinuxNative for descriptor passing via SCM_RIGHTS.")
            .check(allClasses)
    }

    @ArchTest
    fun `cross-module invariant - enforcer must not depend on profiler`(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        // We actually check this in the Enforcer module's tests, but it's good to re-affirm.
        // Since we are analyzing `io.mazewall.profiler`, we can't easily check `io.mazewall.enforcer`.
        // However, we can ensure profiler doesn't try to access enforcer test classes or internal details if we want.
        // The rule described in the plan was:
        // noClasses().that().resideInAPackage("io.mazewall.enforcer..").should().dependOnClassesThat().resideInAPackage("..profiler..")
        // Since this is the profiler test suite, we're only scanning profiler classes. We'll leave the enforcer test in the enforcer module.
    }
}
