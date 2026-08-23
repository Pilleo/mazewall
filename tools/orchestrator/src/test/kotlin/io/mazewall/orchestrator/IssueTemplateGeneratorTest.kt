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
    fun interviewAsksSideEffectsAndKeepsKnownImpact() {
        val answers = ArrayDeque(
            listOf("n", "y", "profiler callers of Cache.put", "n", "map grows", "cap it"),
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
            askSideEffects = true,
        )
        assertEquals(true, filled.hasSideEffects)
        assertEquals(listOf("profiler callers of Cache.put"), filled.sideEffectImpacts)
        assertTrue(filled.openQuestionItems.isEmpty())
    }

    @Test
    fun cliSideEffectFlagsAreNonInteractive() {
        val parsed = IssueCli.parse(
            arrayOf(
                "--title", "Cap cache",
                "--file", "enforcer/a.kt",
                "--side-effects",
                "--side-effect", "profiler Cache.put",
                "--non-interactive",
            ),
        )
        assertEquals(true, parsed.request.hasSideEffects)
        assertEquals(listOf("profiler Cache.put"), parsed.request.sideEffectImpacts)
        assertEquals(true, parsed.sideEffectsSpecified)
        assertTrue(parsed.nonInteractive)
    }

    @Test
    fun tryClarifyKeepsDraftWhenAcpMissingOrFails() {
        val draft = IssueTemplateGenerator(
            repoRoot = tempDir,
            backlogRoot = File(tempDir, "docs/internals/backlog"),
            clock = { Instant.parse("2026-08-23T18:30:00Z") },
        ).scaffold(
            IssueScaffoldRequest(
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
            write = false,
        )
        val warnings = mutableListOf<String>()
        val skipped = IssueClarifier.tryClarify(
            draft = draft,
            repoRoot = tempDir,
            scratchDir = File(tempDir, "scratch"),
            weak = null,
            strong = null,
            warn = { warnings += it },
        )
        assertTrue(skipped.markdown.contains("FILL:"))
        assertEquals("skipped", skipped.request.reviewVerdict)

        val failed = IssueClarifier.tryClarify(
            draft = draft,
            repoRoot = tempDir,
            scratchDir = File(tempDir, "scratch2"),
            weak = ChatModel { _, _ -> error("boom") },
            strong = ChatModel { _, _ -> error("nope") },
            warn = { warnings += it },
        )
        assertTrue(failed.markdown.contains("FILL:"))
        assertTrue(warnings.any { it.contains("boom") })
    }

    @Test
    fun weakAuthorsThenIndependentStrongReview() {
        var strongSawAuthorPrompt = false
        val weak = ChatModel { _, _ ->
            """{"context":"Unbounded map grows forever.","needed":"1. Cap size.\n2. Add a unit test.","investigation_points":["read Cache.kt"],"important_details":["unbounded ConcurrentHashMap"],"open_questions":[],"extra_files":[]}"""
        }
        val strong = ChatModel { system, user ->
            if (system.contains("author") || user.contains("ROLE: author") || user.contains("ROLE: investigator")) {
                strongSawAuthorPrompt = true
            }
            """{"verdict":"approved","comments":[]}"""
        }
        val draft = IssueTemplateGenerator(
            repoRoot = tempDir,
            backlogRoot = File(tempDir, "docs/internals/backlog"),
            clock = { Instant.parse("2026-08-23T18:30:00Z") },
        ).scaffold(
            IssueScaffoldRequest(
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
            write = false,
        )
        val out = IssueClarifier.tryClarify(
            draft = draft,
            repoRoot = tempDir,
            scratchDir = File(tempDir, "scratch3"),
            weak = weak,
            strong = strong,
        )
        assertFalse(strongSawAuthorPrompt)
        assertEquals("approved", out.request.reviewVerdict)
        assertTrue(out.markdown.contains("Unbounded map grows forever."))
        assertFalse(out.markdown.contains("FILL:"))
        assertContains(out.markdown, "review_verdict: approved")
        assertContains(out.markdown, "## Investigation")
        assertContains(out.markdown, "## Important details")
    }

    @Test
    fun weakInvestigateClosesQuestionsWithoutStrongAnswers() {
        var weakRoles = mutableListOf<String>()
        var strongRoles = mutableListOf<String>()
        val weak = ChatModel { _, user ->
            when {
                user.contains("ROLE: author") -> {
                    weakRoles += "author"
                    """{"context":"map grows","needed":"1. Cap.","investigation_points":["opened Cache.kt"],"important_details":["no max size"],"open_questions":["LRU or FIFO?"],"extra_files":[]}"""
                }

                else -> {
                    weakRoles += "investigate"
                    """{"context":"map grows","needed":"1. Cap with LRU.","investigation_points":["traced put()"],"important_details":["LRU or FIFO? → LRU (Cache.kt)"],"open_questions":[],"extra_files":[]}"""
                }
            }
        }
        val strong = ChatModel { _, user ->
            strongRoles += if (user.contains("ROLE: leftover-answers")) "leftover" else "review"
            """{"verdict":"approved","comments":[]}"""
        }
        val draft = IssueTemplateGenerator(
            repoRoot = tempDir,
            backlogRoot = File(tempDir, "docs/internals/backlog"),
            clock = { Instant.parse("2026-08-23T18:30:00Z") },
        ).scaffold(
            IssueScaffoldRequest(
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
            write = false,
        )
        val out = IssueClarifier.tryClarify(
            draft = draft,
            repoRoot = tempDir,
            scratchDir = File(tempDir, "scratch-dig"),
            weak = weak,
            strong = strong,
        )
        assertEquals(listOf("author", "investigate"), weakRoles)
        assertEquals(listOf("review"), strongRoles)
        assertTrue(out.request.openQuestionItems.isEmpty())
        assertContains(out.markdown, "traced put()")
        assertContains(out.markdown, "LRU or FIFO? → LRU")
    }

    @Test
    fun leftoverQuestionsGoToStrongThenFinalReview() {
        val strongRoles = mutableListOf<String>()
        val weak = ChatModel { _, user ->
            if (user.contains("ROLE: author") || user.contains("ROLE: investigator")) {
                """{"context":"unclear kernel cap","needed":"1. Confirm.","investigation_points":["read Platform.kt"],"important_details":[],"open_questions":["Does Landlock ABI v4 exist on CI?"],"extra_files":[]}"""
            } else {
                """{"context":"unclear kernel cap","needed":"1. Confirm.","investigation_points":["read Platform.kt"],"important_details":[],"open_questions":["Does Landlock ABI v4 exist on CI?"],"extra_files":[]}"""
            }
        }
        val strong = ChatModel { _, user ->
            if (user.contains("ROLE: leftover-answers")) {
                strongRoles += "leftover"
                """{"context":"CI is ABI v4","needed":"1. Gate on ABI v4.","investigation_points":["operator knowledge"],"important_details":["CI kernel 6.8"],"open_questions":[],"extra_files":[]}"""
            } else {
                strongRoles += "review"
                """{"verdict":"approved","comments":[]}"""
            }
        }
        val draft = IssueTemplateGenerator(
            repoRoot = tempDir,
            backlogRoot = File(tempDir, "docs/internals/backlog"),
            clock = { Instant.parse("2026-08-23T18:30:00Z") },
        ).scaffold(
            IssueScaffoldRequest(
                title = "Landlock ABI",
                category = "code_health",
                severity = "MEDIUM",
                priority = "high",
                component = "enforcer",
                explicitFiles = listOf("enforcer/a.kt"),
                explicitModules = listOf(":enforcer"),
                symbols = emptyList(),
                dependencies = emptyList(),
            ),
            write = false,
        )
        val out = IssueClarifier.tryClarify(
            draft = draft,
            repoRoot = tempDir,
            scratchDir = File(tempDir, "scratch-left"),
            weak = weak,
            strong = strong,
            maxWeakRounds = 1,
        )
        assertEquals(listOf("leftover", "review"), strongRoles)
        assertTrue(out.request.openQuestionItems.isEmpty())
        assertContains(out.markdown, "CI kernel 6.8")
        assertEquals("approved", out.request.reviewVerdict)
    }

    @Test
    fun weakFillOutputIsRejectedByVerifier() {
        val weak = ChatModel { _, _ -> """{"context":"FILL: x","needed":"FILL: y"}""" }
        val draft = IssueTemplateGenerator(
            repoRoot = tempDir,
            backlogRoot = File(tempDir, "docs/internals/backlog"),
            clock = { Instant.parse("2026-08-23T18:30:00Z") },
        ).scaffold(
            IssueScaffoldRequest(
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
            write = false,
        )
        val out = IssueClarifier.tryClarify(
            draft = draft,
            repoRoot = tempDir,
            scratchDir = File(tempDir, "scratch4"),
            weak = weak,
            strong = ChatModel { _, _ -> """{"verdict":"approved","comments":[]}""" },
        )
        assertTrue(out.markdown.contains("FILL: what is wrong"))
        assertEquals("skipped", out.request.reviewVerdict)
    }

    @Test
    fun strongReviewLoopAppliesWeakFixesThenApproves() {
        val weakRoles = mutableListOf<String>()
        var reviewPasses = 0
        val weak = ChatModel { _, user ->
            when {
                user.contains("ROLE: author") -> {
                    weakRoles += "author"
                    """{"context":"map grows","needed":"1. Cap.","investigation_points":["read Cache.kt"],"important_details":["unbounded"],"open_questions":[],"extra_files":[]}"""
                }

                user.contains("ROLE: review-fix") -> {
                    weakRoles += "review-fix"
                    """{"context":"map grows","needed":"1. Cap.\n2. Unit test for eviction.","investigation_points":["read Cache.kt"],"important_details":["unbounded","needs test"],"open_questions":[],"extra_files":[]}"""
                }

                else -> {
                    weakRoles += "other"
                    error("unexpected weak role: $user")
                }
            }
        }
        val strong = ChatModel { _, user ->
            check(!user.contains("ROLE: leftover-answers")) { "strong should not answer leftovers" }
            reviewPasses++
            if (reviewPasses == 1) {
                """{"verdict":"needs_changes","comments":["Needed must include a test"]}"""
            } else {
                """{"verdict":"approved","comments":[]}"""
            }
        }
        val draft = IssueTemplateGenerator(
            repoRoot = tempDir,
            backlogRoot = File(tempDir, "docs/internals/backlog"),
            clock = { Instant.parse("2026-08-23T18:30:00Z") },
        ).scaffold(
            IssueScaffoldRequest(
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
            write = false,
        )
        val out = IssueClarifier.tryClarify(
            draft = draft,
            repoRoot = tempDir,
            scratchDir = File(tempDir, "scratch-review-loop"),
            weak = weak,
            strong = strong,
        )
        assertEquals(listOf("author", "review-fix"), weakRoles)
        assertEquals(2, reviewPasses)
        assertEquals("approved", out.request.reviewVerdict)
        assertContains(out.markdown, "Unit test for eviction")
    }

    @Test
    fun filesystemImpactScannerFindsExternalIdentifierHits() {
        val origin = File(tempDir, "enforcer/src/main/kotlin/io/mazewall/Cache.kt")
        origin.parentFile.mkdirs()
        origin.writeText("object Cache { fun put() {} }\n")
        val caller = File(tempDir, "profiler/src/main/kotlin/io/mazewall/UsesCache.kt")
        caller.parentFile.mkdirs()
        caller.writeText("fun use() { Cache.put() }\n")
        val hits = FilesystemImpactScanner(tempDir).scan(
            listOf("Cache"),
            listOf("enforcer/src/main/kotlin/io/mazewall/Cache.kt"),
        )
        assertEquals(1, hits.size)
        assertEquals("profiler/src/main/kotlin/io/mazewall/UsesCache.kt", hits[0].file)
        assertEquals("Cache", hits[0].symbol)
        assertTrue(hits[0].snippet.contains("Cache.put"))
    }

    @Test
    fun weakInvestigatesSideEffectsUsingAstWhenAuthorSaysYes() {
        val origin = File(tempDir, "enforcer/src/main/kotlin/io/mazewall/Cache.kt")
        origin.parentFile.mkdirs()
        origin.writeText("object Cache { fun put() {} }\n")
        val caller = File(tempDir, "profiler/src/main/kotlin/io/mazewall/UsesCache.kt")
        caller.parentFile.mkdirs()
        caller.writeText("fun use() { Cache.put() }\n")
        val weakRoles = mutableListOf<String>()
        val weak = ChatModel { _, user ->
            when {
                user.contains("ROLE: author") -> {
                    weakRoles += "author"
                    """{"context":"cache cap","needed":"1. Cap.","has_side_effects":true,"investigation_points":["read Cache"],"important_details":["shared type"],"open_questions":[],"extra_files":[],"side_effects":[]}"""
                }

                user.contains("ROLE: side-effects") -> {
                    weakRoles += "side-effects"
                    check(user.contains("UsesCache.kt")) { "AST artifact missing from prompt:\n$user" }
                    """{"context":"cache cap","needed":"1. Cap.\n2. Update profiler callers.","has_side_effects":true,"investigation_points":["AST callers of Cache"],"important_details":["profiler UsesCache calls Cache.put"],"open_questions":[],"extra_files":["profiler/src/main/kotlin/io/mazewall/UsesCache.kt"],"side_effects":["profiler UsesCache.kt calls Cache.put"]}"""
                }

                else -> error("unexpected weak role: $user")
            }
        }
        val strong = ChatModel { _, user ->
            check(!user.contains("ROLE: leftover-answers")) { "strong should not answer leftovers" }
            """{"verdict":"approved","comments":[]}"""
        }
        val draft = IssueTemplateGenerator(
            repoRoot = tempDir,
            backlogRoot = File(tempDir, "docs/internals/backlog"),
            clock = { Instant.parse("2026-08-23T18:30:00Z") },
        ).scaffold(
            IssueScaffoldRequest(
                title = "Cap cache",
                category = "code_health",
                severity = "MEDIUM",
                priority = "high",
                component = "enforcer",
                explicitFiles = listOf("enforcer/src/main/kotlin/io/mazewall/Cache.kt"),
                explicitModules = listOf(":enforcer"),
                symbols = listOf("Cache"),
                dependencies = emptyList(),
            ),
            write = false,
        )
        val out = IssueClarifier.tryClarify(
            draft = draft,
            repoRoot = tempDir,
            scratchDir = File(tempDir, "scratch-side"),
            weak = weak,
            strong = strong,
        )
        assertEquals(listOf("author", "side-effects"), weakRoles)
        assertEquals(true, out.request.hasSideEffects)
        assertContains(out.markdown, "## Side effects")
        assertContains(out.markdown, "UsesCache")
        assertContains(out.markdown, "AST identifier scan")
        assertTrue(out.files.any { it.contains("UsesCache.kt") })
        assertEquals("approved", out.request.reviewVerdict)
    }

    @Test
    fun noSideEffectInvestigationWhenAuthorSaysNo() {
        val weakRoles = mutableListOf<String>()
        val weak = ChatModel { _, user ->
            weakRoles += when {
                user.contains("ROLE: author") -> "author"
                user.contains("ROLE: side-effects") -> "side-effects"
                user.contains("ROLE: investigator") -> "investigate"
                user.contains("ROLE: review-fix") -> "review-fix"
                else -> "other"
            }
            """{"context":"docs typo","needed":"1. Fix the comment.","has_side_effects":false,"investigation_points":["read comment"],"important_details":["comment only"],"open_questions":[],"extra_files":[],"side_effects":[]}"""
        }
        val draft = IssueTemplateGenerator(
            repoRoot = tempDir,
            backlogRoot = File(tempDir, "docs/internals/backlog"),
            clock = { Instant.parse("2026-08-23T18:30:00Z") },
        ).scaffold(
            IssueScaffoldRequest(
                title = "Fix comment",
                category = "code_health",
                severity = "LOW",
                priority = "low",
                component = "enforcer",
                explicitFiles = listOf("enforcer/a.kt"),
                explicitModules = listOf(":enforcer"),
                symbols = emptyList(),
                dependencies = emptyList(),
            ),
            write = false,
        )
        val out = IssueClarifier.tryClarify(
            draft = draft,
            repoRoot = tempDir,
            scratchDir = File(tempDir, "scratch-no-side"),
            weak = weak,
            strong = ChatModel { _, _ -> """{"verdict":"approved","comments":[]}""" },
        )
        assertEquals(listOf("author"), weakRoles)
        assertEquals(false, out.request.hasSideEffects)
        assertFalse(out.markdown.contains("## Side effects"))
        assertEquals("approved", out.request.reviewVerdict)
    }

    @Test
    fun clarifyModelsNeverUsesApiKeys() {
        val pair = ClarifyModels.resolve { name ->
            if (name.contains("KEY")) "secret" else null
        }
        assertEquals(null, pair.first)
        assertEquals(null, pair.second)
    }

    @Test
    fun acpCommandResolverReadsEnvAndPresets() {
        val env = mapOf("ISSUE_CLARIFY_ACP" to "agy --acp")
        val pair = AcpCommandResolver.resolvePair { env[it] }
        assertEquals(listOf("agy", "--acp"), pair?.first)
        assertEquals(listOf("agy", "--acp"), pair?.second)
        assertEquals(null, AcpCommandResolver.resolvePair { null })
    }

    @Test
    fun acpSessionCollectsPromptTextAndAllowsReadOnce() {
        val clientToAgent = java.io.PipedOutputStream()
        val agentIn = java.io.PipedInputStream(clientToAgent, 65536)
        val agentToClient = java.io.PipedOutputStream()
        val clientIn = java.io.PipedInputStream(agentToClient, 65536)
        val agentOut = java.io.BufferedWriter(java.io.OutputStreamWriter(agentToClient, Charsets.UTF_8))
        val agentReader = java.io.BufferedReader(java.io.InputStreamReader(agentIn, Charsets.UTF_8))
        val agent = Thread {
            fun reply(line: String) {
                agentOut.write(line)
                agentOut.write("\n")
                agentOut.flush()
            }
            agentReader.readLine()
            reply("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":1}}""")
            agentReader.readLine()
            agentReader.readLine()
            reply("""{"jsonrpc":"2.0","id":2,"result":{"sessionId":"s1"}}""")
            agentReader.readLine()
            reply("""{"jsonrpc":"2.0","method":"session/update","params":{"update":{"content":{"type":"text","text":"{\"questions\":[]}"}}}}""")
            reply("""{"jsonrpc":"2.0","id":99,"method":"session/request_permission","params":{}}""")
            val allow = agentReader.readLine()
            check(allow.contains("allow-once"))
            reply("""{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}""")
        }
        agent.isDaemon = true
        agent.start()
        val session = AcpJsonRpcSession(
            java.io.BufferedWriter(java.io.OutputStreamWriter(clientToAgent, Charsets.UTF_8)),
            java.io.BufferedReader(java.io.InputStreamReader(clientIn, Charsets.UTF_8)),
        )
        session.initialize()
        val sid = session.newSession()
        assertEquals("s1", sid)
        val text = session.prompt(sid, "ask")
        assertTrue(text.contains("questions"))
        agent.join(5_000)
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
    fun renderWritesSideEffectsSectionWhenPresent() {
        val markdown = IssueTemplateGenerator.render(
            idInstant = Instant.parse("2026-08-23T18:30:00Z").atZone(ZoneOffset.UTC),
            slug = "cap-cache",
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
                contextBody = "map grows",
                neededBody = "cap it",
                hasSideEffects = true,
                sideEffectImpacts = listOf("profiler UsesCache.kt calls Cache.put"),
                investigationPoints = listOf("AST identifier scan: 1 hits outside origin files"),
            ),
            files = listOf("enforcer/a.kt"),
            modules = listOf(":enforcer"),
            verifyCheap = emptyList(),
            coreLock = false,
        )
        assertContains(markdown, "has_side_effects: true")
        assertContains(markdown, "## Side effects")
        assertContains(markdown, "profiler UsesCache.kt calls Cache.put")
        assertContains(markdown, "## Investigation")
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
