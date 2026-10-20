import json
import os
from pathlib import Path
import select
import signal
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from scripts import ci_resource_probe as probe


GIB = 1073741824
REPO = Path(__file__).resolve().parents[2]


class LinuxFixtureTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="ci-resource-probe-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.proc = self.root / "proc"
        (self.proc / "self").mkdir(parents=True)
        self.cgroup = self.root / "cgroup mount"
        self.cgroup.mkdir()
        self.group = self.cgroup / "parent" / "job"
        self.group.mkdir(parents=True)
        self.mem_available(7 * GIB)
        (self.proc / "self/cgroup").write_text("0::/parent/job\n")
        mount = str(self.cgroup).replace(" ", "\\040")
        (self.proc / "self/mountinfo").write_text(
            f"40 25 0:29 / {mount} rw - cgroup2 cgroup rw\n"
        )
        self.counters(self.group, limit=4 * GIB, current=GIB, peak=GIB)
        self.counters(self.group.parent)

    def mem_available(self, amount):
        (self.proc / "meminfo").write_text(f"MemAvailable: {amount // 1024} kB\n")

    def counters(self, directory, limit="max", current=GIB, peak=GIB, oom=0):
        for name, value in {
            "memory.max": limit,
            "memory.current": current,
            "memory.peak": peak,
            "memory.events": f"low 0\nhigh 0\nmax 0\noom 0\noom_kill {oom}\n",
        }.items():
            (directory / name).write_text(f"{value}\n")

    def collect(self):
        with patch.object(probe, "_PROC", self.proc):
            return probe.collect_sample()


