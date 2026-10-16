package io.mazewall.build

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/** Real checkout, index, alias and permission fixtures; no fake filesystem or Git inventory. */
object GradleSourceInventoryTest {
    private const val EAGER = "val eager = configurations[\"runtimeClasspath\"].asPath\n"
    private const val ACTION = "doFirst { println(configurations[\"runtimeClasspath\"].asPath) }\n"

    @JvmStatic
    fun run() {
        check(System.getProperty("user.name") != "root") { "Permission fixtures require an unprivileged UID" }
        val cases = linkedMapOf<String, Fixture.() -> Unit>(
            "unreachable_generated_directories_are_pruned" to {
                for (dir in listOf(".gradle/configuration-cache/private", "module/.gradle/private", "build/private", "module/build/private", ".worktrees/private", ".git/private")) deny(write("$dir/build.gradle.kts", EAGER).parent)
                clean()
            },
            "reachable_generated_scripts_are_not_source" to {
                for (dir in listOf(".gradle/configuration-cache", "module/.gradle", "build", "module/build", ".worktrees/other")) write("$dir/build.gradle.kts", EAGER)
                clean()
            },
            "only_local_ignored_scratch_is_ignored" to {
                write(".gitignore", "/owned-scratch/\n")
                deny(write("owned-scratch/private/build.gradle", EAGER).parent)
                clean()
                eager(write("integrations/new-adapter/build.gradle.kts", EAGER))
            },
            "normal_untracked_source_and_module_folders_are_checked" to {
                for (name in listOf("integrations/framework/build.gradle", "tests/consumer/build.gradle.kts", "tools/orchestrator/build.gradle.kts", "module with spaces/build.gradle.kts", ".source-fixture/build.gradle.kts")) {
                    val file = write(name, EAGER); eager(file); Files.delete(file)
                }
            },
            "tracked_source_is_checked_even_if_now_ignored" to { val p = write("tracked-source/build.gradle.kts", EAGER); git("add", "--", relative(p)); write(".gitignore", "/tracked-source/\n"); eager(p) },
            "tracked_source_build_package_is_not_generated_output" to { val p = write("module/src/main/example/build/build.gradle.kts", EAGER); git("add", "--", relative(p)); eager(p) },
            "dot_ignore_cannot_hide_normal_source" to { write(".ignore", "integrations/\n"); eager(write("integrations/new-adapter/build.gradle.kts", EAGER)) },
            "ripgrep_config_cannot_force_private_scratch_traversal" to {
                write(".gitignore", "/owned-scratch/\n"); environment["RIPGREP_CONFIG_PATH"] = write("rg-config", "--no-ignore\n--hidden\n").toString()
                deny(write("owned-scratch/private/build.gradle.kts", EAGER).parent); clean(); eager(write("tests/new-consumer/build.gradle.kts", EAGER))
            },
            "foreign_git_context_is_not_trusted_as_cwd_inventory" to {
                val foreign = Files.createTempDirectory("foreign-inventory-")
                command(listOf("git", "-C", foreign.toString(), "init", "--quiet"))
                val p = write("tracked-source/build.gradle.kts", EAGER); git("add", "--", relative(p)); write(".gitignore", "/tracked-source/\n")
                environment["GIT_DIR"] = foreign.resolve(".git").toString(); environment["GIT_WORK_TREE"] = foreign.toString(); eager(p)
            },
            "unreadable_untracked_source_directory_still_fails" to { deny(write("integrations/private-source/build.gradle.kts", ACTION).parent); failure("integrations/private-source") },
            "unreadable_source_file_still_fails" to { deny(write("tests/probe/build.gradle.kts", ACTION)); failure("tests/probe/build.gradle.kts") },
            "unreadable_tracked_ignored_source_still_fails" to { val p = write("tracked-source/build.gradle.kts", ACTION); git("add", "--", relative(p)); write(".gitignore", "/tracked-source/\n"); deny(p); failure("tracked-source/build.gradle.kts") },
            "alternate_index_cannot_hide_ignored_tracked_eager_source" to {
                val p = write("tracked-source/build.gradle.kts", EAGER); git("add", "--", relative(p)); write(".gitignore", "/tracked-source/\n")
                val alternate = alternate(); val before = listOf(hash(root.resolve(".git/index")), hash(alternate)); eager(p)
                check(before == listOf(hash(root.resolve(".git/index")), hash(alternate)))
            },
            "alternate_index_cannot_hide_unreadable_ignored_tracked_source" to { val p = write("tracked-source/build.gradle.kts", ACTION); git("add", "--", relative(p)); write(".gitignore", "/tracked-source/\n"); alternate(); deny(p); failure("tracked-source/build.gradle.kts") },
            "contained_source_file_links_checked_tracked_and_untracked" to {
                val target = write("regular-target.txt", ACTION)
                val alias = link("linked-source.gradle.kts", target.toString()); clean(); git("add", "--", relative(alias)); clean()
            },
            "contained_source_directory_link_checked" to { val target = write("adapter-source/build.gradle.kts", ACTION).parent; link("integrations/local-adapter", target.toString()); clean() },
            "dangling_source_link_rejected" to { link("dangling.gradle.kts", "absent"); failure("Unsupported source symlink:") },
            "unreadable_source_link_target_rejected_without_reading_target" to { val p = write("unreadable-target.txt", ACTION); deny(p); link("unreadable-link.gradle.kts", p.toString()); failure("unreadable-link.gradle.kts") },
            "source_link_to_private_state_rejected_without_traversal" to { val p = write(".gradle/private/build.gradle.kts", ACTION).parent; deny(p); link("integrations/private-target", p.toString()); failure("private boundary") },
            "source_link_to_external_target_rejected" to { val external = Files.createTempFile("external-source-", ".txt"); Files.writeString(external, ACTION); link("external.gradle.kts", external.toString()); failure("leaves checkout") },
            "indexed_source_ancestor_alias_checked_even_if_ignored" to {
                val p = write("tracked-source/build.gradle.kts", ACTION); git("add", "--", relative(p)); write(".gitignore", "/tracked-source/\n/real-target/\n")
                Files.move(p.parent, root.resolve("real-target")); link("tracked-source", "real-target"); clean()
            },
            "indexed_ignored_directory_alias_checked" to { write("real-target/build.gradle.kts", ACTION); link("linked-adapter", "real-target"); git("add", "--", "linked-adapter"); write(".gitignore", "/linked-adapter\n/real-target/\n"); clean() },
            "actual_rootalias_shape_without_gradle_source_is_legitimate" to { write("profiler/hotspot-agentless/cmd/mw-gadget-build/main.go", "package main\n"); link("experiments/hotspot-agentless/cmd/mw-gadget-build", "../../../profiler/hotspot-agentless/cmd/mw-gadget-build"); git("add", "--", "experiments/hotspot-agentless/cmd/mw-gadget-build"); clean() },
            "directory_alias_never_hides_eager_gradle_in_ignored_target" to { val p = write("real-target/build.gradle.kts", EAGER); write(".gitignore", "/real-target/\n"); link("visible-adapter", "real-target"); eager(p) },
            "global_git_ignore_cannot_hide_visible_alias_eager_target" to { val p = globalAlias(EAGER); val before = hash(root.resolve(".git/global-config")); eager(p); check(hash(root.resolve(".git/global-config")) == before) },
            "global_git_ignore_cannot_hide_visible_alias_unreadable_target" to { val p = globalAlias(ACTION); deny(p); failure("Permission denied") },
            "alias_expands_symlink_before_parent_component" to { pivot("../hidden/deep"); link("adapter", "real-target/pivot/../module"); eager(root.resolve("hidden/module/build.gradle.kts")) },
            "gradle_file_alias_uses_link_language_and_checks_target" to { val p = write("target.txt", EAGER); eager(link("alias.gradle.kts", p.toString())) },
            "alias_dot_and_absolute_targets_expand_before_parent" to {
                pivot("../hidden/./deep")
                for (target in listOf("./real-target/./pivot/../module", "$root/real-target/./pivot/../module")) {
                    val p = link("adapter", target); eager(root.resolve("hidden/module/build.gradle.kts")); Files.delete(p)
                }
            },
            "alias_absolute_nested_target_expands_before_parent" to { pivot("$root/hidden/./deep"); link("adapter", "real-target/pivot/../module"); eager(root.resolve("hidden/module/build.gradle.kts")) },
            "alias_non_directory_before_parent_is_rejected" to { write(".gitignore", "/real-target/\n"); write("real-target/file", "not a directory\n"); write("real-target/module/build.gradle.kts", ACTION); link("adapter", "real-target/file/../module"); failure("Not a directory") },
            "alias_parent_cannot_hide_private_traversal" to {
                write("module/build.gradle.kts", ACTION)
                for (private in listOf(".git", ".gradle", ".worktrees", "build")) {
                    Files.createDirectories(root.resolve(private))
                    for (target in listOf("$private/../module", "$root/./$private/../module")) { val p = link("adapter", target); failure("private boundary"); Files.delete(p) }
                }
            },
            "alias_parent_cannot_hide_external_traversal" to { write("module/build.gradle.kts", ACTION); link("adapter", "../${root.fileName}/module"); failure("leaves checkout") },
            "alias_parent_cannot_hide_nested_external_target" to { write(".gitignore", "/real-target/\n"); write("real-target/module/build.gradle.kts", ACTION); link("real-target/pivot", Files.createTempDirectory("external-pivot-").toString()); link("adapter", "real-target/pivot/../module"); failure("leaves checkout") },
            "alias_parent_cannot_hide_unreadable_intermediate_directory" to { write(".gitignore", "/real-target/\n"); write("real-target/module/build.gradle.kts", ACTION); val dir = Files.createDirectories(root.resolve("real-target/private")); deny(dir); link("adapter", "real-target/private/../module"); failure("Permission denied") },
            "repeated_aliases_do_not_skip_physical_source_coverage" to { val p = write("real-target/build.gradle.kts", EAGER); write(".gitignore", "/real-target/\n"); link("first-adapter", "real-target"); link("second-adapter", "real-target"); eager(p) },
            "directory_alias_cycle_is_bounded_failure" to { write("real-target/main.go", "package main\n"); link("real-target/back-to-root", root.toString()); link("adapter", "real-target"); failure("cycle") },
            "file_alias_cycle_is_bounded_failure" to { link("first.gradle.kts", "second.gradle.kts"); link("second.gradle.kts", "first.gradle.kts"); failure("cycle") },
            "directory_alias_unreadable_source_still_fails" to { val p = write("real-target/build.gradle.kts", ACTION); write(".gitignore", "/real-target/\n"); deny(p); link("adapter", "real-target"); failure("Permission denied") },
            "alias_cannot_cross_into_generated_build_target" to { write("build/generated/build.gradle.kts", ACTION); link("adapter", "build/generated"); failure("private boundary") },
            "alias_graph_cycle_between_repeated_physical_directories_fails" to { write("first/main.go", "package main\n"); write("second/main.go", "package main\n"); link("first/second", "../second"); link("second/first", "../first"); failure("directory cycle") },
            "gradle_file_alias_retains_groovy_literal_language" to { val p = write("target.txt", "def pattern = /literal .asPath/\n"); link("alias.gradle", p.toString()); clean() },
            "indexed_alias_suffix_cannot_enter_generated_target" to { val p = write("tracked-adapter/build/private/build.gradle.kts", ACTION); git("add", "--", relative(p)); Files.move(root.resolve("tracked-adapter"), root.resolve("physical-adapter")); link("tracked-adapter", "physical-adapter"); failure("private boundary") },
            "indexed_alias_under_generated_ancestor_is_rejected" to { write("build/target.txt", ACTION); link("build/alias.gradle.kts", "target.txt"); git("add", "--", "build/alias.gradle.kts"); failure("private boundary") },
            "indexed_regular_source_under_build_package_remains_covered" to { val p = write("app/build/build.gradle.kts", EAGER); git("add", "--", relative(p)); eager(p) },
            "file_alias_targets_ending_in_slash_are_rejected" to { val p = write("regular-target.txt", ACTION); for (target in listOf("regular-target.txt/", "$p/")) { val alias = link("alias.gradle.kts", target); failure("Not a directory"); Files.delete(alias) } },
            "directory_alias_target_ending_in_slash_is_valid" to { write("real-target/build.gradle.kts", ACTION); link("directory-slash.gradle.kts", "real-target/"); clean() },
            "indexed_source_suffix_after_directory_alias_target_ending_in_slash" to {
                val p = write("adapter/build.gradle.kts", EAGER); git("add", "--", relative(p)); Files.move(p.parent, root.resolve("real-target")); link("adapter", "real-target/"); write(".gitignore", "/real-target/\n")
                check(scan() == listOf("Eager Gradle configuration resolution: adapter/build.gradle.kts:1: ${EAGER.trim()}", "Eager Gradle configuration resolution: real-target/build.gradle.kts:1: ${EAGER.trim()}"))
            },
            "file_alias_can_expand_through_directory_alias_target_ending_in_slash" to { write("real-target/source.gradle.kts", EAGER); link("adapter", "real-target/"); val p = link("alias.gradle.kts", "adapter/source.gradle.kts"); git("add", "--", relative(p)); write(".gitignore", "/real-target/\n/adapter\n"); eager(p) },
            "alias_target_directory_unreadable_still_fails" to { val p = write("real-target/build.gradle.kts", ACTION).parent; write(".gitignore", "/real-target/\n"); deny(p); link("adapter", "real-target"); failure("Permission denied") },
            "alias_unsupported_target_type_fails_without_opening_it" to { command(listOf("mkfifo", root.resolve("owned-pipe").toString())); link("alias.gradle.kts", "owned-pipe"); failure("unsupported target type") },
            "linked_worktree_uses_its_default_index" to {
                val original = root; val linked = original.resolve("owned-scratch/linked")
                git("worktree", "add", "--orphan", "-b", "fixture-source", linked.toString()); write(".gitignore", "/owned-scratch/\n")
                root = linked
                val p = write("tracked-source/build.gradle.kts", EAGER); git("add", "--", relative(p)); write(".gitignore", "/tracked-source/\n")
                val alternate = original.resolve(".git/alternate-empty.index"); val env = environment + ("GIT_INDEX_FILE" to alternate.toString())
                command(listOf("git", "read-tree", "--empty"), env); environment["GIT_INDEX_FILE"] = alternate.toString(); eager(p)
            },
        )
        cases.forEach { (name, body) ->
            Fixture().use { fixture ->
                try { body(fixture) } catch (failure: Throwable) { throw AssertionError("Source inventory case failed: $name", failure) }
            }
        }
        println("Kotlin source inventory: ${cases.size} real filesystem cases passed.")
    }

