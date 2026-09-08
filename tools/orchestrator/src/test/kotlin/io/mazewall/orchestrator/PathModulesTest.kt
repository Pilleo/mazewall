package io.mazewall.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class PathModulesTest {
    @Test
    fun `every declared Gradle project maps to a known identity`() {
        val declaredProjectRoots = listOf(
            "platform",
            "enforcer",
            "profiler",
            "portal",
            "portal-codegen",
            "portal-worker",
            "demos/cli-demo",
            "demos/vulnerable-web-app",
            "demos/agent-sandbox-demo",
            "tools/orchestrator",
        )

        declaredProjectRoots.forEach { root ->
            assertNotNull(PathModules.identityFor("$root/build.gradle.kts"), root)
        }
    }

    @Test
    fun `unknown Gradle module does not silently become testing`() {
        assertFailsWith<IllegalArgumentException> {
            PathModules.componentFor(":future-module")
        }
    }

    @Test
    fun `identity owns Gradle path and component`() {
        val portal = requireNotNull(PathModules.identityFor("portal/src/main/kotlin/Portal.kt"))

        assertEquals(":portal", portal.gradlePath)
        assertEquals("docs", portal.component)
    }
}