class CollectorTest(LinuxFixtureTest):
    def test_cgroup_budget_wins_over_seven_gib_host(self):
        sample = self.collect()
        self.assertEqual(sample.headroom_bytes, 3 * GIB)
        self.assertEqual(sample.peak_headroom_bytes, 3 * GIB)
        self.assertEqual(sample.oom_kills, 0)
        self.assertEqual(probe.evaluate_samples([sample]), probe.MemoryVerdict.PASS)

    def test_constrained_ancestor_wins_over_leaf(self):
        self.counters(self.group.parent, limit=5 * GIB, current=3 * GIB, peak=4 * GIB)
        sample = self.collect()
        self.assertEqual(sample.headroom_bytes, 2 * GIB)
        self.assertEqual(sample.peak_headroom_bytes, GIB)
        self.assertEqual(probe.evaluate_samples([sample]), probe.MemoryVerdict.UNSAFE)

    def test_unlimited_leaf_keeps_real_ancestor_and_host_bounds(self):
        (self.group / "memory.max").write_text("max\n")
        self.counters(self.group.parent, limit=5 * GIB, current=GIB, peak=GIB)
        self.assertEqual(self.collect().headroom_bytes, 4 * GIB)
        (self.group.parent / "memory.max").write_text("max\n")
        self.assertEqual(self.collect().headroom_bytes, 7 * GIB)

    def test_root_without_memory_controller_is_not_a_missing_limit(self):
        sample = self.collect()
        root = sample._metadata["cgroups"][-1]
        self.assertEqual(root["path"], str(self.cgroup))
        self.assertEqual(root["status"], "not_applicable")
        self.assertIsNone(root["counters"]["memory.max"]["raw"])
        self.assertEqual(probe.evaluate_samples([sample]), probe.MemoryVerdict.PASS)

    def test_partially_present_root_controller_is_unknown(self):
        (self.cgroup / "memory.max").write_text("max\n")
        self.assertEqual(probe.evaluate_samples([self.collect()]), probe.MemoryVerdict.UNKNOWN)

    def test_missing_non_root_controller_is_not_treated_as_unlimited(self):
        for name in ("memory.max", "memory.current", "memory.peak", "memory.events"):
            (self.group.parent / name).unlink()
        self.assertEqual(probe.evaluate_samples([self.collect()]), probe.MemoryVerdict.UNKNOWN)

    def test_missing_or_malformed_controller_counters_are_unknown(self):
        for name in ("memory.max", "memory.current", "memory.peak", "memory.events"):
            path = self.group.parent / name
            original = path.read_text()
            for value in (None, "not-a-counter\n", "-1\n"):
                with self.subTest(counter=name, value=value):
                    if value is None:
                        path.unlink()
                    else:
                        path.write_text(value)
                    sample = self.collect()
                    self.assertEqual(probe.evaluate_samples([sample]), probe.MemoryVerdict.UNKNOWN)
                    raw = sample._metadata["cgroups"][1]["counters"][name]
                    self.assertEqual(raw["raw"], value)
                    path.write_text(original)

    def test_missing_host_bound_is_unknown(self):
        for value in (
            "MemFree: 999999 kB\n",
            "MemAvailable: broken kB\n",
            "MemAvailable:\n7340032 kB\n",
        ):
            with self.subTest(value=value):
                (self.proc / "meminfo").write_text(value)
                self.assertIsNone(self.collect().headroom_bytes)
                self.assertEqual(probe.evaluate_samples([self.collect()]), probe.MemoryVerdict.UNKNOWN)

    def test_oom_counter_uses_outermost_ancestor_without_double_counting(self):
        self.counters(self.group, oom=3)
        self.counters(self.group.parent, oom=7)
        self.assertEqual(self.collect().oom_kills, 7)

    def test_mount_root_maps_membership_without_guessing_sys_fs_cgroup(self):
        mount = str(self.cgroup).replace(" ", "\\040")
        (self.proc / "self/mountinfo").write_text(
            f"40 25 0:29 /parent {mount} rw - cgroup2 cgroup rw\n"
        )
        self.counters(self.cgroup)
        (self.cgroup / "job").mkdir()
        self.counters(self.cgroup / "job", limit=4 * GIB)
        sample = self.collect()
        self.assertEqual(sample.headroom_bytes, 3 * GIB)
        self.assertEqual(sample._metadata["cgroups"][0]["path"], str(self.cgroup / "job"))

    def test_broad_mount_is_used_even_when_a_narrow_bind_mount_is_first(self):
        narrow = self.root / "narrow"
        (narrow / "job").mkdir(parents=True)
        self.counters(narrow)
        self.counters(narrow / "job")
        mountinfo = self.proc / "self/mountinfo"
        mountinfo.write_text(f"41 25 0:29 /parent {narrow} rw - cgroup2 cgroup rw\n" + mountinfo.read_text())
        self.counters(self.group.parent, limit=5 * GIB, current=3 * GIB, peak=3 * GIB)
        self.assertEqual(self.collect().headroom_bytes, 2 * GIB)

    def test_permission_denied_at_root_is_not_not_applicable(self):
        read_text = Path.read_text

        def read(path, *args, **kwargs):
            if path == self.cgroup / "memory.max":
                raise PermissionError(13, "permission denied", str(path))
            return read_text(path, *args, **kwargs)

        with patch.object(Path, "read_text", read):
            sample = self.collect()
        self.assertEqual(probe.evaluate_samples([sample]), probe.MemoryVerdict.UNKNOWN)
        self.assertEqual(sample._metadata["cgroups"][-1]["status"], "controller")

    def test_nonhierarchical_events_cannot_masquerade_as_zero_ooms(self):
        mountinfo = self.proc / "self/mountinfo"
        mountinfo.write_text(mountinfo.read_text().replace(" - cgroup2 cgroup rw", " - cgroup2 cgroup rw,memory_localevents"))
        sample = self.collect()
        self.assertIsNone(sample.oom_kills)
        self.assertEqual(probe.evaluate_samples([sample]), probe.MemoryVerdict.UNKNOWN)

    def test_unresolvable_or_traversing_membership_is_unknown(self):
        for membership in ("0::/../outside\n", "0::/other\n", "1:memory:/parent/job\n"):
            with self.subTest(membership=membership):
                (self.proc / "self/cgroup").write_text(membership)
                self.assertEqual(probe.evaluate_samples([self.collect()]), probe.MemoryVerdict.UNKNOWN)


