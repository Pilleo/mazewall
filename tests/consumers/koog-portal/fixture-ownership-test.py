#!/usr/bin/env python3
"""Actual temporary-filesystem conflicts; no mocked move or generator."""
import fcntl
import importlib.util
import os
from pathlib import Path
import tempfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
spec = importlib.util.spec_from_file_location("fixture_files", HERE / "fixture-files.py")
files = importlib.util.module_from_spec(spec)
spec.loader.exec_module(files)
parent = ROOT / "build/task10-evidence/round1"
parent.mkdir(parents=True, exist_ok=True)
evidence = Path(tempfile.mkdtemp(prefix="fixture-ownership.", dir=parent))


def put(path, data):
    with path.open("xb") as stream:
        stream.write(data)


def conflict(action):
    try:
        action()
    except files.Conflict:
        return
    raise AssertionError("Expected ownership conflict, not successful overwrite/removal")


def check():
    print(f"ownership_test_evidence={evidence}", flush=True)
    existing = evidence / "existing"
    put(existing, b"unknown")
    try:
        files.OwnedFile(existing, b"ours", evidence / "existing-recovery")
        raise AssertionError("exclusive create overwrote existing input")
    except FileExistsError:
        pass
    assert existing.read_bytes() == b"unknown"
    print("OWNERSHIP_CASE_OK exclusive-create", flush=True)

    for case in ("resource-replaced", "receipt-replaced", "same-inode-content"):
        path, recovery = evidence / case, evidence / f"{case}-recovery"
        owned = files.OwnedFile(path, b"owned-original", recovery)
        if case == "same-inode-content":
            with path.open("wb") as stream:
                stream.write(b"unknown")
        else:
            replacement = evidence / f"{case}-replacement"
            put(replacement, b"unknown")
            os.replace(replacement, path)
        conflict(lambda: owned.write(b"must-not-overwrite"))
        conflict(owned.retire)
        assert path.read_bytes() == b"unknown"
        assert Path(str(recovery) + ".expected").read_bytes() == b"owned-original"
        print(f"OWNERSHIP_CASE_OK {case}", flush=True)

    receipt, original = evidence / "receipt", evidence / "receipt-original"
    other, other_original = evidence / "other", evidence / "other-original"
    put(receipt, b"original-receipt")
    put(other, b"other-original")
    borrowed = files.BorrowedFile(receipt, original)
    independent = files.BorrowedFile(other, other_original)
    mutated = files.OwnedFile(receipt, b"fake-receipt", evidence / "fake-receipt-recovery")
    replacement = evidence / "receipt-unknown"
    put(replacement, b"unknown-receipt")
    os.replace(replacement, receipt)
    conflict(lambda: files.restore_all(mutated.retire, borrowed.restore, independent.restore))
    assert receipt.read_bytes() == b"unknown-receipt" and original.read_bytes() == b"original-receipt"
    assert other.read_bytes() == b"other-original" and not other_original.exists()
    print("OWNERSHIP_CASE_OK conflict-preserves-original-and-attempts-all", flush=True)

    replaced, backup = evidence / "backup-replaced", evidence / "backup"
    put(replaced, b"original")
    borrowed = files.BorrowedFile(replaced, backup)
    replacement = evidence / "unknown-backup"
    put(replacement, b"unknown-backup")
    os.replace(replacement, backup)
    conflict(borrowed.restore)
    assert backup.read_bytes() == b"unknown-backup" and not replaced.exists()
    assert Path(str(backup) + ".original").read_bytes() == b"original"
    print("OWNERSHIP_CASE_OK backup-replacement-preserved", flush=True)

    # Inject a real raced destination just before real GNU mv; no fake move result.
    race = evidence / "race"
    recovery = evidence / "race-recovery"
    owned = files.OwnedFile(race, b"owned", recovery)
    bin_dir = evidence / "bin"
    bin_dir.mkdir()
    move = bin_dir / "mv"
    with move.open("x") as stream:
        stream.write('#!/bin/sh\nset -eu\nset -C\nprintf unknown-race > "$4"\nexec /usr/bin/mv "$@"\n')
    move.chmod(0o700)
    previous_path = os.environ["PATH"]
    try:
        os.environ["PATH"] = str(bin_dir) + os.pathsep + previous_path
        conflict(owned.retire)
    finally:
        os.environ["PATH"] = previous_path
    assert race.read_bytes() == b"owned" and recovery.read_bytes() == b"unknown-race"
    print("OWNERSHIP_CASE_OK raced-destination-no-overwrite", flush=True)


lock = ROOT.parents[1] / ".superpowers/sdd/2026-10-06-mazewall-integration-foundation-plan/verification.lock"
with lock.open("a") as held:
    fcntl.flock(held, fcntl.LOCK_EX)
    check()
