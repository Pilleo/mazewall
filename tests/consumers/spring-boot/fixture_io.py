"""Exclusive private-fixture file ownership and evidence-preserving owned-child execution."""
from contextlib import contextmanager, ExitStack
import json
import os
from pathlib import Path
import stat
import subprocess


def retain(primary, cleanup):
    if primary is None:
        return cleanup
    if cleanup is not primary:
        primary.cleanup_failures = (*getattr(primary, "cleanup_failures", ()), cleanup)
        primary.add_note(f"Independent cleanup failure: {cleanup!r}")
    return primary


class OwnedFile:
    """A genuine exclusive acquisition, not a token minted around an existing pathname."""
    def __init__(self, path, content=b""):
        self.path = Path(path)
        # Fixture directories are private, stable and owned; reject even leaf aliases.
        for parent in [self.path.parent, *self.path.parent.parents]:
            if parent.is_symlink():
                raise OSError(f"Fixture directory alias: {parent}")
        self.fd = os.open(self.path, os.O_CREAT | os.O_EXCL | os.O_RDWR | os.O_NOFOLLOW | os.O_CLOEXEC, 0o600)
        try:
            self.identity = os.fstat(self.fd)
            self.write(content)
        except BaseException as primary:
            try:
                os.close(self.fd)
            except BaseException as cleanup:
                retain(primary, cleanup)
            self.fd = None
            raise

    def validate(self):
        actual = os.fstat(self.fd)
        named = self.path.lstat()
        if (not stat.S_ISREG(actual.st_mode) or actual.st_nlink != 1 or
                (actual.st_dev, actual.st_ino) != (self.identity.st_dev, self.identity.st_ino) or
                not stat.S_ISREG(named.st_mode) or (named.st_dev, named.st_ino) != (actual.st_dev, actual.st_ino)):
            raise OSError(f"Refusing replaced/symlink/hardlink fixture file: {self.path}")

    def read(self):
        self.validate()
        # pread cannot move an inherited child's stdout/stderr file offset.
        size = os.fstat(self.fd).st_size
        offset = 0
        chunks = []
        while offset < size:
            chunk = os.pread(self.fd, min(65536, size - offset), offset)
            if not chunk:
                raise OSError(f"Owned file shrank during snapshot: {self.path}")
            chunks.append(chunk)
            offset += len(chunk)
        return b"".join(chunks)

    def write(self, content):
        self.validate()
        os.lseek(self.fd, 0, os.SEEK_SET)
        remaining = memoryview(content)
        while remaining:
            size = os.write(self.fd, remaining)
            remaining = remaining[size:]
        os.ftruncate(self.fd, len(content))
        os.fsync(self.fd)

    def close(self):
        if self.fd is not None:
            fd, self.fd = self.fd, None
            os.close(fd)

    def __enter__(self):
        return self

    def __exit__(self, kind, primary, traceback):
        try:
            self.close()
        except BaseException as cleanup:
            if primary is None:
                raise
            retain(primary, cleanup)
        return False


@contextmanager
def restoring(*owners):
    snapshots = [(owner, owner.read()) for owner in owners]
    primary = None
    try:
        yield
    except BaseException as failure:
        primary = failure
    finally:
        # One failure never prevents another independent original from being restored.
        for owner, original in snapshots:
            try:
                owner.write(original)
            except BaseException as cleanup:
                primary = retain(primary, cleanup)
    if primary is not None:
        raise primary


def run_child(command, root, label, timeout=90, termination_grace=1):
    """Persist before launch/termination; signal and reap only this Popen-owned child."""
    root = Path(root)
    with ExitStack() as files:
        stdout = files.enter_context(OwnedFile(root / f"{label}.stdout.log"))
        stderr = files.enter_context(OwnedFile(root / f"{label}.stderr.log"))
        combined = files.enter_context(OwnedFile(root / f"{label}.log"))
        metadata = files.enter_context(OwnedFile(root / f"{label}.command.json"))
        state = {"argv": command, "cwd": str(root), "pid": None, "exitCode": None,
                 "timedOut": False, "reaped": False, "state": "launching", "transitions": ["launching"]}

        def persist(phase):
            state["state"] = phase
            state["transitions"].append(phase)
            combined.write(stdout.read() + stderr.read())
            metadata.write(json.dumps(state, indent=2).encode())

        persist("before-launch")
        child = None
        primary = None
        try:
            child = subprocess.Popen(command, cwd=root, stdout=stdout.fd, stderr=stderr.fd)
            state["pid"] = child.pid
            persist("running")
            try:
                child.wait(timeout=timeout)
            except subprocess.TimeoutExpired as failure:
                primary = failure
                state["timedOut"] = True
                # Real stream files already contain partial stdout/stderr. Record timeout
                # before sending any signal; cleanup never targets a process group/stranger.
                persist("timed-out")
        except BaseException as failure:
            primary = retain(primary, failure)
        finally:
            if child is not None:
                try:
                    if child.poll() is None:
                        child.terminate()
                        try:
                            child.wait(timeout=termination_grace)
                        except subprocess.TimeoutExpired:
                            child.kill()
                            child.wait(timeout=termination_grace)
                    else:
                        child.wait()
                    state["exitCode"] = child.returncode
                    state["reaped"] = True
                except BaseException as cleanup:
                    primary = retain(primary, cleanup)
                    # Independent last attempt on the same genuine owned child only.
                    try:
                        if child.poll() is None:
                            child.kill()
                        child.wait(timeout=termination_grace)
                        state["exitCode"] = child.returncode
                        state["reaped"] = True
                    except BaseException as final_cleanup:
                        primary = retain(primary, final_cleanup)
            try:
                persist("reaped" if state["reaped"] else "launch-failed")
            except BaseException as evidence_failure:
                primary = retain(primary, evidence_failure)
        if primary is not None:
            raise primary
        return subprocess.CompletedProcess(command, child.returncode,
            stdout.read().decode(errors="replace"), stderr.read().decode(errors="replace"))
