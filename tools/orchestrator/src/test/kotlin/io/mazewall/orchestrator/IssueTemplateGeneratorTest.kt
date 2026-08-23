package io.mazewall.orchestrator

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IssueTemplateGeneratorTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        tempDir = File.createTempFile("issue-template-", "")
        tempDir.delete()
        tempDir.mkdirs()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun generatedFilePassesBacklogValidator() {
        val backlog = File(tempDir, "docs/internals/backlog")
        val request = IssueScaffoldRequest(
            title = "Cap PolicyCompilationCache growth",
            category = "code_health",
            severity = "MEDIUM",
            priority = "high",
            component = "enforcer",
            explicitFiles = listOf("enforcer/src/main/kotlin/io/mazewall/PolicyCompilationCache.kt"),
            explicitModules = emptyList(),
            symbols = emptyList(),
            dependencies = emptyList(),
        )
        val written = IssueTemplateGenerator(
            repoRoot = tempDir,
            backlogRoot = backlog,
            clock = { Instant.parse("2026-08-23T18:30:00Z") },
        ).write(request)

        assertEquals(
            "issue-20260823-183000-cap-policycompilationcache-growth.md",
            written.name,
        )
        val errors = BacklogValidator.validateBacklog(backlog)
        assertTrue(errors.isEmpty(), "validator errors: $errors")
        val parsed = requireNotNull(BacklogParser.parseIssueFile(written))
        assertEquals("issue-20260823-183000", parsed.id)
        assertEquals(listOf(":enforcer"), parsed.targetModules)
        assertEquals(
            listOf("enforcer/src/main/kotlin/io/mazewall/PolicyCompilationCache.kt"),
            parsed.targetFiles,
        )
        assertTrue(parsed.context?.contains("FILL") == true)
        assertTrue(parsed.needed?.contains("FILL") == true)
    }

    @Test
    fun infersPortalCodegenModuleBeforePortalPrefix() {
        assertEquals(":portal-codegen", PathModules.moduleFor("portal-codegen/src/main/kotlin/X.kt"))
        assertEquals(":portal-worker", PathModules.moduleFor("portal-worker/src/main/kotlin/X.kt"))
        assertEquals(":portal", PathModules.moduleFor("portal/src/main/kotlin/X.kt"))
        assertEquals(":tools:orchestrator", PathModules.moduleFor("tools/orchestrator/src/main/kotlin/X.kt"))
        assertEquals(":demos:cli-demo", PathModules.moduleFor("demos/cli-demo/src/main/kotlin/X.kt"))
        assertEquals(null, PathModules.moduleFor("docs/internals/designs/core/architectural-map.md"))
    }

    @Test
    fun coreLockSetIncludesSyscallAndRootAgents() {
        assertTrue(PathModules.isCoreLock("platform/src/main/kotlin/io/mazewall/core/Syscall.kt"))
        assertTrue(PathModules.isCoreLock("AGENTS.md"))
        assertTrue(PathModules.isCoreLock("enforcer/AGENTS.md"))
        assertTrue(PathModules.isCoreLock("settings.gradle.kts"))
        assertFalse(PathModules.isCoreLock("enforcer/src/main/kotlin/io/mazewall/PolicyCompilationCache.kt"))
    }

    @Test
    fun incrementsSecondsWhenFilenameAlreadyExists() {
        val backlog = File(tempDir, "docs/internals/backlog")
        val category = File(backlog, "code_health").apply { mkdirs() }
        File(category, "issue-20260823-183000-cap-cache.md").writeText("occupied")

        val written = IssueTemplateGenerator(
            repoRoot = tempDir,
            backlogRoot = backlog,
            clock = { Instant.parse("2026-08-23T18:30:00Z") },
        ).write(
            IssueScaffoldRequest(
                title = "Cap cache",
                category = "code_health",
                severity = "LOW",
                priority = "low",
                component = "enforcer",
                explicitFiles = listOf("enforcer/src/main/kotlin/io/mazewall/PolicyCompilationCache.kt"),
                explicitModules = listOf(":enforcer"),
                symbols = emptyList(),
                dependencies = emptyList(),
            ),
        )
        assertEquals("issue-20260823-183001-cap-cache.md", written.name)
    }

    @Test
    fun locatesDefinitionAndTestFromSymbolWalk() {
        val def = File(tempDir, "enforcer/src/main/kotlin/io/mazewall/PolicyCompilationCache.kt")
        def.parentFile.mkdirs()
        def.writeText("package io.mazewall\nclass PolicyCompilationCache\n")
        val test = File(tempDir, "enforcer/src/test/kotlin/io/mazewall/PolicyCompilationCacheTest.kt")
        test.parentFile.mkdirs()
        test.writeText("package io.mazewall\nclass PolicyCompilationCacheTest\n")
        val binCopy = File(tempDir, "enforcer/bin/main/io/mazewall/PolicyCompilationCache.kt")
        binCopy.parentFile.mkdirs()
        binCopy.writeText("package io.mazewall\nclass PolicyCompilationCache\n")

        val files = FilesystemSymbolLocator(tempDir).filesForSymbols(listOf("PolicyCompilationCache"))
        assertEquals(
            listOf(
                "enforcer/src/main/kotlin/io/mazewall/PolicyCompilationCache.kt",
                "enforcer/src/test/kotlin/io/mazewall/PolicyCompilationCacheTest.kt",
            ),
            files,
        )
    }

    @Test
    fun verifyCheapUsesTestClassPath() {
        val cmd = PathModules.verifyCheapCommand(
            "enforcer/src/test/kotlin/io/mazewall/PolicyCompilationCacheTest.kt",
        )
        assertEquals(
            "./gradlew :enforcer:test --tests io.mazewall.PolicyCompilationCacheTest",
            cmd,
        )
    }

    @Test
    fun rejectsUnknownSeverityAndEmptyTitle() {
        val generator = IssueTemplateGenerator(tempDir, File(tempDir, "backlog"))
        assertFailsWith<IllegalArgumentException> {
            generator.write(
                IssueScaffoldRequest(
                    title = "  ",
                    category = "code_health",
                    severity = "MEDIUM",
                    priority = "high",
                    component = "enforcer",
                    explicitFiles = listOf("enforcer/a.kt"),
                    explicitModules = listOf(":enforcer"),
                    symbols = emptyList(),
                    dependencies = emptyList(),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            generator.write(
                IssueScaffoldRequest(
                    title = "x",
                    category = "code_health",
                    severity = "URGENT",
                    priority = "high",
                    component = "enforcer",
                    explicitFiles = listOf("enforcer/a.kt"),
                    explicitModules = listOf(":enforcer"),
                    symbols = emptyList(),
                    dependencies = emptyList(),
                ),
            )
        }
    }

    @Test
    fun unknownDependencyIsRejectedAgainstExistingBacklog() {
        val backlog = File(tempDir, "docs/internals/backlog")
        File(backlog, "code_health").mkdirs()
        val generator = IssueTemplateGenerator(
            repoRoot = tempDir,
            backlogRoot = backlog,
            clock = { Instant.parse("2026-08-23T18:30:00Z") },
        )
        val ex = assertFailsWith<IllegalArgumentException> {
            generator.write(
                IssueScaffoldRequest(
                    title = "Child",
                    category = "code_health",
                    severity = "MEDIUM",
                    priority = "high",
                    component = "enforcer",
                    explicitFiles = listOf("enforcer/a.kt"),
                    explicitModules = listOf(":enforcer"),
                    symbols = emptyList(),
                    dependencies = listOf("issue-20991231-000000"),
                ),
            )
        }
        assertContains(ex.message ?: "", "issue-20991231-000000")
    }

    @Test
    fun renderIncludesOptionalSymbolsAndCoreLock() {
        val markdown = IssueTemplateGenerator.render(
            idInstant = Instant.parse("2026-08-23T18:30:00Z").atZone(ZoneOffset.UTC),
            slug = "touch-syscall",
            request = IssueScaffoldRequest(
                title = "Touch syscall",
                category = "code_health",
                severity = "HIGH",
                priority = "high",
                component = "platform",
                explicitFiles = listOf("platform/src/main/kotlin/io/mazewall/core/Syscall.kt"),
                explicitModules = listOf(":platform"),
                symbols = listOf("Syscall"),
                dependencies = emptyList(),
            ),
            files = listOf("platform/src/main/kotlin/io/mazewall/core/Syscall.kt"),
            modules = listOf(":platform"),
            verifyCheap = emptyList(),
            coreLock = true,
        )
        assertContains(markdown, "target_symbols:")
        assertContains(markdown, "- \"Syscall\"")
        assertContains(markdown, "core_lock: true")
        assertContains(markdown, "needs_kernel: false")
        assertContains(markdown, "# 🔴 [Severity: HIGH]: Touch syscall")
        assertFalse(markdown.contains("## ❓ Open Questions"))
    }

    @Test
    fun cliParseKeepsQuotedTitlePiecesAsSeparateFlags() {
        val parsed = IssueCli.parse(
            arrayOf(
                "--title", "Cap cache",
                "--file", "enforcer/a.kt",
                "--symbol", "PolicyCompilationCache",
                "--dry-run",
            ),
        )
        assertEquals("Cap cache", parsed.request.title)
        assertEquals(listOf("enforcer/a.kt"), parsed.request.explicitFiles)
        assertEquals(listOf("PolicyCompilationCache"), parsed.request.symbols)
        assertTrue(parsed.dryRun)
    }

    @Test
    fun interviewCollectsOpenQuestionsWithoutCallingLlm() {
        val answers = ArrayDeque(
            listOf("n", "y", "Should eviction be LRU?", "What is max size?", "", "cache overflowed", "add a bound"),
        )
        val prompt = LinePrompt { _, _ -> answers.removeFirst() }
        val filled = IssueInterview.complete(
            request = IssueScaffoldRequest(
                title = "Cap cache",
                category = "code_health",
                severity = "MEDIUM",
                priority = "high",
                component = "enforcer",
                explicitFiles = listOf("enforcer/a.kt"),
                explicitModules = listOf(":enforcer"),
                symbols = emptyList(),
                dependencies = emptyList(),
            ),
            prompt = prompt,
            askOpenQuestions = true,
            askKernel = true,
        )
        assertFalse(filled.needsKernel)
        assertEquals(listOf("Should eviction be LRU?", "What is max size?"), filled.openQuestionItems)
        assertEquals("cache overflowed", filled.contextBody)
        assertEquals("add a bound", filled.neededBody)
    }

    @Test
    fun clarifierClosesQuestionsUsingStrongModel() {
        val weak = ChatModel { _, _ -> """{"questions":["LRU or FIFO?"]}""" }
        val strong = ChatModel { _, _ ->
            """{"context":"Unbounded map grows forever.","needed":"1. Cap size.\n2. Add a unit test."}"""
        }
        val filled = IssueClarifier.clarify(
            request = IssueScaffoldRequest(
                title = "Cap cache",
                category = "code_health",
                severity = "MEDIUM",
                priority = "high",
                component = "enforcer",
                explicitFiles = listOf("enforcer/a.kt"),
                explicitModules = listOf(":enforcer"),
                symbols = emptyList(),
                dependencies = emptyList(),
            ),
            files = emptyList(),
            repoRoot = tempDir,
            weak = weak,
            strong = strong,
        )
        assertTrue(filled.openQuestionItems.isEmpty())
        assertEquals("Unbounded map grows forever.", filled.contextBody)
        assertTrue(filled.neededBody!!.contains("Cap size"))
    }

    @Test
    fun renderWritesOpenQuestionsSectionWhenPresent() {
        val markdown = IssueTemplateGenerator.render(
            idInstant = Instant.parse("2026-08-23T18:30:00Z").atZone(ZoneOffset.UTC),
            slug = "cap-cache",
            request = IssueScaffoldRequest(
                title = "Cap cache",
                category = "code_health",
                severity = "LOW",
                priority = "low",
                component = "enforcer",
                explicitFiles = listOf("enforcer/a.kt"),
                explicitModules = listOf(":enforcer"),
                symbols = emptyList(),
                dependencies = emptyList(),
                openQuestionItems = listOf("LRU or FIFO?"),
                contextBody = "map grows",
                neededBody = "cap it",
            ),
            files = listOf("enforcer/a.kt"),
            modules = listOf(":enforcer"),
            verifyCheap = emptyList(),
            coreLock = false,
        )
        assertContains(markdown, "open_questions: true")
        assertContains(markdown, "## ❓ Open Questions")
        assertContains(markdown, "1. LRU or FIFO?")
        val file = File(tempDir, "issue-20260823-183000-cap-cache.md")
        file.writeText(markdown)
        val parsed = requireNotNull(BacklogParser.parseIssueFile(file))
        assertTrue(parsed.hasOpenQuestions)
        assertEquals("map grows", parsed.context)
        assertEquals("cap it", parsed.needed)
    }

    @Test
    fun cliOpenQuestionFlagsAreNonInteractive() {
        val parsed = IssueCli.parse(
            arrayOf(
                "--title", "Cap cache",
                "--file", "enforcer/a.kt",
                "--open-question", "LRU?",
                "--non-interactive",
            ),
        )
        assertEquals(listOf("LRU?"), parsed.request.openQuestionItems)
        assertEquals(true, parsed.openQuestionsSpecified)
        assertTrue(parsed.nonInteractive)
        assertFalse(parsed.interactive)
    }

    @Test
    fun dryRunDoesNotWrite() {
        val backlog = File(tempDir, "docs/internals/backlog")
        val result = IssueTemplateGenerator(
            repoRoot = tempDir,
            backlogRoot = backlog,
            clock = { Instant.parse("2026-08-23T18:30:00Z") },
        ).scaffold(
            IssueScaffoldRequest(
                title = "Dry",
                category = "code_health",
                severity = "LOW",
                priority = "low",
                component = "docs",
                explicitFiles = listOf("AGENTS.md"),
                explicitModules = listOf(":tools:orchestrator"),
                symbols = emptyList(),
                dependencies = emptyList(),
            ),
            write = false,
        )
        assertFalse(result.file.exists())
        assertTrue(result.markdown.startsWith("---"))
    }
}
