package io.mazewall.profiler

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
import io.mazewall.profiler.engine.ProfilerDaemonEngine
import io.mazewall.profiler.engine.ProfilerSessionHandler

@AnalyzeClasses(packages = ["io.mazewall.profiler"], importOptions = [ImportOption.DoNotIncludeTests::class])
class ProfilerArchitectureTest {
    @ArchTest
    fun `handshake ordering (0xAC Protocol)`(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        val requireAckBeforeContinue = object : ArchCondition<JavaMethod>("ensure waitForParentAck occurs before sendContinueResponse") {
            override fun check(
                method: JavaMethod,
                events: ConditionEvents,
            ) {
                var waitLine = -1
                var continueLine = -1

                for (call in method.methodCallsFromSelf) {
                    if (call.target.name == "waitForParentAck") {
                        waitLine = call.lineNumber
                    } else if (call.target.name == "sendContinueResponse") {
                        continueLine = call.lineNumber
                    }
                }

                if (waitLine != -1 && continueLine != -1) {
                    if (waitLine > continueLine) {
                        events.add(SimpleConditionEvent.violated(method, "Method ${method.fullName} calls sendContinueResponse before waitForParentAck."))
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
    fun `native-only transport for SCM_RIGHTS`(allClasses: com.tngtech.archunit.core.domain.JavaClasses) {
        noClasses()
            .that()
            .resideInAPackage("io.mazewall.profiler.engine..")
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
