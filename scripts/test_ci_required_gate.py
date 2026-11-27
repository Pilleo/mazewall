#!/usr/bin/env python3
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Run with python3 scripts/test_ci_required_gate.py; no external dependencies."""

import subprocess
import sys
import unittest
from pathlib import Path
from typing import Final

LANES: Final = (
    "core-quality", "mutation-tests", "security-check", "build-logic-validation",
    "go-validation", "optional-integrations", "confined-helper-kernel",
)
GATE: Final = Path(__file__).with_name("ci_required_gate.py")


class RequiredGateTest(unittest.TestCase):
    def test_each_required_lane_reports_every_result_and_fails_closed(self) -> None:
        for lane in LANES:
            for result in ("success", "failure", "cancelled", "skipped", "", "unknown", "failure\n::warning::injected"):
                with self.subTest(lane=lane, result=result):
                    command = [sys.executable, str(GATE)]
                    for name in LANES:
                        command.extend((f"--{name}", result if name == lane else "success"))
                    command.extend(("--go-variable", "true", "--go-input", ""))
                    completed = subprocess.run(command, capture_output=True, text=True, check=False)
                    self.assertEqual(completed.returncode, 0 if result == "success" else 1, completed.stderr)
                    self.assertEqual(len(completed.stdout.splitlines()), len(LANES))
                    display = "unknown" if "\n" in result else result or "missing"
                    self.assertIn(f"{lane}: result={display} ", completed.stdout)
                    self.assertIn("outcome=required-success" if result == "success" else "outcome=rejected", completed.stdout)

    def test_go_skip_is_accepted_only_when_both_flags_are_disabled(self) -> None:
        for variable in ("", "false", "true"):
            for dispatch in ("", "false", "true"):
                for result in ("success", "failure", "cancelled", "skipped", "", "unknown"):
                    with self.subTest(variable=variable, dispatch=dispatch, result=result):
                        command = [sys.executable, str(GATE)]
                        for lane in LANES:
                            command.extend((f"--{lane}", result if lane == "go-validation" else "success"))
                        command.extend(("--go-variable", variable, "--go-input", dispatch))
                        completed = subprocess.run(command, capture_output=True, text=True, check=False)
                        allowed_skip = result == "skipped" and variable != "true" and dispatch != "true"
                        self.assertEqual(completed.returncode, 0 if result == "success" or allowed_skip else 1, completed.stderr)
                        if allowed_skip:
                            self.assertIn("go-validation: result=skipped outcome=optional-skip", completed.stdout)

    def test_missing_argument_cannot_pass(self) -> None:
        completed = subprocess.run([sys.executable, str(GATE)], capture_output=True, text=True, check=False)
        self.assertNotEqual(completed.returncode, 0)


if __name__ == "__main__":
    unittest.main()
