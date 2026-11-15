#!/usr/bin/env python3
"""Focused negative controls for confined-helper CI result validation."""

import tempfile
import subprocess
import unittest
from pathlib import Path
import verify_confined_helper_results as results


class ResultsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name)

    def suite(self, name, *, skipped=0, cases=1, filename=None):
        (self.path / (filename or f"TEST-{name}.xml")).write_text(
            f'<testsuite name="{name}" tests="{cases}" failures="0" errors="0" skipped="{skipped}">'
            + "".join(f'<testcase classname="{name}" name="case{i}"/>' for i in range(cases))
            + "</testsuite>"
        )

    def all_suites(self):
        for name in results.CLASSES:
            self.suite(name)

    def test_confined_accepts_complete_fresh_suites(self):
        self.all_suites()
        retained = self.path / "retained"
        results.verify("confined", self.path, 0, retained)
        self.assertEqual(3, len(list(retained.glob("TEST-*.xml"))))
        for copy in retained.glob("TEST-*.xml"):
            self.assertEqual((self.path / copy.name).read_bytes(), copy.read_bytes())

    def test_rejects_missing_skipped_stale_foreign_and_duplicate(self):
        self.all_suites()
        with self.assertRaises(ValueError):
            results.verify("confined", self.path, 2**63 - 1)
        name = next(iter(results.CLASSES))
        self.suite(name, skipped=1)
        with self.assertRaises(ValueError):
            results.verify("confined", self.path, 0)
        self.suite(name)
        self.suite(name, filename="TEST-duplicate.xml")
        with self.assertRaises(ValueError):
            results.verify("confined", self.path, 0)
        (self.path / "TEST-duplicate.xml").unlink()
        self.suite("foreign")
        with self.assertRaises(ValueError):
            results.verify("confined", self.path, 0)
        (self.path / "TEST-foreign.xml").unlink()
        (self.path / f"TEST-{name}.xml").unlink()
        with self.assertRaises(ValueError):
            results.verify("confined", self.path, 0)

    def test_baseline_disjoint_and_missing(self):
        with self.assertRaises(ValueError):
            results.verify("baseline", self.path)
        self.suite("ordinary", skipped=1)
        results.verify("baseline", self.path)
        target = self.path / "TEST-ordinary.xml"
        target.write_text(target.read_text().replace('failures="0"', 'failures="1"'))
        with self.assertRaises(ValueError):
            results.verify("baseline", self.path)
        self.suite("ordinary")
        self.suite(next(iter(results.CLASSES)))
        with self.assertRaises(ValueError):
            results.verify("baseline", self.path)

    def test_rejects_failure_error_empty_and_malformed(self):
        self.all_suites()
        name = next(iter(results.CLASSES))
        target = self.path / f"TEST-{name}.xml"
        original = target.read_text()
        for mutation in (
            original.replace('failures="0"', 'failures="1"'),
            original.replace('errors="0"', 'errors="1"'),
            original.replace('tests="1"', 'tests="0"'),
            original.replace('/>', '><failure/></testcase>'),
            '<testsuite',
        ):
            target.write_text(mutation)
            with self.assertRaises((ValueError, results.ET.ParseError)):
                results.verify("confined", self.path, 0)

    def test_rejects_symlink_and_oversized_xml(self):
        self.all_suites()
        name = next(iter(results.CLASSES))
        target = self.path / f"TEST-{name}.xml"
        target.unlink()
        target.symlink_to("missing")
        with self.assertRaises(OSError):
            results.verify("confined", self.path, 0)
        target.unlink()
        target.write_bytes(b"x" * (results.MAX_XML + 1))
        with self.assertRaises(ValueError):
            results.verify("confined", self.path, 0)

    def test_runner_refuses_missing_or_invalid_image_before_docker(self):
        runner = Path(__file__).with_name("run_confined_helper_tests.sh")
        for args in ([], ["--image", "latest", "--gradle-home", str(self.path)]):
            process = subprocess.run(["bash", str(runner), *args], capture_output=True, check=False)
            self.assertEqual(2, process.returncode)