class VerdictTest(unittest.TestCase):
    def test_exact_reserve_passes_and_one_byte_less_is_unsafe(self):
        for current, peak, expected in (
            (2147483648, 2147483648, probe.MemoryVerdict.PASS),
            (2147483647, 2147483648, probe.MemoryVerdict.UNSAFE),
            (2147483648, 2147483647, probe.MemoryVerdict.UNSAFE),
        ):
            with self.subTest(current=current, peak=peak):
                self.assertEqual(probe.evaluate_samples([probe.MemorySample(current, peak, 4)]), expected)

    def test_every_sample_matters_and_known_pressure_beats_unknown(self):
        good = probe.MemorySample(3 * GIB, 3 * GIB, 0)
        low = probe.MemorySample(2147483647, 3 * GIB, 0)
        missing = probe.MemorySample(None, None, None)
        self.assertEqual(probe.evaluate_samples([good, low, good]), probe.MemoryVerdict.UNSAFE)
        self.assertEqual(probe.evaluate_samples([good, missing, low]), probe.MemoryVerdict.UNSAFE)

    def test_increased_oom_counter_is_unsafe(self):
        samples = [probe.MemorySample(3 * GIB, 3 * GIB, value) for value in (7, 8)]
        self.assertEqual(probe.evaluate_samples(samples), probe.MemoryVerdict.UNSAFE)

    def test_empty_missing_or_reset_evidence_cannot_pass(self):
        for samples in (
            [],
            [probe.MemorySample(None, 3 * GIB, 0)],
            [probe.MemorySample(3 * GIB, None, 0)],
            [probe.MemorySample(3 * GIB, 3 * GIB, None)],
            [probe.MemorySample(3 * GIB, 3 * GIB, 8), probe.MemorySample(3 * GIB, 3 * GIB, 7)],
        ):
            with self.subTest(samples=samples):
                self.assertEqual(probe.evaluate_samples(samples), probe.MemoryVerdict.UNKNOWN)

    def test_counter_reset_between_endpoints_is_unknown(self):
        samples = [probe.MemorySample(3 * GIB, 3 * GIB, value) for value in (7, 6, 7)]
        self.assertEqual(probe.evaluate_samples(samples), probe.MemoryVerdict.UNKNOWN)

    def test_changing_cgroup_ancestry_is_unknown(self):
        samples = [
            probe.MemorySample(3 * GIB, 3 * GIB, 0, {"cgroup_identity": identity})
            for identity in (["/job", "/", "/first"], ["/job", "/", "/second"])
        ]
        self.assertEqual(probe.evaluate_samples(samples), probe.MemoryVerdict.UNKNOWN)


