"""Real file, descriptor, restoration and subprocess fault checks (standard library only)."""
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import unittest
from fixture_io import OwnedFile, restoring, run_child


class FixtureOwnershipTest(unittest.TestCase):
    def root(self):
        # Retain only genuine private fixtures; never clean an operator deployment.
        return Path(tempfile.mkdtemp(prefix="task13-fixture-fault-"))

    def test_exclusive_acquisition_preserves_existing_symlink_and_hardlink(self):
        for kind in ["file", "symlink", "hardlink"]:
            with self.subTest(kind=kind):
                root = self.root()
                original = root / "operator"
                original.write_bytes(b"unknown")
                destination = root / "destination"
                if kind == "file":
                    destination.write_bytes(b"unknown")
                elif kind == "symlink":
                    destination.symlink_to(original)
                else:
                    destination.hardlink_to(original)
                with self.assertRaises(FileExistsError):
                    OwnedFile(destination, b"forbidden")
                self.assertEqual(original.read_bytes(), b"unknown")
                self.assertEqual(destination.read_bytes(), b"unknown")

    def test_stale_sidecar_restores_same_owned_inode_after_original_failure(self):
        root = self.root()
        primary = AssertionError("actual stale invocation assertion")
        with OwnedFile(root / "review", b"external original approval") as owner:
            inode = owner.path.stat().st_ino
            with self.assertRaises(AssertionError) as raised:
                with restoring(owner):
                    owner.write(b"stale approval")
                    raise primary
            self.assertIs(raised.exception, primary)
            self.assertEqual(owner.read(), b"external original approval")
            self.assertEqual(owner.path.stat().st_ino, inode)

    def test_failed_first_restoration_does_not_skip_second_or_replace_primary(self):
        root = self.root()
        primary = RuntimeError("original fixture failure")
        with OwnedFile(root / "first", b"first original") as first, OwnedFile(root / "second", b"second original") as second:
            with self.assertRaises(RuntimeError) as raised:
                with restoring(first, second):
                    first.write(b"first tamper")
                    second.write(b"second tamper")
                    first.path.unlink()
                    first.path.write_bytes(b"independent operator replacement")
                    raise primary
            self.assertIs(raised.exception, primary)
            self.assertEqual(first.path.read_bytes(), b"independent operator replacement")
            self.assertEqual(second.read(), b"second original")
            self.assertEqual(len(primary.cleanup_failures), 1)

    def test_symlink_replacement_is_never_followed_during_restore(self):
        root = self.root()
        external = root / "external"
        external.write_bytes(b"operator data")
        with OwnedFile(root / "owned", b"original") as owner:
            with self.assertRaises(OSError):
                with restoring(owner):
                    owner.write(b"tamper")
                    owner.path.unlink()
                    owner.path.symlink_to(external)
            self.assertEqual(external.read_bytes(), b"operator data")
            self.assertTrue(owner.path.is_symlink())

    def test_unowned_hardlink_alias_is_rejected_during_restore(self):
        root = self.root()
        with OwnedFile(root / "owned", b"original") as owner:
            with self.assertRaises(OSError):
                with restoring(owner):
                    owner.write(b"tamper")
                    (root / "unowned-alias").hardlink_to(owner.path)
            self.assertEqual((root / "unowned-alias").read_bytes(), b"tamper")

    def test_independent_multiple_restoration_failures_are_attached_to_primary(self):
        root = self.root()
        primary = RuntimeError("original")
        with OwnedFile(root / "first", b"one") as first, OwnedFile(root / "second", b"two") as second:
            with self.assertRaises(RuntimeError) as raised:
                with restoring(first, second):
                    first.path.unlink()
                    second.path.unlink()
                    raise primary
            self.assertIs(raised.exception, primary)
            self.assertEqual(len(primary.cleanup_failures), 2)

    def test_timeout_persists_streams_and_outcome_before_reaping_only_owned_child(self):
        root = self.root()
        unrelated = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(30)"])
        command = [sys.executable, "-c", "import signal,time,sys; signal.signal(signal.SIGTERM,signal.SIG_IGN); print('partial stdout',flush=True); print('partial stderr',file=sys.stderr,flush=True); time.sleep(30)"]
        try:
            with OwnedFile(root / "review", b"external original") as owner:
                with self.assertRaises(subprocess.TimeoutExpired) as raised:
                    with restoring(owner):
                        owner.write(b"stale")
                        run_child(command, root, "timeout", timeout=1, termination_grace=0.2)
                self.assertEqual(raised.exception.cmd, command)
                self.assertEqual(owner.read(), b"external original")
            metadata = json.loads((root / "timeout.command.json").read_text())
            self.assertEqual(metadata["argv"], command)
            self.assertEqual(metadata["cwd"], str(root))
            self.assertTrue(metadata["timedOut"])
            self.assertTrue(metadata["reaped"])
            self.assertEqual(metadata["exitCode"], -signal.SIGKILL)
            self.assertLess(metadata["transitions"].index("timed-out"), metadata["transitions"].index("reaped"))
            self.assertIn("partial stdout", (root / "timeout.stdout.log").read_text())
            self.assertIn("partial stderr", (root / "timeout.stderr.log").read_text())
            self.assertIn("partial stdout", (root / "timeout.log").read_text())
            with self.assertRaises(ChildProcessError):
                os.waitpid(metadata["pid"], os.WNOHANG)
            self.assertIsNone(unrelated.poll(), "Unrelated process was signalled")
        finally:
            unrelated.terminate()
            unrelated.wait(timeout=3)

    def test_normal_nonzero_exit_and_large_streams_are_preserved_without_offset_mutation(self):
        root = self.root()
        command = [sys.executable, "-c", "import sys; print('out'*20000,flush=True); print('err'*20000,file=sys.stderr,flush=True); sys.exit(37)"]
        result = run_child(command, root, "nonzero")
        self.assertEqual(result.returncode, 37)
        self.assertEqual(result.stdout, "out" * 20000 + "\n")
        self.assertEqual(result.stderr, "err" * 20000 + "\n")
        metadata = json.loads((root / "nonzero.command.json").read_text())
        self.assertEqual(metadata["exitCode"], 37)
        self.assertFalse(metadata["timedOut"])
        self.assertTrue(metadata["reaped"])

    def test_spawn_failure_retains_argv_cwd_and_failure_state(self):
        root = self.root()
        command = [str(root / "no-such-executable")]
        with self.assertRaises(FileNotFoundError):
            run_child(command, root, "spawn-failure")
        metadata = json.loads((root / "spawn-failure.command.json").read_text())
        self.assertEqual(metadata["argv"], command)
        self.assertEqual(metadata["state"], "launch-failed")
        self.assertIsNone(metadata["pid"])
        self.assertIsNone(metadata["exitCode"])
        self.assertFalse(metadata["timedOut"])

    def test_existing_evidence_file_is_not_overwritten_and_child_does_not_start(self):
        root = self.root()
        (root / "owned.stdout.log").write_bytes(b"unknown evidence")
        with self.assertRaises(FileExistsError):
            run_child([sys.executable, "-c", "raise Exception('must not start')"], root, "owned")
        self.assertEqual((root / "owned.stdout.log").read_bytes(), b"unknown evidence")


if __name__ == "__main__":
    unittest.main()
