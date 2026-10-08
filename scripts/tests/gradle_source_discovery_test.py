#!/usr/bin/env python3
"""Real-file discovery contract; permission cases require an unprivileged UID."""

import os
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
            result = self.scan()
            self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("Git context is not the caller's checkout", result.stderr)

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


if __name__ == "__main__":
    unittest.main(verbosity=2)
