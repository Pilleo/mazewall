import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from scripts.probe_tier_e_real_pid_reuse import run_for_identity, starttime


class RealPidReuseProbeTest(unittest.TestCase):
    def test_proc_stat_extracts_starttime_after_parenthesized_comm(self):
        stat = "42 (worker name with spaces) S " + " ".join(str(n) for n in range(1, 19)) + " 9876 21"
        self.assertEqual(9876, starttime(stat))
        with self.assertRaises(ValueError):
            starttime("42 broken")

    def test_registered_identity_probe_reports_unverified_without_replacement(self):
        with TemporaryDirectory() as directory:
            output = Path(directory) / "probe.json"
            result = run_for_identity({"pid": 99999999, "starttime": 123}, 2, 5, output, 0)
            self.assertEqual({"pid": 99999999, "starttime": 123}, result["original"])
            self.assertEqual("UNVERIFIED", result["host_pid_reuse_verdict"])
            self.assertEqual(2, result["attempts"])
            self.assertIsNone(result["replacement"])
            self.assertFalse(result["policy_eligibility"])
            self.assertEqual(result, json.loads(output.read_text()))


if __name__ == "__main__":
    unittest.main()
