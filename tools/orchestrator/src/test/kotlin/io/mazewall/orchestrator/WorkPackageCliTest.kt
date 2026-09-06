package io.mazewall.orchestrator

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkPackageCliTest {
    @Test
    fun syscallPathIsExclusiveAndNeedsKernelTests() {
        val pkg = WorkPackage.assemble(
            files = listOf("platform/src/main/kotlin/io/mazewall/core/Syscall.kt"),
            symbols = listOf("Syscall"),
        )
        assertEquals(listOf("platform/src/main/kotlin/io/mazewall/core/Syscall.kt"), pkg.edit)
        assertTrue(pkg.exclusive)
        assertTrue(pkg.kernelTests)
        assertTrue(pkg.impact.isEmpty())
    }

    @Test
    fun profilerOnlyIsNotExclusive() {
        val pkg = WorkPackage.assemble(
            files = listOf("profiler/src/main/kotlin/io/mazewall/Profiler.kt"),
            symbols = listOf("Profiler"),
        )
        assertFalse(pkg.exclusive)
        assertFalse(pkg.kernelTests)
    }

    @Test
    fun impactOmitsEditFilesAndTestComesFromTestPaths() {
        val pkg = WorkPackage.assemble(
            files = listOf("enforcer/src/main/kotlin/io/mazewall/Cache.kt"),
            symbols = listOf("Cache"),
            callers = listOf(
                WorkPackageCaller("Cache", "enforcer/src/main/kotlin/io/mazewall/Cache.kt"),
                WorkPackageCaller(
                    "CacheTest",
                    "enforcer/src/test/kotlin/io/mazewall/CacheTest.kt",
                ),
                WorkPackageCaller(
                    "use",
                    "profiler/src/main/kotlin/io/mazewall/UsesCache.kt",
                ),
            ),
        )
        assertEquals(listOf("enforcer/src/test/kotlin/io/mazewall/CacheTest.kt", "profiler/src/main/kotlin/io/mazewall/UsesCache.kt"), pkg.impact)
        assertEquals(
            listOf("./gradlew :enforcer:test --tests io.mazewall.CacheTest"),
            pkg.test,
        )
    }

    @Test
    fun jsonUsesPlainKeys() {
        val json = WorkPackage
            .assemble(
            files = listOf("platform/src/main/kotlin/io/mazewall/core/Syscall.kt"),
        ).toJson()
        assertContains(json, "\"edit\"")
        assertContains(json, "\"impact\"")
        assertContains(json, "\"test\"")
        assertContains(json, "\"exclusive\": true")
        assertContains(json, "\"kernel_tests\": true")
        assertFalse(json.contains("target_files"))
        assertFalse(json.contains("verify_cheap"))
        assertFalse(json.contains("core_lock"))
        assertFalse(json.contains("needs_kernel"))
    }

    @Test
    fun parseCodannaCallersExtractsFileAndSymbol() {
        val raw = """
            2 function(s) call symbol_id:2028:
              <- Method CacheTest at ./enforcer/src/test/kotlin/io/mazewall/CacheTest.kt:12
                 Signature: @Test CacheTest ()
              <- Method scaffold at ./tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/IssueTemplateGenerator.kt:96
        """.trimIndent()
        val callers = CodannaOutput.parseCallers(raw)
        assertEquals("CacheTest", callers[0].symbol)
        assertEquals("enforcer/src/test/kotlin/io/mazewall/CacheTest.kt", callers[0].file)
        assertEquals("IssueTemplateGenerator.kt", callers[1].file.substringAfterLast('/'))
    }

    @Test
    fun parseCodannaDescribeExtractsFilePaths() {
        val raw = """
            Ambiguous: found 2 symbol(s) named 'PolicyCompilationCache':
              1. symbol_id:6428 - Class at ./enforcer/src/main/kotlin/io/mazewall/PolicyCompilationCache.kt:16
              2. symbol_id:13603 - Class at ./enforcer/src/main/kotlin/io/mazewall/PolicyCompilationCache.kt:16
        """.trimIndent()
        val files = CodannaOutput.parseFiles(raw)
        assertEquals(listOf("enforcer/src/main/kotlin/io/mazewall/PolicyCompilationCache.kt"), files)
        assertEquals(listOf("6428", "13603"), CodannaOutput.parseSymbolIds(raw))
    }

    @Test
    fun missingCodannaIsDetected() {
        assertFalse(codannaOnPath(""))
        assertFalse(codannaOnPath("/tmp"))
    }

    @Test
    fun testDagDecompositionAndFormatters() {
        val stages = WorkPackage.decomposeDag(
            title = "Refactor Memory Barriers",
            files = listOf(
                "platform/src/main/kotlin/io/mazewall/core/Syscall.kt",
                "enforcer/src/main/kotlin/io/mazewall/Policy.kt",
                "profiler/src/main/kotlin/io/mazewall/profiler/Profiler.kt",
            ),
        )
        assertEquals(3, stages.size)
        assertEquals(1, stages[0].stageNumber)
        assertEquals(":platform", stages[0].module)
        assertTrue(stages[0].dependencies.isEmpty())

        assertEquals(2, stages[1].stageNumber)
        assertEquals(":enforcer", stages[1].module)
        assertEquals(listOf(stages[0].title), stages[1].dependencies)

        assertEquals(3, stages[2].stageNumber)
        assertEquals(":profiler", stages[2].module)
        assertEquals(listOf(stages[1].title), stages[2].dependencies)

        val ascii = WorkPackage.formatAsciiDag(stages)
        assertContains(ascii, "Work Package Blast Radius & DAG Decomposition")
        assertContains(ascii, "[Stage 1: :platform]")
        assertContains(ascii, "[Stage 2: :enforcer]")
        assertContains(ascii, "[Stage 3: :profiler]")

        val mermaid = WorkPackage.formatMermaidDag(stages)
        assertContains(mermaid, "graph TD")
        assertContains(mermaid, "Stage1[\":platform: Part 1\"]")
        assertContains(mermaid, "Stage1 --> Stage2")
        assertContains(mermaid, "Stage2 --> Stage3")
    }

    @Test
    fun testCliDecomposeAndJsonFlags() {
        val parsed = IssueCli.parse(
            arrayOf(
                "--title",
                "Add Syscall X",
                "--decompose",
                "--json",
                "--file",
                "platform/src/main/kotlin/io/mazewall/core/Syscall.kt",
            ),
        )
        assertTrue(parsed.decompose)
        assertTrue(parsed.json)
    }
}
