#!/usr/bin/env python3
"""Executable contract tests for the kernel UAPI watcher."""

import os
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
CHECK = ROOT / "scripts/check_kernel_uapi.sh"
HEADERS = ("seccomp.h", "landlock.h", "io_uring.h", "audit.h", "bpf_common.h")


class KernelUapiCheckTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.snapshot = root / "snapshot"
        self.headers = root / "headers"
        for directory in (self.snapshot, self.headers):
            directory.mkdir()
            for header in HEADERS:
                (directory / header).write_text(f"#define {header.replace('.', '_').upper()} 1\n")
        self.tools = root / "tools"
        self.tools.mkdir()
        self._tool("sudo", "exec \"$@\"")
        self._tool(
            "apt-get",
            '[[ "${1:-}" == "${FAKE_APT_FAIL_ON:-never}" ]] && exit "${FAKE_APT_EXIT:-23}"\n'
            "exit 0",
        )
        self._tool(
            "dpkg-query",
            '[[ -n "${FAKE_QUERY_EXIT:-}" ]] && exit "$FAKE_QUERY_EXIT"\n'
            "printf '%s\\n' \"${FAKE_VERSION:-7.0.0-42.42}\"",
        )
        self._tool(
            "dpkg",
            'if [[ -n "${FAKE_DPKG_EXIT:-}" ]]; then exit "$FAKE_DPKG_EXIT"; fi\n'
            '[[ "${2:-}" == "6.8.0-147.147" ]] && exit 1\n'
            "exit 0",
        )

    def tearDown(self):
        self.temp.cleanup()

    def _tool(self, name, body):
        path = self.tools / name
        path.write_text(f"#!/usr/bin/env bash\nset -eu\n{body}\n")
        path.chmod(path.stat().st_mode | stat.S_IXUSR)

    def run_check(self, **environment):
        output = Path(self.temp.name) / "output"
        env = os.environ | {
            "SUDO_BIN": str(self.tools / "sudo"),
            "APT_GET_BIN": str(self.tools / "apt-get"),
            "DPKG_QUERY_BIN": str(self.tools / "dpkg-query"),
            "DPKG_BIN": str(self.tools / "dpkg"),
        } | environment
        result = subprocess.run(
            [
                "bash", str(CHECK), "--install", "--snapshot-dir", str(self.snapshot),
                "--headers-dir", str(self.headers), "--output-dir", str(output),
            ],
            env=env,
            capture_output=True,
            text=True,
        )
        return result, output

    def test_rejects_old_package_as_unqualified_input(self):
        result, output = self.run_check(FAKE_VERSION="6.8.0-147.147")
        self.assertEqual(2, result.returncode)
        self.assertEqual("kind=input-error\n", (output / "kind").read_text())
        self.assertIn("older than reviewed baseline", (output / "report.txt").read_text())

    def test_accepts_exact_reviewed_headers(self):
        result, output = self.run_check()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("kind=clean\n", (output / "kind").read_text())
        self.assertEqual("7.0.0-42.42\n", (output / "package-version").read_text())

    def test_retains_named_diff_for_newer_changed_header(self):
        (self.headers / "io_uring.h").write_text("#define IO_URING_H 2\n")
        result, output = self.run_check(FAKE_VERSION="7.0.0-43.43")
        self.assertEqual(1, result.returncode)
        self.assertEqual("kind=drift\n", (output / "kind").read_text())
        self.assertIn("io_uring.h", (output / "changed").read_text())
        self.assertTrue((output / "io_uring.h.diff").read_text())

    def test_rejects_missing_header(self):
        (self.headers / "audit.h").unlink()
        result, output = self.run_check()
        self.assertEqual(2, result.returncode)
        self.assertIn("missing or unreadable current header", (output / "report.txt").read_text())

    def test_rejects_unreadable_header(self):
        header = self.headers / "landlock.h"
        header.chmod(0)
        try:
            result, output = self.run_check()
        finally:
            header.chmod(stat.S_IRUSR | stat.S_IWUSR)
        self.assertEqual(2, result.returncode)
        self.assertIn("missing or unreadable current header", (output / "report.txt").read_text())

    def test_rejects_diff_execution_error(self):
        self._tool("bad-diff", "exit 2")
        result, output = self.run_check(DIFF_BIN=str(self.tools / "bad-diff"))
        self.assertEqual(2, result.returncode)
        self.assertIn("diff failed", (output / "report.txt").read_text())

    def test_rejects_version_comparison_error(self):
        result, output = self.run_check(FAKE_DPKG_EXIT="2")
        self.assertEqual(2, result.returncode)
        self.assertIn("cannot compare", (output / "report.txt").read_text())

    def test_retains_preexisting_package_version_when_update_fails(self):
        result, output = self.run_check(FAKE_APT_FAIL_ON="update")
        self.assertEqual(2, result.returncode)
        self.assertIn("update failed", (output / "report.txt").read_text())
        self.assertEqual("7.0.0-42.42\n", (output / "package-version").read_text())
        self.assertEqual("preexisting\n", (output / "package-version-source").read_text())
        self.assertEqual("kind=input-error\n", (output / "kind").read_text())

    def test_retains_preexisting_package_version_when_install_fails(self):
        result, output = self.run_check(FAKE_APT_FAIL_ON="install")
        self.assertEqual(2, result.returncode)
        self.assertIn("header installation failed", (output / "report.txt").read_text())
        self.assertEqual("7.0.0-42.42\n", (output / "package-version").read_text())
        self.assertEqual("preexisting\n", (output / "package-version-source").read_text())
        self.assertEqual("kind=input-error\n", (output / "kind").read_text())

    def test_retains_unknown_state_when_update_and_package_query_fail(self):
        result, output = self.run_check(FAKE_APT_FAIL_ON="update", FAKE_QUERY_EXIT="5")
        self.assertEqual(2, result.returncode)
        report = (output / "report.txt").read_text()
        self.assertIn("update failed", report)
        self.assertIn("preexisting package version unavailable", report)
        self.assertEqual("unknown\n", (output / "package-version").read_text())
        self.assertEqual("unavailable\n", (output / "package-version-source").read_text())
        self.assertEqual("kind=input-error\n", (output / "kind").read_text())

    def test_retains_unknown_state_when_package_query_fails_after_install_failure(self):
        result, output = self.run_check(FAKE_APT_FAIL_ON="install", FAKE_QUERY_EXIT="5")
        self.assertEqual(2, result.returncode)
        report = (output / "report.txt").read_text()
        self.assertIn("header installation failed", report)
        self.assertIn("preexisting package version unavailable", report)
        self.assertEqual("unknown\n", (output / "package-version").read_text())
        self.assertEqual("unavailable\n", (output / "package-version-source").read_text())
        self.assertEqual("kind=input-error\n", (output / "kind").read_text())


if __name__ == "__main__":
    unittest.main()
