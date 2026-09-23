import unittest
from scripts.probe_tier_e_real_pid_reuse import starttime


class RealPidReuseProbeTest(unittest.TestCase):
    def test_proc_stat_extracts_starttime_after_parenthesized_comm(self):
        stat = "42 (worker name with spaces) S " + " ".join(str(n) for n in range(1, 19)) + " 9876 21"
        self.assertEqual(9876, starttime(stat))
        with self.assertRaises(ValueError):
            starttime("42 broken")


if __name__ == "__main__":
    unittest.main()