class OwnedCommandTest(LinuxFixtureTest):
    def launch(self, code):
        receipt = self.root / "receipt.json"
        command = [sys.executable, "-c", code]
        args = ["--output", str(receipt), "--", *command]
        wrapper = (
            "from pathlib import Path; import sys; "
            f"sys.path.insert(0, {str(REPO)!r}); "
            "from scripts import ci_resource_probe as p; "
            f"p._PROC = Path({str(self.proc)!r}); "
            f"sys.exit(p.main({args!r}))"
        )
        return subprocess.Popen(
            [sys.executable, "-c", wrapper], stdout=subprocess.PIPE, stderr=subprocess.PIPE
        ), receipt, command

    def run_child(self, code):
        process, receipt, command = self.launch(code)
        try:
            stdout, stderr = process.communicate(timeout=10)
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=3)
        return process.returncode, stdout, stderr, json.loads(receipt.read_text()), command

    def test_nonzero_command_exit_wins_over_unknown_diagnostics(self):
        code = f"from pathlib import Path; Path({str(self.group / 'memory.events')!r}).unlink(); raise SystemExit(42)"
        status, _, _, receipt, command = self.run_child(code)
        self.assertEqual(status, 42)
        self.assertEqual(receipt["command_exit_code"], 42)
        self.assertEqual(receipt["argv"], command)
        self.assertEqual(receipt["qualification"], "unknown")
        self.assertIsNone(receipt["oom_kill_delta"])

    def test_success_with_unsafe_or_unknown_final_evidence_fails(self):
        for counter, value, expected in (
            ("memory.peak", str(3 * GIB), "unsafe"),
            ("memory.events", "oom_kill 1\n", "unsafe"),
            ("memory.events", "malformed\n", "unknown"),
        ):
            with self.subTest(counter=counter, expected=expected):
                self.counters(self.group, limit=4 * GIB)
                self.counters(self.group.parent)
                target = self.group.parent if counter == "memory.events" else self.group
                code = f"from pathlib import Path; Path({str(target / counter)!r}).write_text({value!r})"
                status, _, _, receipt, _ = self.run_child(code)
                self.assertNotEqual(status, 0)
                self.assertEqual(receipt["command_exit_code"], 0)
                self.assertEqual(receipt["qualification"], expected)
                if value == "oom_kill 1\n":
                    self.assertEqual(receipt["oom_kill_delta"], 1)

    def test_initial_insufficient_headroom_does_not_launch(self):
        (self.group / "memory.max").write_text(str(3 * GIB - 1))
        marker = self.root / "launched"
        status, _, _, receipt, _ = self.run_child(
            f"from pathlib import Path; Path({str(marker)!r}).touch()"
        )
        self.assertNotEqual(status, 0)
        self.assertFalse(marker.exists())
        self.assertIsNone(receipt["command_exit_code"])
        self.assertEqual(receipt["qualification"], "unsafe")

    def test_known_low_host_bound_still_blocks_with_unknown_cgroup_limit(self):
        self.mem_available(GIB)
        (self.group / "memory.max").unlink()
        marker = self.root / "launched"
        status, _, _, receipt, _ = self.run_child(
            f"from pathlib import Path; Path({str(marker)!r}).touch()"
        )
        self.assertNotEqual(status, 0)
        self.assertFalse(marker.exists())
        self.assertEqual(receipt["qualification"], "unsafe")

    def test_stdout_stderr_and_receipt_are_preserved(self):
        status, stdout, stderr, receipt, command = self.run_child(
            "import os; os.write(1, b'normal output\\n'); os.write(2, b'normal error\\n')"
        )
        self.assertEqual((status, stdout, stderr), (0, b"normal output\n", b"normal error\n"))
        self.assertEqual(receipt["schema"], 1)
        self.assertEqual(receipt["argv"], command)
        self.assertEqual(receipt["minimum_headroom_bytes"], 3 * GIB)
        self.assertEqual(receipt["oom_kill_delta"], 0)
        self.assertEqual(receipt["qualification"], "pass")
        self.assertGreater(receipt["elapsed_seconds"], 0)
        self.assertGreaterEqual(len(receipt["samples"]), 2)
        self.assertEqual(receipt["cgroup_peaks"][0]["initial_bytes"], GIB)
        self.assertEqual(receipt["cgroup_peaks"][0]["maximum_bytes"], GIB)
        self.assertIn("predate", receipt["cgroup_peaks"][0]["attribution"])

    def test_receipt_write_failure_cannot_replace_exit_42(self):
        receipt = self.root / "receipt.json"
        read_text = Path.read_text
        write_text = Path.write_text
        writes = 0

        def write(path, *args, **kwargs):
            nonlocal writes
            if path == receipt:
                writes += 1
                if writes > 1:
                    raise OSError("receipt write failed")
            return write_text(path, *args, **kwargs)

        with patch.object(probe, "_PROC", self.proc), patch.object(Path, "write_text", write):
            with patch.object(sys, "stderr"):
                status = probe.main(["--output", str(receipt), "--", sys.executable, "-c", "raise SystemExit(42)"])
        self.assertEqual(status, 42)
        self.assertIsNone(json.loads(read_text(receipt))["command_exit_code"])

    def test_collection_exception_cannot_replace_exit_42(self):
        receipt = self.root / "receipt.json"
        initial = self.collect()
        subreaper = probe._subreaper()
        with patch.object(probe, "collect_sample", side_effect=[initial, OSError("collection failed")]):
            status = probe.main(["--output", str(receipt), "--", sys.executable, "-c", "raise SystemExit(42)"])
        self.assertEqual(status, 42)
        self.assertEqual(probe._subreaper(), subreaper)
        data = json.loads(receipt.read_text())
        self.assertEqual(data["command_exit_code"], 42)
        self.assertEqual(data["qualification"], "unknown")

    def test_launch_failure_receipt_keeps_exit_code_null(self):
        receipt = self.root / "receipt.json"
        with patch.object(probe, "_PROC", self.proc):
            status = probe.main(["--output", str(receipt), "--", str(self.root / "missing-command")])
        self.assertEqual(status, 127)
        data = json.loads(receipt.read_text())
        self.assertIsNone(data["command_exit_code"])
        self.assertEqual(data["qualification"], "unknown")

    def test_cancellation_before_launch_cannot_qualify_a_null_command(self):
        receipt = self.root / "receipt.json"
        marker = self.root / "launched"
        initial = self.collect()

        def cancel():
            os.kill(os.getpid(), signal.SIGTERM)
            return initial

        command = [sys.executable, "-c", f"from pathlib import Path; Path({str(marker)!r}).touch()"]
        with patch.object(probe, "collect_sample", cancel):
            status = probe.main(["--output", str(receipt), "--", *command])
        self.assertEqual(status, 143)
        self.assertFalse(marker.exists())
        data = json.loads(receipt.read_text())
        self.assertIsNone(data["command_exit_code"])
        self.assertEqual(data["qualification"], "unknown")

    def test_sigterm_during_final_collection_retires_owned_group(self):
        receipt = self.root / "receipt.json"
        pids = self.root / "pids.json"
        initial = self.collect()
        collections = 0
        owned = []

        def collect():
            nonlocal collections
            collections += 1
            if collections == 2:
                owned.extend(json.loads(pids.read_text()))
                os.kill(os.getpid(), signal.SIGTERM)
            return initial

        code = (
            "import json, os, subprocess, sys; from pathlib import Path; "
            "p = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)']); "
            f"Path({str(pids)!r}).write_text(json.dumps([os.getpid(), p.pid])); "
            "raise SystemExit(42)"
        )
        started = time.monotonic()
        try:
            with patch.object(probe, "collect_sample", collect):
                status = probe.main(["--output", str(receipt), "--", sys.executable, "-c", code])
            self.assertEqual(status, 143)
            self.assertLess(time.monotonic() - started, 8)
            data = json.loads(receipt.read_text())
            self.assertEqual(data["command_exit_code"], 42)
            self.assertIn("SIGTERM", data["reason"])
            stat = Path(f"/proc/{owned[1]}/stat")
            if stat.exists():
                self.assertEqual(stat.read_text().split(") ", 1)[1][0], "Z")
        finally:
            if not owned and pids.exists():
                owned = json.loads(pids.read_text())
            if owned:
                try:
                    os.killpg(owned[0], signal.SIGKILL)
                except ProcessLookupError:
                    pass

    def test_sigterm_during_final_receipt_returns_143(self):
        receipt = self.root / "receipt.json"
        write_text = Path.write_text
        writes = 0

        def write(path, *args, **kwargs):
            nonlocal writes
            result = write_text(path, *args, **kwargs)
            if path == receipt:
                writes += 1
                if writes == 2:
                    os.kill(os.getpid(), signal.SIGTERM)
            return result

        with patch.object(probe, "_PROC", self.proc), patch.object(Path, "write_text", write):
            status = probe.main(["--output", str(receipt), "--", sys.executable, "-c", "raise SystemExit(0)"])
        self.assertEqual(status, 143)
        data = json.loads(receipt.read_text())
        self.assertEqual(data["command_exit_code"], 0)
        self.assertIn("SIGTERM", data["reason"])

    def test_sigterm_receipt_preserves_cooperative_child_exit(self):
        ready = self.root / "ready"
        pending = self.root / "pending"
        code = (
            "import os, signal, sys, time; from pathlib import Path; "
            "signal.signal(signal.SIGTERM, lambda *_: sys.exit(42)); "
            f"Path({str(pending)!r}).write_text(str(os.getpid())); "
            f"Path({str(pending)!r}).replace({str(ready)!r}); time.sleep(60)"
        )
        process, receipt, _ = self.launch(code)
        try:
            deadline = time.monotonic() + 5
            while not ready.exists() and process.poll() is None and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue(ready.exists())
            process.send_signal(signal.SIGTERM)
            process.communicate(timeout=8)
            self.assertEqual(process.returncode, 143)
            self.assertEqual(json.loads(receipt.read_text())["command_exit_code"], 42)
        finally:
            if process.poll() is None:
                if ready.exists():
                    try:
                        os.killpg(int(ready.read_text()), signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                process.kill()
            process.communicate(timeout=3)

    def test_sampling_keeps_a_transient_low_observation(self):
        path = str(self.proc / "meminfo")
        code = (
            "from pathlib import Path; import time; "
            f"p = Path({path!r}); p.write_text('MemAvailable: 1048576 kB\\n'); "
            "time.sleep(1.5); p.write_text('MemAvailable: 7340032 kB\\n')"
        )
        status, _, _, receipt, _ = self.run_child(code)
        self.assertNotEqual(status, 0)
        self.assertEqual(receipt["minimum_headroom_bytes"], GIB)
        self.assertGreaterEqual(len(receipt["samples"]), 3)

    def test_sigterm_retires_owned_child_group_and_returns_143(self):
        ready = self.root / "ready"
        pids = self.root / "pids.json"
        pending = self.root / "pending.json"
        grandchild = (
            "import signal, time; from pathlib import Path; "
            "signal.signal(signal.SIGTERM, signal.SIG_IGN); "
            f"Path({str(ready)!r}).touch(); time.sleep(60)"
        )
        child = (
            "import json, os, subprocess, sys; from pathlib import Path; "
            f"p = subprocess.Popen([sys.executable, '-c', {grandchild!r}]); "
            f"Path({str(pending)!r}).write_text(json.dumps([os.getpid(), p.pid])); "
            f"Path({str(pending)!r}).replace({str(pids)!r}); p.wait()"
        )
        process, receipt, _ = self.launch(child)
        owned = []
        started = time.monotonic()
        try:
            deadline = started + 5
            while not (ready.exists() and pids.exists()) and process.poll() is None and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue(ready.exists(), "owned grandchild did not become ready")
            owned = json.loads(pids.read_text())
            process.send_signal(signal.SIGTERM)
            process.communicate(timeout=8)
            self.assertEqual(process.returncode, 143)
            self.assertLess(time.monotonic() - started, 8)
            data = json.loads(receipt.read_text())
            self.assertEqual(data["command_exit_code"], 143)
            self.assertIn("SIGTERM", data["reason"])
            for pid in owned:
                stat = Path(f"/proc/{pid}/stat")
                if stat.exists():
                    self.assertEqual(stat.read_text().split(") ", 1)[1][0], "Z")
        finally:
            if owned:
                try:
                    os.killpg(owned[0], signal.SIGKILL)
                except ProcessLookupError:
                    pass
            if process.poll() is None:
                process.kill()
            process.communicate(timeout=3)

    def test_sigterm_retires_detached_descendant_not_unrelated_process(self):
        ready = self.root / "detached.json"
        detached = (
            "import os, signal, time; from pathlib import Path; "
            "signal.signal(signal.SIGTERM, signal.SIG_IGN); "
            f"p = Path({str(ready) + '.pending'!r}); p.write_text(str(os.getpid())); "
            f"p.replace({str(ready)!r}); time.sleep(60)"
        )
        child = (
            "import subprocess, sys, time; "
            f"subprocess.Popen([sys.executable, '-c', {detached!r}], "
            "start_new_session=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL); "
            "time.sleep(60)"
        )
        unrelated = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
        process, receipt, _ = self.launch(child)
        detached_fd = None
        try:
            deadline = time.monotonic() + 5
            while not ready.exists() and process.poll() is None and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue(ready.exists(), "detached descendant did not become ready")
            detached_fd = os.pidfd_open(int(ready.read_text()))
            process.send_signal(signal.SIGTERM)
            process.communicate(timeout=8)
            self.assertEqual(process.returncode, 143)
            self.assertEqual(json.loads(receipt.read_text())["command_exit_code"], 143)
            self.assertIsNone(unrelated.poll(), "cleanup killed pre-existing unrelated work")
            self.assertTrue(select.select([detached_fd], [], [], 0)[0], "detached descendant is live")
        finally:
            if detached_fd is not None:
                try:
                    signal.pidfd_send_signal(detached_fd, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                os.close(detached_fd)
            if process.poll() is None:
                process.kill()
            process.communicate(timeout=3)
            unrelated.kill()
            unrelated.wait(timeout=3)

    def test_double_fork_from_non_main_thread_retires_on_success_and_failure(self):
        ready = self.root / "daemon-pid"
        release = self.root / "release"
        daemonize = (
            "import os, signal, time; from pathlib import Path\n"
            "if os.fork(): os._exit(0)\n"
            "os.setsid()\n"
            "if os.fork(): os._exit(0)\n"
            "signal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
            f"p = Path({str(ready) + '.pending'!r}); p.write_text(str(os.getpid())); p.replace({str(ready)!r})\n"
            "time.sleep(60)\n"
        )
        for code in (0, 42):
            with self.subTest(primary_status=code):
                ready.unlink(missing_ok=True)
                release.unlink(missing_ok=True)
                child = (
                    "import subprocess, sys, threading, time; from pathlib import Path; "
                    f"t = threading.Thread(target=lambda: subprocess.run([sys.executable, '-c', {daemonize!r}], "
                    "stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)); t.start(); t.join(); "
                    f"ready = Path({str(ready)!r})\n"
                    "while not ready.exists(): time.sleep(0.01)\n"
                    f"while not Path({str(release)!r}).exists(): time.sleep(0.01)\n"
                    f"raise SystemExit({code})\n"
                )
                process, receipt, _ = self.launch(child)
                descriptor = None
                try:
                    deadline = time.monotonic() + 5
                    while not ready.exists() and process.poll() is None and time.monotonic() < deadline:
                        time.sleep(0.01)
                    self.assertTrue(ready.exists())
                    descriptor = os.pidfd_open(int(ready.read_text()))
                    release.touch()
                    process.communicate(timeout=8)
                    self.assertEqual(process.returncode, code)
                    self.assertEqual(json.loads(receipt.read_text())["command_exit_code"], code)
                    self.assertTrue(select.select([descriptor], [], [], 0)[0], "double-fork daemon is live")
                finally:
                    release.touch()
                    if descriptor is not None:
                        try:
                            signal.pidfd_send_signal(descriptor, signal.SIGKILL)
                        except ProcessLookupError:
                            pass
                        os.close(descriptor)
                    if process.poll() is None:
                        process.kill()
                    process.communicate(timeout=3)

    def test_cancellation_retires_detached_worker_born_during_cleanup(self):
        daemon_ready = self.root / "daemon-pid"
        worker_ready = self.root / "worker-pid"
        worker = (
            "import os, signal, time; from pathlib import Path; "
            "signal.signal(signal.SIGTERM, signal.SIG_IGN); "
            f"p = Path({str(worker_ready) + '.pending'!r}); p.write_text(str(os.getpid())); "
            f"p.replace({str(worker_ready)!r}); time.sleep(60)"
        )
        daemon = (
            "import os, signal, subprocess, sys, time; from pathlib import Path\n"
            "def on_term(*_):\n"
            "    signal.signal(signal.SIGTERM, signal.SIG_IGN)\n"
            f"    subprocess.Popen([sys.executable, '-c', {worker!r}], start_new_session=True)\n"
            "signal.signal(signal.SIGTERM, on_term)\n"
            f"p = Path({str(daemon_ready) + '.pending'!r}); p.write_text(str(os.getpid())); p.replace({str(daemon_ready)!r})\n"
            "time.sleep(60)\n"
        )
        child = (
            "import subprocess, sys, time; "
            f"subprocess.Popen([sys.executable, '-c', {daemon!r}], start_new_session=True, "
            "stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL); time.sleep(60)"
        )
        process, receipt, _ = self.launch(child)
        descriptors = []
        try:
            for marker in (daemon_ready, worker_ready):
                deadline = time.monotonic() + 5
                while not marker.exists() and process.poll() is None and time.monotonic() < deadline:
                    time.sleep(0.01)
                self.assertTrue(marker.exists())
                descriptors.append(os.pidfd_open(int(marker.read_text())))
                if marker == daemon_ready:
                    process.send_signal(signal.SIGTERM)
            process.communicate(timeout=8)
            self.assertEqual(process.returncode, 143)
            self.assertEqual(json.loads(receipt.read_text())["command_exit_code"], 143)
            for descriptor in descriptors:
                self.assertTrue(select.select([descriptor], [], [], 0)[0], "late descendant is live")
        finally:
            for descriptor in descriptors:
                try:
                    signal.pidfd_send_signal(descriptor, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                os.close(descriptor)
            if process.poll() is None:
                process.kill()
            process.communicate(timeout=3)

    def test_missing_ownership_primitives_fail_before_launch(self):
        receipt = self.root / "receipt.json"
        marker = self.root / "launched"
        command = [sys.executable, "-c", f"from pathlib import Path; Path({str(marker)!r}).touch()"]
        for target, name in ((probe, "_subreaper"), (os, "pidfd_open"), (signal, "pidfd_send_signal")):
            with self.subTest(primitive=name), patch.object(probe, "_PROC", self.proc):
                with patch.object(target, name, side_effect=OSError("ownership unavailable")):
                    status = probe.main(["--output", str(receipt), "--", *command])
                self.assertEqual(status, 126)
                self.assertFalse(marker.exists())
                data = json.loads(receipt.read_text())
                self.assertIsNone(data["command_exit_code"])
                self.assertEqual(data["qualification"], "unknown")

    def test_pre_existing_child_is_not_acquired_or_killed(self):
        receipt = self.root / "receipt.json"
        marker = self.root / "launched"
        unrelated = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
        before = probe._subreaper()
        try:
            with patch.object(probe, "_PROC", self.proc):
                status = probe.main(["--output", str(receipt), "--", sys.executable, "-c",
                                     f"from pathlib import Path; Path({str(marker)!r}).touch()"])
            self.assertEqual(status, 126)
            self.assertFalse(marker.exists())
            self.assertIsNone(unrelated.poll())
            self.assertEqual(probe._subreaper(), before)
            self.assertIsNone(json.loads(receipt.read_text())["command_exit_code"])
        finally:
            unrelated.kill()
            unrelated.wait(timeout=3)

    def test_threaded_host_or_custom_sigchld_cannot_launch(self):
        receipt = self.root / "receipt.json"
        marker = self.root / "launched"
        command = [sys.executable, "-c", f"from pathlib import Path; Path({str(marker)!r}).touch()"]
        done = threading.Event()
        thread = threading.Thread(target=done.wait)
        thread.start()
        try:
            with patch.object(probe, "_PROC", self.proc):
                status = probe.main(["--output", str(receipt), "--", *command])
            self.assertEqual(status, 126)
            self.assertFalse(marker.exists())
        finally:
            done.set()
            thread.join(timeout=3)
        with patch.object(probe, "_PROC", self.proc), patch.object(signal, "getsignal", return_value=signal.SIG_IGN):
            status = probe.main(["--output", str(receipt), "--", *command])
        self.assertEqual(status, 126)
        self.assertFalse(marker.exists())
        self.assertIsNone(json.loads(receipt.read_text())["command_exit_code"])


if __name__ == "__main__":
    unittest.main()
