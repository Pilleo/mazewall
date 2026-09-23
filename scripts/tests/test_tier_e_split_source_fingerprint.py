import json
import pathlib
import subprocess
import sys
import tempfile
import unittest


SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "tier_e_split_source_fingerprint.py"


class SplitSourceFingerprintTest(unittest.TestCase):
    def test_dirty_tracked_and_untracked_inputs_change_digest_but_docs_do_not(self):
        with tempfile.TemporaryDirectory(prefix="tier-e-source-state-") as directory:
            root = pathlib.Path(directory)
            subprocess.run(["git", "init", "-q", str(root)], check=True)
            subprocess.run(["git", "-C", str(root), "config", "user.email", "test@example.invalid"], check=True)
            subprocess.run(["git", "-C", str(root), "config", "user.name", "Test"], check=True)
            source = root / "profiler/src/example.kt"
            source.parent.mkdir(parents=True)
            source.write_text("class Before\n")
            docs = root / "docs/readme.md"
            docs.parent.mkdir()
            docs.write_text("original\n")
            subprocess.run(["git", "-C", str(root), "add", "."], check=True)
            subprocess.run(["git", "-C", str(root), "commit", "-qm", "baseline"], check=True)

            def snapshot():
                return json.loads(subprocess.check_output([sys.executable, str(SCRIPT), str(root)], text=True))

            baseline = snapshot()
            docs.write_text("edited\n")
            self.assertEqual(baseline["source_digest"], snapshot()["source_digest"])
            source.write_text("class After\n")
            self.assertNotEqual(baseline["source_digest"], snapshot()["source_digest"])
            source.write_text("class Before\n")
            extra = root / "profiler/src/new.kt"
            extra.write_text("class New\n")
            self.assertNotEqual(baseline["source_digest"], snapshot()["source_digest"])

            saved = root / "before.json"
            saved.write_text(json.dumps(baseline))
            result = subprocess.run(
                [sys.executable, str(SCRIPT), str(root), "--verify", str(saved)],
                text=True, capture_output=True, check=False,
            )
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(json.loads(result.stdout)["unchanged"])


if __name__ == "__main__":
    unittest.main()
