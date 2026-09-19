import json
import pathlib
import tempfile
import unittest

from analyze_tier_e_perf_windows import analyze


class PerfWindowTest(unittest.TestCase):
    def test_filters_half_open_windows_and_keeps_process_roles_separate(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            output = ('collector_pid=20 workload_pid=10\n'
                      'tier-e-perf-window: pid=10 phase=measured index=1 '
                      'start_ns=1000000000 end_ns=2000000000 clock=monotonic')
            (root / 'results.jsonl').write_text(json.dumps(dict(
                kind='fork-output', variant='test', fork=1, output=output)))
            (root / 'perf-timed-samples.txt').write_text(
                'java 10/11 0.999999999: cycles:u:\n\t aa outside\n'
                'java 10/11 1.000000000: cycles:u:\n\t ab leaf\n\t ac caller\n'
                'java 20/21 1.500000000: cycles:u:\n\t ad collector\n'
                'java 10/11 2.000000000: cycles:u:\n\t ae outside\n')
            processes = analyze(root)['processes']
            self.assertEqual([1, 1], [p['measured_samples'] for p in processes])
            self.assertEqual([2, 0], [p['outside_samples'] for p in processes])
            self.assertEqual([('ab leaf', 1)], processes[0]['top_leaves'])

    def test_missing_windows_rejects_attribution(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / 'results.jsonl').write_text(json.dumps(dict(
                kind='fork-output', variant='test', fork=1, output='')))
            with self.assertRaisesRegex(ValueError, 'Missing measured windows'):
                analyze(root)


if __name__ == '__main__':
    unittest.main()
