import org.gradle.api.publish.PublishingExtension
import java.io.File

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.spotbugs)
    alias(libs.plugins.dependencyCheck)
    alias(libs.plugins.pitest) apply false
    id("jacoco")
    id("base")
}

allprojects {
    group = "io.mazewall"
    version = "0.0.1-prealpha-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    // Disable detekt globally
    tasks.configureEach {
        if (name.contains("detekt", ignoreCase = true)) {
            enabled = false
        }
    }

    if (!project.path.startsWith(":demos")) {
        apply(plugin = "org.jlleitschuh.gradle.ktlint")
        configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            version.set("1.3.1")
            verbose.set(true)
            outputToConsole.set(true)
            coloredOutput.set(true)
            reporters {
                reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
                reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.HTML)
            }
        }
        // Disable ktlint formatting/checking tasks because the ktlint engine (even 1.3.1+)
        // fails to parse Kotlin 2.x context parameters syntax ("context(arena: Arena)").
        // TODO: Re-enable these tasks once KtLint officially supports named context parameters syntax in Kotlin 2.4+.
        tasks.configureEach {
            if (name.contains("ktlint", ignoreCase = true)) {
                enabled = false
            }
        }
    }

    // JitPack Shim: Satisfy JitPack's broken 'listDeps' task by injecting
    // the missing 'configurations' property into the task instance.
    tasks.matching { it.name == "listDeps" }.configureEach {
        // Using extensions/extra to satisfy Groovy property resolution
        (this as? ExtensionAware)?.extra?.set("configurations", project.configurations)
    }

    // Aggressively skip tests on JitPack because the host kernel (4.4)
    // is too old for Seccomp/Landlock/FFM and will cause failures.
    if (System.getenv("JITPACK") == "true") {
        tasks.withType<Test>().configureEach {
            enabled = false
        }
    }

    // Ensure code is formatted before compilation or check to prevent build failures
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        if (!project.path.startsWith(":demos")) {
            dependsOn("ktlintFormat")
        }
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_22)
            freeCompilerArgs.add("-Xcontext-parameters")
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(22)
    }

    tasks.matching { it.name == "ktlintCheck" || it.name == "ktlintTestSourceSetCheck" || it.name == "ktlintMainSourceSetCheck" }.configureEach {
        if (!project.path.startsWith(":demos")) {
            dependsOn("ktlintFormat")
        }
    }

    // Also format Kotlin scripts (like build.gradle.kts)
    tasks.matching { it.name == "kotlinSourcesJar" }.configureEach {
        if (!project.path.startsWith(":demos")) {
            dependsOn("ktlintFormat")
        }
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

dependencyCheck {
    failBuildOnCVSS = 7.0f
    format = "ALL"
    suppressionFile = "$rootDir/config/dependency-check/suppressions.xml"
    System.getenv("NVD_API_KEY")?.takeIf { it.isNotBlank() }?.let {
        nvd.apiKey = it
    }
    // Disable OSS Index as it requires separate credentials and is currently failing in CI
    analyzers {
        ossIndexEnabled = false
    }
    // Skip checking demo projects since they are deliberately vulnerable
    skipProjects = listOf(":demos:cli-demo", ":demos:vulnerable-web-app")
    // Only scan production compile and runtime configurations to avoid scanning build tooling like detekt and ktlint
    scanConfigurations = listOf("compileClasspath", "runtimeClasspath")
}

tasks.named("dependencyCheckAnalyze").configure {
    onlyIf("NVD API Key is required for CI performance") {
        System.getenv("CI") != "true" || !System.getenv("NVD_API_KEY").isNullOrBlank()
    }
}

detekt {
    buildUponDefaultConfig = false
    allRules = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    source.setFrom(files("src/main/kotlin"))
    failOnSeverity = dev.detekt.gradle.extensions.FailOnSeverity.Never
}

subprojects {
    if (project.path.startsWith(":demos") || project.path.startsWith(":tools")) {
        return@subprojects
    }
    apply(plugin = "java")
    apply(plugin = "maven-publish")
    apply(plugin = "base")
    apply(plugin = "jacoco")
    apply(plugin = "dev.detekt")
    apply(plugin = "com.github.spotbugs")

    detekt {
        baseline = file("$rootDir/config/detekt/${project.name}-baseline.xml")
        source.setFrom(files("src/main/kotlin"))
    }

    tasks.configureEach {
        if (name.startsWith("detekt")) {
            try {
                val method = this.javaClass.getMethod("getJdkHome")
                val property = method.invoke(this) as org.gradle.api.file.DirectoryProperty
                property.set(layout.projectDirectory.dir(providers.systemProperty("java.home")))
            } catch (_: NoSuchMethodException) {
                // Ignore tasks that do not have getJdkHome
            }
        }
    }

    extensions.configure<PublishingExtension> {
        repositories {
            System.getenv("GITHUB_ACTOR")?.let { actor ->
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/Pilleo/mazewall")
                    credentials {
                        username = actor
                        password = System.getenv("GITHUB_TOKEN")
                    }
                }
            }
        }
    }

    spotbugs {
        ignoreFailures.set(false)
        showStackTraces.set(true)
        showProgress.set(true)
        effort.set(com.github.spotbugs.snom.Effort.MAX)
        reportLevel.set(com.github.spotbugs.snom.Confidence.HIGH)
        excludeFilter.set(file("$rootDir/config/spotbugs/exclude.xml"))
    }

    dependencies {
        "spotbugsPlugins"(
            rootProject.extensions
                .getByType<VersionCatalogsExtension>()
                .named("libs")
                .findLibrary("findsecbugs")
                .get(),
        )
    }

    extensions.configure<JacocoPluginExtension> {
        toolVersion =
            rootProject.extensions
                .getByType<VersionCatalogsExtension>()
                .named("libs")
                .findVersion("jacoco")
                .get()
                .requiredVersion
    }

    tasks.withType<Test>().configureEach {
        systemProperty("io.mazewall.test", "true")
    }

    tasks.withType<org.gradle.testing.jacoco.tasks.JacocoReport>().configureEach {
        // Enforce ordering so we aggregate execution data from both host unit tests and container integration tests
        mustRunAfter(rootProject.tasks.named("test"))
        dependsOn(tasks.withType<Test>())
        mustRunAfter(tasks.withType<Test>())
        executionData.setFrom(fileTree(project.layout.buildDirectory.dir("jacoco")).include("*.exec"))
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    tasks.withType<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>().configureEach {
        // Enforce ordering so we aggregate execution data from both host unit tests and container integration tests
        mustRunAfter(rootProject.tasks.named("test"))
        dependsOn(tasks.withType<org.gradle.testing.jacoco.tasks.JacocoReport>())
        mustRunAfter(tasks.withType<org.gradle.testing.jacoco.tasks.JacocoReport>())
        executionData.setFrom(fileTree(project.layout.buildDirectory.dir("jacoco")).include("*.exec"))
        violationRules {
            if (project.name == "enforcer") {
                rule {
                    element = "BUNDLE"
                    limit {
                        counter = "INSTRUCTION"
                        value = "COVEREDRATIO"
                        minimum = "0.50".toBigDecimal()
                    }
                }
                rule {
                    element = "CLASS"
                    includes = listOf("io.mazewall.landlock.Landlock*")
                    limit {
                        counter = "INSTRUCTION"
                        value = "COVEREDRATIO"
                        minimum = "0.0".toBigDecimal()
                    }
                }
            } else if (project.name == "profiler") {
                rule {
                    element = "BUNDLE"
                    limit {
                        counter = "INSTRUCTION"
                        value = "COVEREDRATIO"
                        minimum = "0.30".toBigDecimal()
                    }
                }
            }
        }
    }

    plugins.withId("java") {
        tasks.named("check") {
            dependsOn(tasks.withType<Test>())
            dependsOn(tasks.withType<org.gradle.testing.jacoco.tasks.JacocoReport>())
            dependsOn(tasks.withType<org.gradle.testing.jacoco.tasks.JacocoCoverageVerification>())
        }
    }
}

evaluationDependsOn(":profiler")

tasks.register<JavaExec>("runTriage") {
    group = "verification"
    description = "Gathers system telemetry and diagnostics on failure."
    classpath = files(":profiler:classes", ":profiler:runtimeClasspath")
    mainClass.set("io.mazewall.profiler.triage.DiagnosticTriageRunner")
    
    val testFailures = objects.listProperty<Boolean>().apply {
        set(provider {
            subprojects.flatMap { it.tasks.withType<Test>() }.map { it.state.failure != null }
        })
    }
    
    // Only run this diagnostic triage task if the test execution actually failed.
    onlyIf {
        testFailures.get().any { it }
    }
}

// Wire the triage runner to finalize test execution across all subprojects
subprojects {
    tasks.withType<Test>().configureEach {
        finalizedBy(rootProject.tasks.named("runTriage"))
    }
}

val generateKnowledgeMap by tasks.registering {
    group = "documentation"
    description = "Generates and updates the Mermaid-based architectural knowledge graph"
    val projectDir = layout.projectDirectory.asFile
    doLast {
        class KnowledgeMapGenerator(private val rootDir: File) {
            private val docsDir = File(rootDir, "docs/internals")
            private val mapsDir = File(docsDir, "maps")
            private val backlogDir = File(docsDir, "backlog")
            private val targetMapFile = File(docsDir, "architectural_map.md")

            fun generate() {
                println("Generating scoped knowledge sub-maps...")
                if (!docsDir.isDirectory) {
                    println("Error: docs directory not found at ${docsDir.absolutePath}")
                    return
                }

                val designDocs = (docsDir.listFiles() ?: emptyArray())
                    .filter { it.isFile && it.name.endsWith(".md") }
                    .sortedBy { it.name }

                val backlogIssues = backlogDir.walkTopDown()
                    .filter { it.isFile && it.name.startsWith("issue-") && it.name.endsWith(".md") }
                    .toList()
                    .sortedBy { it.name }

                val enforcerOk = writeSubMap(
                    scopeName = "enforcer",
                    title = "Enforcer Module Knowledge Map",
                    designDocs = designDocs,
                    backlogIssues = backlogIssues,
                    description = "Maps design documents, source files, and open issues for the `:enforcer` module (Seccomp-BPF, Landlock, FFM bindings)."
                )

                val profilerOk = writeSubMap(
                    scopeName = "profiler",
                    title = "Profiler Module Knowledge Map",
                    designDocs = designDocs,
                    backlogIssues = backlogIssues,
                    description = "Maps design documents, source files, and open issues for the `:profiler` module (USER_NOTIF, strace, iterative Landlock)."
                )

                updateRootIndex(enforcerOk, profilerOk)
                println("Done.")
            }

            private fun parseYamlFrontmatter(file: File): Map<String, Any>? {
                val content = file.readText()
                val regex = Regex("^---\\s*\\r?\\n(.*?)\\r?\\n---\\s*\\r?\\n", RegexOption.DOT_MATCHES_ALL)
                val matchResult = regex.find(content) ?: return null
                val yamlText = matchResult.groupValues[1]
                val metadata = mutableMapOf<String, Any>()
                for (line in yamlText.split("\n")) {
                    val parts = line.split(":", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim()
                        val rawVal = parts[1].trim().removeSurrounding("\"").removeSurrounding("'")
                        if (rawVal.startsWith("[") && rawVal.endsWith("]")) {
                            val items = rawVal.removeSurrounding("[", "]")
                                .split(",")
                                .map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
                                .filter { it.isNotEmpty() }
                            metadata[key] = items
                        } else {
                            metadata[key] = rawVal
                        }
                    }
                }
                return metadata
            }

            private fun generateMermaidForScope(scopeName: String, designDocs: List<File>, backlogIssues: List<File>): String? {
                val nodes = mutableSetOf<String>()
                val edges = mutableSetOf<String>()
                val clicks = mutableSetOf<String>()

                for (doc in designDocs) {
                    val filename = doc.name
                    if (filename in listOf("architectural_map.md", "README.md", "documentation_standards.md")) {
                        continue
                    }

                    val meta = parseYamlFrontmatter(doc) ?: continue
                    val title = meta["title"] as? String ?: continue
                    val docScope = (meta["scope"] as? String)?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""

                    if (scopeName != "all" && docScope != scopeName) {
                        continue
                    }

                    val docId = filename.replace(".md", "").replace("-", "_")
                    nodes.add("    $docId[\"📄 Design: $title\"]")
                    clicks.add("    click $docId \"../$filename\"")

                    val targetsRaw = meta["target_files"]
                    val targets = when (targetsRaw) {
                        is List<*> -> targetsRaw.filterIsInstance<String>()
                        is String -> listOf(targetsRaw)
                        else -> emptyList()
                    }

                    for (target in targets) {
                        val cleanTarget = File(target).name
                        val targetId = cleanTarget.replace(".", "_").replace("-", "_")
                        nodes.add("    $targetId[\"💻 Source: $cleanTarget\"]")
                        edges.add("    $targetId -->|Governed by| $docId")
                    }
                }

                for (issue in backlogIssues) {
                    val filename = issue.name
                    val meta = parseYamlFrontmatter(issue) ?: continue
                    val title = meta["title"] as? String ?: continue
                    if (meta["status"] != "open") {
                        continue
                    }

                    val issueScope = (meta["scope"] as? String)?.removeSurrounding("\"")?.removeSurrounding("'") ?: ""
                    if (scopeName != "all" && issueScope.isNotEmpty() && issueScope != scopeName) {
                        continue
                    }

                    val issueId = filename.replace(".md", "").replace("-", "_")
                    val severity = meta["severity"] as? String ?: "MEDIUM"
                    nodes.add("    $issueId[\"🔴 Issue: $title ($severity)\"]")
                    val relPath = issue.relativeTo(backlogDir).path
                    clicks.add("    click $issueId \"../backlog/$relPath\"")

                    val issueContent = issue.readText()
                    val targetRegex = Regex("\\*\\*Target( Area)?:\\*\\*\\s*`(.*?)`")
                    val targetMatch = targetRegex.find(issueContent)
                    if (targetMatch != null) {
                        val targetPath = targetMatch.groupValues[2]
                        val cleanTarget = File(targetPath).name
                        val targetId = cleanTarget.replace(".", "_").replace("-", "_")
                        nodes.add("    $targetId[\"💻 Source: $cleanTarget\"]")
                        edges.add("    $issueId -->|Affects| $targetId")
                    }
                }

                if (nodes.isEmpty()) {
                    return null
                }

                val result = mutableListOf("```mermaid", "graph TD")
                result.addAll(nodes.sorted())
                result.addAll(edges.sorted())
                result.addAll(clicks.sorted())
                result.add("```")
                return result.joinToString("\n")
            }

            private fun writeSubMap(scopeName: String, title: String, designDocs: List<File>, backlogIssues: List<File>, description: String): Boolean {
                val mermaid = generateMermaidForScope(scopeName, designDocs, backlogIssues) ?: return false

                mapsDir.mkdirs()
                val outPath = File(mapsDir, "${scopeName}_map.md")
                val content = """# $title

$description

> Auto-generated by `build.gradle.kts`. Do not edit manually.
> Root map: [architectural_map.md](../architectural_map.md)

<!-- KNOWLEDGE_MAP_START -->

$mermaid

<!-- KNOWLEDGE_MAP_END -->
"""
                outPath.writeText(content)
                println("  Wrote ${outPath.absolutePath}")
                return true
            }

            private fun updateRootIndex(enforcerOk: Boolean, profilerOk: Boolean) {
                if (!targetMapFile.exists()) {
                    println("Error: ${targetMapFile.absolutePath} not found.")
                    return
                }

                val content = targetMapFile.readText()
                val startMarker = "<!-- KNOWLEDGE_MAP_START -->"
                val endMarker = "<!-- KNOWLEDGE_MAP_END -->"

                val links = mutableListOf<String>()
                if (enforcerOk) {
                    links.add("- [enforcer_map.md](maps/enforcer_map.md) — BPF filter, containment, FFM bindings")
                }
                if (profilerOk) {
                    links.add("- [profiler_map.md](maps/profiler_map.md) — USER_NOTIF daemon, trace events, iterative profiler")
                }

                val indexSection = listOf(
                    "### Sub-Maps (auto-generated per module):",
                    "",
                    links.joinToString("\n"),
                    "",
                    "> Each sub-map links design documents to source files and open backlog issues for that scope."
                ).joinToString("\n")

                val newContent = if (content.contains(startMarker) && content.contains(endMarker)) {
                    val before = content.substringBefore(startMarker)
                    val after = content.substringAfter(endMarker)
                    "$before$startMarker\n\n$indexSection\n\n$endMarker$after"
                } else {
                    "$content\n\n## Dynamic Knowledge Map Index\n\n$startMarker\n\n$indexSection\n\n$endMarker\n"
                }

                targetMapFile.writeText(newContent)
                println("  Updated root index in ${targetMapFile.absolutePath}")
            }
        }
        KnowledgeMapGenerator(projectDir).generate()
    }
}

val installGitHooks by tasks.registering(Copy::class) {
    group = "git"
    description = "Installs the pre-commit audit and verification hook"
    from("$rootDir/scripts/git-audit-hook.sh") {
        rename { "pre-commit" }
    }
    into(file("$rootDir/.git/hooks"))
    val targetFile = file("$rootDir/.git/hooks/pre-commit")
    doLast {
        targetFile.setExecutable(true)
    }
}

tasks.named("check") {
    dependsOn(generateKnowledgeMap)
    dependsOn(installGitHooks)
}




