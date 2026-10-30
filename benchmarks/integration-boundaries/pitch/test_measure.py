import unittest
import measure
import json
import io
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
from contextlib import contextmanager, redirect_stderr
from unittest.mock import patch

TEMP_PARENT = '/tmp/opencode' if Path('/tmp/opencode').is_dir() else None


def fixture(mode="reader-thread", fork=0):
    row = {"schema": 1, "mode": mode, "pid": 100 + fork, "warmups": 20,
            "java": "synthetic", "os": "synthetic", "arch": "synthetic",
            "samples": list(range(1, 101)), "medianNs": 50, "p95Ns": 95,
            "setupNs": 1, "drainNs": 1, "retirement": "physical",
            "resourcesBefore": {"pid": 100 + fork, "rssKiB": 1, "threads": 1},
            "resourcesAfter": {"pid": 100 + fork, "rssKiB": 1, "threads": 1},
            "pressure": {"accepted": 4, "rejected": 1, "rejectionNs": 1},
            "artifacts": {"synthetic-unit-fixture.jar": "a" * 64}, "scope": "synthetic validator fixture only"}
    if mode.endswith("direct"):
        row.update(retirement="not-applicable", pressure=None)
    if mode == "outline-portal":
        row["childResources"] = {"pid": 900 + fork, "rssKiB": 1, "threads": 1}
    return row


def inventory():
    return [fixture(mode, index * 3 + fork) for index, mode in enumerate(sorted(measure.MODES - {"spring-http"})) for fork in range(3)]


class MeasurementsTest(unittest.TestCase):
    def test_portal_child_requires_actual_identity_threads_and_rss(self):
        for field in ("pid", "threads", "rssKiB"):
            rows = inventory()
            next(row for row in rows if row["mode"] == "outline-portal")["childResources"].pop(field)
            with self.assertRaises(ValueError):
                measure.validate(rows)

    def test_duplicate_fields_cannot_override_evidence(self):
        with self.assertRaises(ValueError):
            measure.decode('{"pid":1,"pid":2}')

    def test_exact_three_forks_are_validated_without_pooling_pressure(self):
        measure.validate(inventory())

    def test_corruption_and_missing_physical_evidence_reject(self):
        for change in [lambda row: row["samples"].pop(),
                       lambda row: row["samples"].__setitem__(0, 0),
                       lambda row: row.update(medianNs=999),
                       lambda row: row.update(p95Ns=999),
                       lambda row: row.update(warmups=0),
                       lambda row: row.update(retirement="cancelled"),
                       lambda row: row.update(pressure=None),
                       lambda row: row.update(artifacts={}),
                       lambda row: row.pop("java"),
                       lambda row: row["resourcesAfter"].update(threads=0)]:
            rows = inventory()
            change(next(row for row in rows if row["mode"] == "reader-thread"))
            with self.assertRaises(ValueError):
                measure.validate(rows)
        with self.assertRaises(ValueError):
            measure.validate([fixture()])
        with self.assertRaises(ValueError):
            measure.validate([fixture()] * 3)


