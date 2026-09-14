package io.mazewall.enforcer.api

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@AnalyzeClasses(packages = ["io.mazewall"], importOptions = [ImportOption.DoNotIncludeTests::class])
class SingletonProviderArchitectureTest {
    @ArchTest
    fun singletonProvidersMustBeImmutable(allClasses: JavaClasses) {
        singletonProviderRule().check(allClasses)
    }

    @ArchTest
    fun singletonScanIncludesEveryProductionModule(allClasses: JavaClasses) {
        val representatives = mapOf(
            "enforcer" to "io.mazewall.enforcer.ContainedExecutors",
            "platform" to "io.mazewall.platform.native.LinuxNative",
            "profiler" to "io.mazewall.profiler.api.Profiler",
            "portal" to "io.mazewall.portal.ProcessBroker",
            "portal-worker" to "io.mazewall.portal.worker.PortalWorkerMain",
            "portal-codegen" to "io.mazewall.portal.codegen.PortalStubGenerator",
        )
        representatives.forEach { (module, representative) ->
            assertTrue(
                allClasses.any { it.name == representative },
                "Singleton provider scan must include $module production classes ($representative)",
            )
        }
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "io.mazewall.profiler.api.MutableProfilerProviderFixture",
            "io.mazewall.portal.MutablePortalProviderFixture",
            "io.mazewall.portal.worker.MutableWorkerProviderFixture",
        ],
    )
    fun `module gate rejects a new mutable singleton provider`(fixtureName: String) {
        val fixtures = ClassFileImporter().importClasses(Class.forName(fixtureName))
        val failure = assertThrows(AssertionError::class.java) {
            singletonProvidersMustBeImmutable(fixtures)
        }
        assertTrue(failure.message.orEmpty().contains("$fixtureName.provider"), "Violation must identify the module's mutable provider")
    }
}