    private class Fixture : AutoCloseable {
        private val ownedRoot = Files.createTempDirectory("mazewall-source-inventory-")
        var root: Path = ownedRoot
        val environment = System.getenv().toMutableMap()
        private val denied = mutableListOf<Path>()

        init {
            val names = ProcessBuilder("git", "rev-parse", "--local-env-vars").start()
            names.inputStream.bufferedReader().use { it.readLines() }.forEach(environment::remove)
            check(names.waitFor() == 0)
            git("init", "--quiet")
            write("build.gradle.kts", ACTION)
        }

        fun write(name: String, text: String): Path = root.resolve(name).also { Files.createDirectories(it.parent); Files.writeString(it, text) }
        fun relative(path: Path): String = root.relativize(path).toString()
        fun link(name: String, target: String): Path = root.resolve(name).also { Files.createDirectories(it.parent); command(listOf("ln", "-s", "--", target, it.toString())) }
        fun git(vararg args: String): String = command(listOf("git") + args)
        fun command(args: List<String>, env: Map<String, String> = environment): String {
            val child = ProcessBuilder(args).directory(root.toFile()).apply { environment().clear(); environment().putAll(env) }.redirectErrorStream(true).start()
            val text = child.inputStream.bufferedReader().use { it.readText() }
            check(child.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) && child.exitValue() == 0) { "Fixture command failed: $args: $text" }
            return text
        }
        fun deny(path: Path) { Files.setPosixFilePermissions(path, emptySet()); denied.add(path); check(!Files.isReadable(path)) }
        fun scan(): List<String> = GradleSourceInventory(root, environment).scan()
        fun clean() { check(scan().isEmpty()) }
        fun eager(path: Path) { check(scan() == listOf("Eager Gradle configuration resolution: ${relative(path)}:1: ${EAGER.trim()}")) }
        fun failure(fragment: String) {
            val result = runCatching { scan() }
            val text = result.exceptionOrNull()?.let { generateSequence(it) { error -> error.cause }.joinToString("\n") { error -> error.message.orEmpty() } }
                ?: result.getOrThrow().joinToString("\n")
            check(fragment in text) { "Missing failure '$fragment': $text" }
        }
        fun alternate(): Path {
            val path = root.resolve(".git/alternate-empty.index")
            command(listOf("git", "read-tree", "--empty"), environment + ("GIT_INDEX_FILE" to path.toString()))
            environment["GIT_INDEX_FILE"] = path.toString()
            return path
        }
        fun globalAlias(source: String): Path {
            val p = write("real-target/build.gradle.kts", source); write(".gitignore", "/real-target/\n"); link("visible-adapter", "real-target")
            val excludes = write(".git/global-excludes", "visible-adapter\n"); val config = write(".git/global-config", "[core]\n\texcludesFile = $excludes\n")
            environment["GIT_CONFIG_GLOBAL"] = config.toString()
            return p
        }
        fun pivot(target: String) { write(".gitignore", "/hidden/\n/real-target/\n"); write("hidden/module/build.gradle.kts", EAGER); write("real-target/module/build.gradle.kts", ACTION); Files.createDirectories(root.resolve("hidden/deep")); link("real-target/pivot", target) }
        override fun close() {
            denied.forEach { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString(if (Files.isDirectory(it)) "rwx------" else "rw-------")) }
            Files.walk(ownedRoot).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    private fun hash(path: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
}