class ProcessOwnershipTest(unittest.TestCase):
    def test_owned_stdout_remains_json_and_stderr_is_retained_separately(self):
        diagnostic = io.StringIO()
        with redirect_stderr(diagnostic):
            output = measure.owned([sys.executable, '-c',
                                    'import sys; print("diagnostic from launcher", file=sys.stderr); print(\'{"measured":true}\')'])
        self.assertEqual({'measured': True}, measure.decode(output))
        self.assertEqual('diagnostic from launcher\n', diagnostic.getvalue())

    def test_failed_owned_command_keeps_stderr_in_failure_report(self):
        with self.assertRaisesRegex(ValueError, 'actual child failure'):
            measure.owned([sys.executable, '-c', 'import sys; print("actual child failure", file=sys.stderr); sys.exit(9)'])

    def test_separate_streams_still_share_the_existing_combined_log_bound(self):
        with self.assertRaisesRegex(ValueError, 'exceeds2MiB'):
            measure.owned([sys.executable, '-c', 'import sys; sys.stdout.write("x"*1048576); sys.stderr.write("y"*1048577)'])

    CHILD = """
import json, os, signal, sys, time
from pathlib import Path
signal.signal(signal.SIGTERM, signal.SIG_IGN)
fields = Path('/proc/self/stat').read_text().rsplit(')', 1)[1].split()
marker = Path(sys.argv[1])
temporary = marker.with_suffix('.new')
temporary.write_text(json.dumps({'pid': os.getpid(), 'start': fields[19]}))
temporary.rename(marker)
while True: time.sleep(1)
"""
    PARENT = """
import subprocess, sys, time
from pathlib import Path
subprocess.Popen([sys.executable, '-c', sys.argv[2], sys.argv[1]], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
deadline = time.monotonic() + 5
while not Path(sys.argv[1]).exists():
    if time.monotonic() >= deadline: raise RuntimeError('Child did not enter')
    time.sleep(.01)
if sys.argv[3] == 'held':
    while True: time.sleep(1)
"""

    def child_alive(self, marker):
        child = json.loads(marker.read_text())
        try:
            fields = Path('/proc', str(child['pid']), 'stat').read_text().rsplit(')', 1)[1].split()
            return fields[19] == child['start'] and fields[0] not in {'Z', 'X'}
        except (FileNotFoundError, ProcessLookupError):
            return False

    def assert_child_ends(self, marker):
        deadline = time.monotonic() + 5
        while self.child_alive(marker) and time.monotonic() < deadline:
            time.sleep(.01)
        self.assertFalse(self.child_alive(marker), 'Owned child did not physically end after group KILL')

    def release_child(self, marker):
        if not marker.exists() or not self.child_alive(marker):
            return
        child = json.loads(marker.read_text())
        try:
            fd = os.pidfd_open(child['pid'])
        except ProcessLookupError:
            return
        try:
            try:
                fields = Path('/proc', str(child['pid']), 'stat').read_text().rsplit(')', 1)[1].split()
            except (FileNotFoundError, ProcessLookupError):
                return
            if fields[19] != child['start']:
                raise AssertionError('Child identity changed; refusing unowned signal')
            signal.pidfd_send_signal(fd, signal.SIGKILL)
        finally:
            os.close(fd)

    @contextmanager
    def held_process(self, marker):
        process = subprocess.Popen([sys.executable, '-c', self.PARENT, str(marker), self.CHILD, 'held'], start_new_session=True)
        try:
            deadline = time.monotonic() + 5
            while not marker.exists() and time.monotonic() < deadline:
                time.sleep(.01)
            self.assertTrue(marker.exists())
            yield process
        finally:
            self.release_child(marker)
            if process.poll() is None:
                process.kill()
            process.wait(timeout=5)

    def test_exited_parent_does_not_abandon_held_child(self):
        with tempfile.TemporaryDirectory(dir=TEMP_PARENT) as temporary:
            marker = Path(temporary, 'child.json')
            try:
                try:
                    measure.owned([sys.executable, '-c', self.PARENT, str(marker), self.CHILD, 'exit'])
                except ValueError:
                    pass  # An exited parent with a live child is a failed measurement, even after cleanup.
                self.assertFalse(self.child_alive(marker), 'Held child survived exited parent cleanup')
            finally:
                self.release_child(marker)

    def test_parent_exit_on_sigterm_does_not_skip_child_escalation(self):
        with tempfile.TemporaryDirectory(dir=TEMP_PARENT) as temporary:
            marker = Path(temporary, 'child.json')
            with self.held_process(marker) as process:
                measure.retire(process)
                self.assertFalse(self.child_alive(marker), 'Child ignored SIGTERM after parent exited')

    def test_initial_inspection_failure_still_stops_owned_group(self):
        with tempfile.TemporaryDirectory(dir=TEMP_PARENT) as temporary:
            marker = Path(temporary, 'child.json')
            with self.held_process(marker) as process:
                failure = PermissionError(13, 'initial proc inspection denied')
                with patch.object(measure, 'live_group', side_effect=failure):
                    with self.assertRaises(PermissionError) as caught:
                        measure.retire(process)
                self.assertIs(caught.exception, failure)
                self.assertIsNotNone(process.returncode, 'Owned leader was not reaped')
                self.assert_child_ends(marker)

    def test_later_inspection_failure_still_escalates_and_reaps(self):
        with tempfile.TemporaryDirectory(dir=TEMP_PARENT) as temporary:
            marker = Path(temporary, 'child.json')
            with self.held_process(marker) as process:
                failure = PermissionError(13, 'later proc inspection denied')
                scan = measure.live_group
                calls = 0
                def inspect(leader):
                    nonlocal calls
                    calls += 1
                    if calls == 1:
                        return scan(leader)
                    raise failure
                with patch.object(measure, 'live_group', side_effect=inspect):
                    with self.assertRaises(PermissionError) as caught:
                        measure.retire(process)
                self.assertIs(caught.exception, failure)
                self.assertIsNotNone(process.returncode, 'Owned leader was not reaped')
                self.assert_child_ends(marker)

    def test_reaped_anchor_never_authorizes_a_group_signal(self):
        process = subprocess.Popen([sys.executable, '-c', 'pass'], start_new_session=True)
        process.wait(timeout=5)
        with patch.object(measure.os, 'killpg') as signal_group:
            with self.assertRaises(ChildProcessError):
                measure.retire(process)
            signal_group.assert_not_called()

    def test_emergency_signal_failure_preserves_inspection_cause(self):
        with tempfile.TemporaryDirectory(dir=TEMP_PARENT) as temporary:
            marker = Path(temporary, 'child.json')
            with self.held_process(marker) as process:
                inspection = PermissionError(13, 'proc inspection denied')
                denied = PermissionError(1, 'group signal denied')
                with patch.object(measure, 'live_group', side_effect=inspection), patch.object(measure.os, 'killpg', side_effect=denied):
                    with self.assertRaises(PermissionError) as caught:
                        measure.retire(process)
                self.assertIs(caught.exception, denied)
                self.assertIs(caught.exception.__cause__, inspection)


if __name__ == "__main__":
    unittest.main()
