#!/usr/bin/env python3
import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest

spec = importlib.util.spec_from_file_location("benchmark_runner", Path(__file__).with_name("run-benchmark.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class LossControlTest(unittest.TestCase):
    def test_fault_control_requires_exact_missing_token(self):
        args = SimpleNamespace(expect_missing=1, expect_missing_sequence=42)
        report = {"unobserved_marked_operations": 1, "missing_sequences": [43], "correct_application_segments": 10}
        with self.assertRaisesRegex(RuntimeError, "exact missing token"):
            runner.validate_report(args, "full", report)
        report["missing_sequences"] = [42]
        runner.validate_report(args, "full", report)


if __name__ == "__main__":
    unittest.main()
