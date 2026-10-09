#!/usr/bin/env python3
"""Real-file discovery contract; permission cases require an unprivileged UID."""

import os
import hashlib
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCANNER = Path(sys.argv.pop(1)).resolve() if len(sys.argv) > 1 and not sys.argv[1].startswith("-") else ROOT / "scripts/check_gradle_lazy_resolution.sh"
EAGER = 'val eager = configurations["runtimeClasspath"].asPath\n'
ACTION = 'doFirst { println(configurations["runtimeClasspath"].asPath) }\n'


class SourceDiscoveryTest(unittest.TestCase):
    def setUp(self):
        if os.geteuid() == 0:
            self.fail("Permission fixtures require a real unprivileged UID; refusing false root permission proof")
        self.temporary = tempfile.TemporaryDirectory(prefix="mazewall-source-discovery-")
        self.directory = Path(self.temporary.name)
        self.locked = []
        # Fixture Git operations must not inherit an outer committing index/repo.
        names = subprocess.check_output(["git", "rev-parse", "--local-env-vars"], text=True).splitlines()
        self.environment = os.environ.copy()
        for name in names:
            self.environment.pop(name, None)
        self.git("init", "--quiet")
        self.write("build.gradle.kts", ACTION)

    def tearDown(self):
        for path in self.locked:
            path.chmod(0o700 if path.is_dir() else 0o600)
        if hasattr(self, "temporary"):
            self.temporary.cleanup()

    def git(self, *arguments):
        return subprocess.run(["git", "-C", str(self.directory), *arguments], env=self.environment,
                              capture_output=True, text=True, check=True)

    def write(self, name, contents):
        path = self.directory / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(contents)
        return path

    def deny(self, path):
        path.chmod(0)
        self.locked.append(path)
        self.assertFalse(os.access(path, os.R_OK), "Permission fixture unexpectedly readable")

    def scan(self):
        return subprocess.run(["bash", str(SCANNER)], cwd=self.directory, env=self.environment,
                              capture_output=True, text=True)

    def assert_clean(self):
        result = self.scan()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("", result.stdout + result.stderr)

    def assert_eager(self, name):
        result = self.scan()
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertEqual(f"Eager Gradle configuration resolution: {name}:1: {EAGER.strip()}\n", result.stderr)
        self.assertEqual("", result.stdout)

    def test_unreachable_generated_directories_are_pruned(self):
        for directory in (".gradle/configuration-cache/private", "module/.gradle/private",
                          "build/private", "module/build/private", ".worktrees/private", ".git/private"):
            with self.subTest(directory=directory):
                path = self.write(f"{directory}/build.gradle.kts", EAGER).parent
                self.deny(path)
        self.assert_clean()

    def test_reachable_generated_scripts_are_not_source(self):
        for directory in (".gradle/configuration-cache", "module/.gradle", "build", "module/build", ".worktrees/other"):
            self.write(f"{directory}/build.gradle.kts", EAGER)
        self.assert_clean()

    def test_only_local_ignored_scratch_is_ignored(self):
        self.write(".gitignore", "/owned-scratch/\n")
        path = self.write("owned-scratch/private/build.gradle", EAGER).parent
        self.deny(path)
        self.assert_clean()
        self.write("integrations/new-adapter/build.gradle.kts", EAGER)
        self.assert_eager("integrations/new-adapter/build.gradle.kts")

    def test_normal_untracked_source_and_module_folders_are_checked(self):
        for name in ("integrations/framework/build.gradle", "tests/consumer/build.gradle.kts",
                     "tools/orchestrator/build.gradle.kts", "module with spaces/build.gradle.kts",
                     ".source-fixture/build.gradle.kts"):
            with self.subTest(name=name):
                path = self.write(name, EAGER)
                self.assert_eager(name)
                path.unlink()

    def test_tracked_source_is_checked_even_if_now_ignored(self):
        name = "tracked-source/build.gradle.kts"
        self.write(name, EAGER)
        self.git("add", "--", name)
        self.write(".gitignore", "/tracked-source/\n")
        self.assert_eager(name)

    def test_tracked_source_build_package_is_not_generated_output(self):
        name = "module/src/main/example/build/build.gradle.kts"
        self.write(name, EAGER)
        self.git("add", "--", name)
        self.assert_eager(name)

    def test_dot_ignore_cannot_hide_normal_source(self):
        self.write(".ignore", "integrations/\n")
        name = "integrations/new-adapter/build.gradle.kts"
        self.write(name, EAGER)
        self.assert_eager(name)

    def test_ripgrep_config_cannot_force_private_scratch_traversal(self):
        self.write(".gitignore", "/owned-scratch/\n")
        config = self.write("rg-config", "--no-ignore\n--hidden\n")
        self.environment["RIPGREP_CONFIG_PATH"] = str(config)
        self.deny(self.write("owned-scratch/private/build.gradle.kts", EAGER).parent)
        self.assert_clean()
        name = "tests/new-consumer/build.gradle.kts"
        self.write(name, EAGER)
        self.assert_eager(name)

    def test_foreign_git_context_is_not_trusted_as_cwd_inventory(self):
        with tempfile.TemporaryDirectory(prefix="mazewall-foreign-inventory-") as foreign:
            subprocess.run(["git", "-C", foreign, "init", "--quiet"], env=self.environment, check=True)
            self.environment["GIT_DIR"] = str(Path(foreign) / ".git")
            self.environment["GIT_WORK_TREE"] = foreign
            name = "tracked-source/build.gradle.kts"
            # Prepare in the real fixture repository, before injecting caller context.
            clean = self.environment.copy()
            clean.pop("GIT_DIR")
            clean.pop("GIT_WORK_TREE")
            self.write(name, EAGER)
            subprocess.run(["git", "-C", str(self.directory), "add", "--", name], env=clean, check=True)
            self.write(".gitignore", "/tracked-source/\n")
            self.assert_eager(name)

    def test_unreadable_untracked_source_directory_still_fails(self):
        path = self.write("integrations/private-source/build.gradle.kts", ACTION).parent
        self.deny(path)
        result = self.scan()
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Permission denied", result.stderr)
        self.assertIn("integrations/private-source", result.stderr)

    def test_unreadable_source_file_still_fails(self):
        path = self.write("tests/probe/build.gradle.kts", ACTION)
        self.deny(path)
        result = self.scan()
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Permission denied", result.stderr)
        self.assertIn("tests/probe/build.gradle.kts", result.stderr)

    def test_unreadable_tracked_ignored_source_still_fails(self):
        name = "tracked-source/build.gradle.kts"
        path = self.write(name, ACTION)
        self.git("add", "--", name)
        self.write(".gitignore", "/tracked-source/\n")
        self.deny(path)
        result = self.scan()
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Permission denied", result.stderr)
        self.assertIn(name, result.stderr)

    def alternate_empty_index(self):
        alternate = self.directory / ".git/alternate-empty.index"
        environment = self.environment.copy()
        environment["GIT_INDEX_FILE"] = str(alternate)
        subprocess.run(["git", "-C", str(self.directory), "read-tree", "--empty"],
                       env=environment, capture_output=True, check=True)
        self.environment["GIT_INDEX_FILE"] = str(alternate)
        return alternate

    def test_alternate_index_cannot_hide_ignored_tracked_eager_source(self):
        name = "tracked-source/build.gradle.kts"
        self.write(name, EAGER)
        self.git("add", "--", name)
        self.write(".gitignore", "/tracked-source/\n")
        alternate = self.alternate_empty_index()
        indexes = (self.directory / ".git/index", alternate)
        before = [hashlib.sha256(path.read_bytes()).hexdigest() for path in indexes]
        self.assert_eager(name)
        self.assertEqual(before, [hashlib.sha256(path.read_bytes()).hexdigest() for path in indexes])

    def test_alternate_index_cannot_hide_unreadable_ignored_tracked_source(self):
        name = "tracked-source/build.gradle.kts"
        path = self.write(name, ACTION)
        self.git("add", "--", name)
        self.write(".gitignore", "/tracked-source/\n")
        self.alternate_empty_index()
        self.deny(path)
        result = self.scan()
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Permission denied", result.stderr)
        self.assertIn(name, result.stderr)

    def assert_link_rejected(self, name):
        result = self.scan()
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("Unsupported source symlink:", result.stderr)
        self.assertIn(name, result.stderr)

    def test_contained_source_file_links_checked_tracked_and_untracked(self):
        for tracked in (False, True):
            with self.subTest(tracked=tracked):
                target = self.write("regular-target.txt", ACTION)
                name = "linked-source.gradle.kts"
                link = self.directory / name
                link.symlink_to(target)
                if tracked:
                    self.git("add", "--", name)
                try:
                    self.assert_clean()
                finally:
                    link.unlink()

    def test_contained_source_directory_link_checked(self):
        self.write("adapter-source/build.gradle.kts", ACTION)
        name = "integrations/local-adapter"
        link = self.directory / name
        link.parent.mkdir()
        link.symlink_to(self.directory / "adapter-source", target_is_directory=True)
        self.assert_clean()

    def test_dangling_source_link_rejected(self):
        name = "dangling.gradle.kts"
        (self.directory / name).symlink_to(self.directory / "absent")
        self.assert_link_rejected(name)

    def test_unreadable_source_link_target_rejected_without_reading_target(self):
        target = self.write("unreadable-target.txt", ACTION)
        self.deny(target)
        name = "unreadable-link.gradle.kts"
        (self.directory / name).symlink_to(target)
        self.assert_link_rejected(name)

    def test_source_link_to_private_state_rejected_without_traversal(self):
        target = self.write(".gradle/private/build.gradle.kts", ACTION).parent
        self.deny(target)
        name = "integrations/private-target"
        link = self.directory / name
        link.parent.mkdir()
        link.symlink_to(target, target_is_directory=True)
        self.assert_link_rejected(name)

    def test_source_link_to_external_target_rejected(self):
        with tempfile.TemporaryDirectory(prefix="mazewall-external-source-") as external:
            target = Path(external) / "build.gradle.kts"
            target.write_text(ACTION)
            name = "external.gradle.kts"
            (self.directory / name).symlink_to(target)
            self.assert_link_rejected(name)

    def test_indexed_source_ancestor_alias_checked_even_if_ignored(self):
        name = "tracked-source/build.gradle.kts"
        self.write(name, ACTION)
        self.git("add", "--", name)
        self.write(".gitignore", "/tracked-source/\n/real-target/\n")
        original = self.directory / "tracked-source"
        target = self.directory / "real-target"
        original.rename(target)
        original.symlink_to(target, target_is_directory=True)
        self.assert_clean()

    def test_indexed_ignored_directory_alias_checked(self):
        target = self.write("real-target/build.gradle.kts", ACTION).parent
        name = "linked-adapter"
        (self.directory / name).symlink_to(target, target_is_directory=True)
        self.git("add", "--", name)
        self.write(".gitignore", "/linked-adapter\n/real-target/\n")
        self.assert_clean()

    def test_actual_rootalias_shape_without_gradle_source_is_legitimate(self):
        target = self.write("profiler/hotspot-agentless/cmd/mw-gadget-build/main.go", "package main\n").parent
        link = self.directory / "experiments/hotspot-agentless/cmd/mw-gadget-build"
        link.parent.mkdir(parents=True)
        link.symlink_to("../../../profiler/hotspot-agentless/cmd/mw-gadget-build", target_is_directory=True)
        self.git("add", "--", str(link.relative_to(self.directory)))
        self.assertTrue(target.is_dir())
        self.assert_clean()

    def test_directory_alias_never_hides_eager_gradle_in_ignored_target(self):
        self.write("real-target/build.gradle.kts", EAGER)
        self.write(".gitignore", "/real-target/\n")
        link = self.directory / "visible-adapter"
        link.symlink_to(self.directory / "real-target", target_is_directory=True)
        self.assert_eager("real-target/build.gradle.kts")

    def test_alias_expands_symlink_before_parent_component(self):
        self.write(".gitignore", "/hidden/\n/real-target/\n")
        self.write("hidden/module/build.gradle.kts", EAGER)
        self.write("real-target/module/build.gradle.kts", ACTION)
        (self.directory / "hidden/deep").mkdir()
        (self.directory / "real-target/pivot").symlink_to(
            "../hidden/deep", target_is_directory=True
        )
        (self.directory / "adapter").symlink_to(
            "real-target/pivot/../module", target_is_directory=True
        )
        self.assert_eager("hidden/module/build.gradle.kts")

    def test_gradle_file_alias_uses_link_language_and_checks_target(self):
        target = self.write("target.txt", EAGER)
        (self.directory / "alias.gradle.kts").symlink_to(target)
        self.assert_eager("alias.gradle.kts")

    def test_alias_dot_and_absolute_targets_expand_before_parent(self):
        self.write(".gitignore", "/hidden/\n/real-target/\n")
        self.write("hidden/module/build.gradle.kts", EAGER)
        self.write("real-target/module/build.gradle.kts", ACTION)
        (self.directory / "hidden/deep").mkdir()
        (self.directory / "real-target/pivot").symlink_to(
            "../hidden/./deep", target_is_directory=True
        )
        link = self.directory / "adapter"
        for target in ("./real-target/./pivot/../module",
                       str(self.directory) + "/real-target/./pivot/../module"):
            with self.subTest(target=target):
                link.symlink_to(target, target_is_directory=True)
                try:
                    self.assert_eager("hidden/module/build.gradle.kts")
                finally:
                    link.unlink()

    def test_alias_absolute_nested_target_expands_before_parent(self):
        self.write(".gitignore", "/hidden/\n/real-target/\n")
        self.write("hidden/module/build.gradle.kts", EAGER)
        self.write("real-target/module/build.gradle.kts", ACTION)
        (self.directory / "hidden/deep").mkdir()
        (self.directory / "real-target/pivot").symlink_to(
            str(self.directory) + "/hidden/./deep", target_is_directory=True
        )
        (self.directory / "adapter").symlink_to(
            "real-target/pivot/../module", target_is_directory=True
        )
        self.assert_eager("hidden/module/build.gradle.kts")

    def test_alias_non_directory_before_parent_is_rejected(self):
        self.write(".gitignore", "/real-target/\n")
        self.write("real-target/file", "not a directory\n")
        self.write("real-target/module/build.gradle.kts", ACTION)
        (self.directory / "adapter").symlink_to(
            "real-target/file/../module", target_is_directory=True
        )
        self.assert_link_rejected("adapter")
        self.assertIn("Not a directory", self.scan().stderr)

    def test_alias_parent_cannot_hide_private_traversal(self):
        self.write("module/build.gradle.kts", ACTION)
        for private in (".git", ".gradle", ".worktrees", "build"):
            (self.directory / private).mkdir(exist_ok=True)
            link = self.directory / "adapter"
            for target in (f"{private}/../module",
                           str(self.directory) + f"/./{private}/../module"):
                with self.subTest(private=private, target=target):
                    link.symlink_to(target, target_is_directory=True)
                    try:
                        self.assert_link_rejected("adapter")
                        self.assertIn("private boundary", self.scan().stderr)
                    finally:
                        link.unlink()

    def test_alias_parent_cannot_hide_external_traversal(self):
        self.write("module/build.gradle.kts", ACTION)
        (self.directory / "adapter").symlink_to(
            "../" + self.directory.name + "/module", target_is_directory=True
        )
        self.assert_link_rejected("adapter")
        self.assertIn("leaves checkout", self.scan().stderr)

    def test_alias_parent_cannot_hide_nested_external_target(self):
        self.write(".gitignore", "/real-target/\n")
        self.write("real-target/module/build.gradle.kts", ACTION)
        with tempfile.TemporaryDirectory(prefix="mazewall-external-pivot-") as external:
            (self.directory / "real-target/pivot").symlink_to(
                external, target_is_directory=True
            )
            (self.directory / "adapter").symlink_to(
                "real-target/pivot/../module", target_is_directory=True
            )
            self.assert_link_rejected("adapter")
            self.assertIn("leaves checkout", self.scan().stderr)

    def test_alias_parent_cannot_hide_unreadable_intermediate_directory(self):
        self.write(".gitignore", "/real-target/\n")
        self.write("real-target/module/build.gradle.kts", ACTION)
        intermediate = self.directory / "real-target/private"
        intermediate.mkdir()
        self.deny(intermediate)
        (self.directory / "adapter").symlink_to(
            "real-target/private/../module", target_is_directory=True
        )
        self.assert_link_rejected("adapter")
        self.assertIn("Permission denied", self.scan().stderr)

    def test_repeated_aliases_do_not_skip_physical_source_coverage(self):
        self.write("real-target/build.gradle.kts", EAGER)
        self.write(".gitignore", "/real-target/\n")
        for name in ("first-adapter", "second-adapter"):
            (self.directory / name).symlink_to(self.directory / "real-target", target_is_directory=True)
        self.assert_eager("real-target/build.gradle.kts")

    def test_directory_alias_cycle_is_bounded_failure(self):
        target = self.write("real-target/main.go", "package main\n").parent
        (target / "back-to-root").symlink_to(self.directory, target_is_directory=True)
        (self.directory / "adapter").symlink_to(target, target_is_directory=True)
        result = self.scan()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("cycle", result.stderr.lower())

    def test_file_alias_cycle_is_bounded_failure(self):
        (self.directory / "first.gradle.kts").symlink_to("second.gradle.kts")
        (self.directory / "second.gradle.kts").symlink_to("first.gradle.kts")
        result = self.scan()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("cycle", result.stderr.lower())

    def test_directory_alias_unreadable_source_still_fails(self):
        target = self.write("real-target/build.gradle.kts", ACTION)
        self.write(".gitignore", "/real-target/\n")
        self.deny(target)
        (self.directory / "adapter").symlink_to(target.parent, target_is_directory=True)
        result = self.scan()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Permission denied", result.stderr)
        self.assertIn("build.gradle.kts", result.stderr)

    def test_alias_cannot_cross_into_generated_build_target(self):
        target = self.write("build/generated/build.gradle.kts", ACTION).parent
        (self.directory / "adapter").symlink_to(target, target_is_directory=True)
        self.assert_link_rejected("adapter")

    def test_alias_graph_cycle_between_repeated_physical_directories_fails(self):
        first = self.write("first/main.go", "package main\n").parent
        second = self.write("second/main.go", "package main\n").parent
        (first / "second").symlink_to(second, target_is_directory=True)
        (second / "first").symlink_to(first, target_is_directory=True)
        result = self.scan()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("directory cycle", result.stderr.lower())

    def test_gradle_file_alias_retains_groovy_literal_language(self):
        target = self.write("target.txt", "def pattern = /literal .asPath/\n")
        (self.directory / "alias.gradle").symlink_to(target)
        self.assert_clean()

    def test_indexed_alias_suffix_cannot_enter_generated_target(self):
        name = "tracked-adapter/build/private/build.gradle.kts"
        self.write(name, ACTION)
        self.git("add", "--", name)
        original = self.directory / "tracked-adapter"
        physical = self.directory / "physical-adapter"
        original.rename(physical)
        original.symlink_to(physical, target_is_directory=True)
        self.assert_link_rejected("tracked-adapter")

    def test_indexed_alias_under_generated_ancestor_is_rejected(self):
        name = "build/alias.gradle.kts"
        self.write("build/target.txt", ACTION)
        (self.directory / name).symlink_to("target.txt")
        self.git("add", "--", name)
        self.assert_link_rejected(name)

    def test_indexed_regular_source_under_build_package_remains_covered(self):
        name = "app/build/build.gradle.kts"
        self.write(name, EAGER)
        self.git("add", "--", name)
        self.assert_eager(name)

    def test_file_alias_targets_ending_in_slash_are_rejected(self):
        target = self.write("regular-target.txt", ACTION)
        for name, raw_target in (
            ("relative-slash.gradle.kts", "regular-target.txt/"),
            ("absolute-slash.gradle.kts", str(target) + "/"),
        ):
            with self.subTest(target=raw_target):
                (self.directory / name).symlink_to(raw_target)
                try:
                    self.assert_link_rejected(name)
                    self.assertIn("Not a directory", self.scan().stderr)
                finally:
                    (self.directory / name).unlink()

    def test_directory_alias_target_ending_in_slash_is_valid(self):
        self.write("real-target/build.gradle.kts", ACTION)
        (self.directory / "directory-slash.gradle.kts").symlink_to("real-target/")
        self.assert_clean()

    def test_indexed_source_suffix_after_directory_alias_target_ending_in_slash(self):
        name = "adapter/build.gradle.kts"
        self.write(name, EAGER)
        self.git("add", "--", name)
        (self.directory / "adapter").rename(self.directory / "real-target")
        (self.directory / "adapter").symlink_to("real-target/")
        self.write(".gitignore", "/real-target/\n")
        self.assertEqual(EAGER, (self.directory / name).read_text())
        result = self.scan()
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertEqual("".join(
            f"Eager Gradle configuration resolution: {source}:1: {EAGER.strip()}\n"
            for source in (name, "real-target/build.gradle.kts")
        ), result.stderr)
        self.assertEqual("", result.stdout)

    def test_file_alias_can_expand_through_directory_alias_target_ending_in_slash(self):
        self.write("real-target/source.gradle.kts", EAGER)
        (self.directory / "adapter").symlink_to("real-target/")
        (self.directory / "alias.gradle.kts").symlink_to("adapter/source.gradle.kts")
        self.git("add", "--", "alias.gradle.kts")
        self.write(".gitignore", "/real-target/\n/adapter\n")
        self.assertEqual(EAGER, (self.directory / "alias.gradle.kts").read_text())
        result = self.scan()
        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn(f"Eager Gradle configuration resolution: alias.gradle.kts:1: {EAGER.strip()}\n", result.stderr)
        self.assertNotIn("failed:", result.stderr)
        self.assertEqual("", result.stdout)

    def test_alias_target_directory_unreadable_still_fails(self):
        target = self.write("real-target/build.gradle.kts", ACTION).parent
        self.write(".gitignore", "/real-target/\n")
        self.deny(target)
        (self.directory / "adapter").symlink_to(target, target_is_directory=True)
        self.assert_link_rejected("adapter")

    def test_alias_unsupported_target_type_fails_without_opening_it(self):
        target = self.directory / "owned-pipe"
        os.mkfifo(target)
        (self.directory / "alias.gradle.kts").symlink_to(target)
        self.assert_link_rejected("alias.gradle.kts")

    def test_linked_worktree_uses_its_default_index(self):
        repository = self.directory
        linked = repository / "owned-scratch/linked"
        self.git("worktree", "add", "--orphan", "-b", "fixture-source", str(linked))
        self.write(".gitignore", "/owned-scratch/\n")
        self.directory = linked
        try:
            self.assertTrue((linked / ".git").is_file())
            name = "tracked-source/build.gradle.kts"
            self.write(name, EAGER)
            self.git("add", "--", name)
            self.write(".gitignore", "/tracked-source/\n")
            alternate = repository / ".git/alternate-empty.index"
            environment = self.environment.copy()
            environment["GIT_INDEX_FILE"] = str(alternate)
            subprocess.run(["git", "-C", str(linked), "read-tree", "--empty"], env=environment, check=True)
            self.environment["GIT_INDEX_FILE"] = str(alternate)
            self.assert_eager(name)
        finally:
            self.directory = repository


if __name__ == "__main__":
    unittest.main(verbosity=2)