class SecurityReceiptTest(unittest.TestCase):
    """Component tests of parsed receipts; these do not prove a real container."""

    def test_engine_and_namespace_fail_closed(self):
        results.check_engine(["name=seccomp"])
        with self.assertRaises(ValueError):
            results.check_engine(["name=rootless"])
        results.check_namespace("user:[1]", "user:[1]", "0 0 4294967295")
        for host, worker, mapping in (("user:[1]", "user:[2]", "0 0 4294967295"),
                                      ("user:[1]", "user:[1]", "0 1000 1")):
            with self.assertRaises(ValueError):
                results.check_namespace(host, worker, mapping)

    def test_identity_rejects_wrong_uid_gid_groups_caps_and_nnp(self):
        status = "Uid:\t1000\t1000\t1000\t1000\nGid:\t1001\t1001\t1001\t1001\nGroups:\t\nNoNewPrivs:\t1\n"
        status += "".join(f"{field}:\t0000010000000000\n" for field in ("CapInh", "CapPrm", "CapEff", "CapBnd", "CapAmb"))
        results.check_identity(status, 1000, 1001)
        for bad in (status.replace("Uid:\t1000", "Uid:\t0"), status.replace("Gid:\t1001", "Gid:\t0"),
                    status.replace("Groups:\t\n", "Groups:\t10\n"), status.replace("NoNewPrivs:\t1", "NoNewPrivs:\t0"),
                    status.replace("CapEff:\t0000010000000000", "CapEff:\t0000010000000001")):
            with self.assertRaises(ValueError):
                results.check_identity(bad, 1000, 1001)

    def test_state_refuses_nonzero_and_forced_retirement(self):
        valid = {"Status": "exited", "Running": False, "Pid": 0, "ExitCode": 0,
                 "OOMKilled": False, "Dead": False, "Error": ""}
        results.check_state(valid)
        for field, value in (("ExitCode", 1), ("Running", True), ("Pid", 8),
                             ("Status", "running"), ("OOMKilled", True), ("Dead", True), ("Error", "failure")):
            with self.assertRaises(ValueError):
                results.check_state({**valid, field: value})

    def test_ownership_rejects_a_different_caller(self):
        import os
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / "receipt").write_text("owned")
            results.check_ownership([path], os.getuid(), True)
            with self.assertRaises(ValueError):
                results.check_ownership([path], os.getuid() + 1, True)

    def test_policy_and_mounts_refuse_drift(self):
        import json
        policy = {"defaultAction": "SCMP_ACT_ERRNO"}
        config = {"CapAdd": ["CAP_CHECKPOINT_RESTORE", "CAP_SETUID", "CAP_SETGID", "CAP_SETPCAP"],
                  "CapDrop": ["ALL"], "SecurityOpt": ["no-new-privileges", "seccomp=" + json.dumps(policy)],
                  "UsernsMode": "host", "NetworkMode": "bridge", "ReadonlyRootfs": True,
                  "NanoCpus": 2000000000, "Memory": 8589934592, "PidsLimit": 1024,
                  "Privileged": False, "Devices": [], "DeviceRequests": None, "PidMode": "", "IpcMode": "private", "LxcConf": None, "Init": True,
                  "Tmpfs": {"/tmp": "rw,nosuid,nodev,exec,size=1g"},
                  "Ulimits": [{"Name": "core", "Hard": 0, "Soft": 0}]}
        results.check_host(config, policy)
        for bad in ({**config, "CapAdd": config["CapAdd"] + ["CAP_SYS_ADMIN"]},
                    {**config, "SecurityOpt": ["no-new-privileges", "seccomp={}"]},
                    {**config, "ReadonlyRootfs": False}, {**config, "Privileged": True},
                    {**config, "Devices": ["unexpected"]}, {**config, "DeviceRequests": ["unexpected"]},
                    {**config, "PidMode": "host"}, {**config, "IpcMode": "host"},
                    {**config, "LxcConf": ["unexpected"]}, {**config, "Init": False}):
            with self.assertRaises(ValueError):
                results.check_host(bad, policy)
        mounts = [{"Type": "bind", "Source": "/src", "Destination": "/src", "RW": True},
                  {"Type": "bind", "Source": "/cache", "Destination": "/container-gradle-home", "RW": True}]
        results.check_mounts(mounts, "/src", "/cache")
        with self.assertRaises(ValueError):
            results.check_mounts(mounts + [mounts[0]], "/src", "/cache")


if __name__ == "__main__":
    unittest.main()
