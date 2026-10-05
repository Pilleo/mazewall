#!/usr/bin/env python3
"""Verify portable distribution publication and tamper refusal with real files."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("distribution", Path(__file__).with_name("distribution.py"))
distribution = importlib.util.module_from_spec(spec)
spec.loader.exec_module(distribution)


class DistributionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.tree = self.root / "release"
        self.tree.mkdir()
        self.tree.joinpath("operator.txt").write_text("verified fixture")
        self.tree.joinpath("licenses").mkdir()
        self.tree.joinpath("licenses/Apache-2.0.txt").write_text("license fixture")

    def test_index_roundtrip_and_tampered_notice_refused(self):
        distribution.write_checksums(self.tree)
        distribution.check_checksums(self.tree)
        self.tree.joinpath("licenses/Apache-2.0.txt").write_text("changed")
        with self.assertRaises(ValueError):
            distribution.check_checksums(self.tree)

    def test_nested_checksum_named_file_is_not_exempt_from_inventory(self):
        distribution.write_checksums(self.tree)
        self.tree.joinpath("licenses/SHA256SUMS").write_text("unlisted")
        with self.assertRaises(ValueError):
            distribution.check_checksums(self.tree)

    def test_missing_and_unlisted_distribution_file_refused(self):
        distribution.write_checksums(self.tree)
        self.tree.joinpath("operator.txt").unlink()
        with self.assertRaises(FileNotFoundError):
            distribution.check_checksums(self.tree)
        self.tree.joinpath("operator.txt").write_text("verified fixture")
        self.tree.joinpath("unlisted").write_text("surprise")
        with self.assertRaises(ValueError):
            distribution.check_checksums(self.tree)

    def test_reproducible_archive_and_checksum(self):
        distribution.write_checksums(self.tree)
        one, two = self.root / "one.tar.gz", self.root / "two.tar.gz"
        distribution.archive(self.tree, one)
        distribution.archive(self.tree, two)
        self.assertEqual(one.read_bytes(), two.read_bytes())

    def test_symbolic_and_traversing_checksum_paths_refused(self):
        self.tree.joinpath("linked").symlink_to(self.tree / "operator.txt")
        with self.assertRaises(ValueError):
            distribution.write_checksums(self.tree)
        self.tree.joinpath("linked").unlink()
        self.tree.joinpath("SHA256SUMS").write_text("a" * 64 + "  ../outside\n")
        with self.assertRaises(ValueError):
            distribution.check_checksums(self.tree)


if __name__ == "__main__":
    unittest.main()
