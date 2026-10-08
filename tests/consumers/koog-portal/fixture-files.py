"""Exclusive, inode-owned test mutations; conflicts and recovery files are retained."""
import os
from pathlib import Path
import subprocess


class Conflict(RuntimeError):
    pass


def restore_all(*actions):
    errors = []
    for action in actions:
        try:
            action()
        except BaseException as error:
            errors.append(str(error))
    if errors:
        raise Conflict("; ".join(errors))


def identity(path):
    stat = Path(path).lstat()
    return stat.st_dev, stat.st_ino


def move_owned(source, target, owned):
    source, target = Path(source), Path(target)
    if not source.exists() or identity(source) != owned or target.exists():
        raise Conflict(f"Preserve conflicting source/destination: {source} recovery={target}")
    if source.lstat().st_dev != target.parent.stat().st_dev:
        raise Conflict(f"Cross-filesystem move rejected before effect: {source} recovery={target}")
    # Exact, no-clobber rename. Check postconditions even when GNU mv returns0
    # after declining a raced destination. Never unlink a pathname after a stat.
    result = subprocess.run(["mv", "-T", "--no-clobber", str(source), str(target)], check=False)
    if result.returncode or source.exists() or not target.exists() or identity(target) != owned:
        if target.exists() and identity(target) != owned and not source.exists():
            subprocess.run(["mv", "-T", "--no-clobber", str(target), str(source)], check=False)
        raise Conflict(f"Move conflict preserved: {source} recovery={target} status={result.returncode}")


class OwnedFile:
    def __init__(self, path, data, recovery):
        self.path, self.recovery = Path(path), Path(recovery)
        self.fd = os.open(self.path, os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        stat = os.fstat(self.fd)
        self.owned = stat.st_dev, stat.st_ino
        self.expected = b""
        try:
            self.write(data)
        except BaseException:
            self.close()
            raise

    def validate(self):
        if not self.path.exists() or identity(self.path) != self.owned:
            raise Conflict(f"Unknown replacement preserved: {self.path}; owned recovery={self.recovery}")
        if os.pread(self.fd, os.fstat(self.fd).st_size, 0) != self.expected:
            raise Conflict(f"Unknown content preserved: {self.path}; owned recovery={self.recovery}")

    def write(self, data):
        self.validate()
        data = data.encode() if isinstance(data, str) else data
        # Mutate the retained descriptor, never reopen/truncate a replaced pathname.
        offset = 0
        while offset < len(data):
            offset += os.pwrite(self.fd, data[offset:], offset)
        os.ftruncate(self.fd, len(data))
        self.expected = data

    def retire(self):
        try:
            self.validate()
            move_owned(self.path, self.recovery, self.owned)
        except BaseException:
            # Recover even an unlinked owned inode; exclusive creation cannot
            # overwrite another actor's file. Keep both actual and expected bytes.
            for suffix, data in ((".owned", os.pread(self.fd, os.fstat(self.fd).st_size, 0)),
                                 (".expected", self.expected)):
                with Path(str(self.recovery) + suffix).open("xb") as stream:
                    stream.write(data)
            raise
        finally:
            self.close()

    def close(self):
        if self.fd is not None:
            os.close(self.fd)
            self.fd = None


class BorrowedFile:
    def __init__(self, path, recovery):
        self.path, self.recovery = Path(path), Path(recovery)
        fd = os.open(self.path, os.O_RDONLY | os.O_NOFOLLOW)
        try:
            stat = os.fstat(fd)
            self.owned = stat.st_dev, stat.st_ino
            self.data = os.pread(fd, stat.st_size, 0)
            move_owned(self.path, self.recovery, self.owned)
        except BaseException:
            if hasattr(self, "data"):
                with Path(str(self.recovery) + ".original").open("xb") as stream:
                    stream.write(self.data)
            raise
        finally:
            os.close(fd)

    def restore(self):
        try:
            if self.recovery.read_bytes() != self.data:
                raise Conflict(f"Changed original backup preserved: {self.recovery}; destination={self.path}")
            move_owned(self.recovery, self.path, self.owned)
        except BaseException:
            with Path(str(self.recovery) + ".original").open("xb") as stream:
                stream.write(self.data)
            raise
