#!/usr/bin/env python3
"""Content-address Tier E split-benchmark source inputs before and after forks."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import stat
import subprocess


INPUTS = (
    "build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew",
    "buildSrc", "config/detekt", "enforcer/src", "platform/src", "profiler/src",
    "profiler-native", "scripts", "infra/dev/Containerfile",
)
MAX_FILE_BYTES = 32 * 1024 * 1024


def git(root, *args):
    return subprocess.check_output(["git", "-C", str(root), *args])


def fingerprint(root):
    root = root.resolve()
    revision = git(root, "rev-parse", "HEAD").decode("ascii").strip()
    listed = git(root, "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", *INPUTS)
    paths = sorted(set(os.fsdecode(name) for name in listed.split(b"\0") if name))
    aggregate = hashlib.sha256()
    files = {}
    for name in paths:
        path = root / name
        metadata = path.lstat() if path.exists() or path.is_symlink() else None
        if metadata is None:
            contents = b"MISSING"
            kind = "missing"
        elif stat.S_ISLNK(metadata.st_mode):
            contents = os.fsencode(os.readlink(path))
            kind = "symlink"
        elif stat.S_ISREG(metadata.st_mode):
            if metadata.st_size > MAX_FILE_BYTES:
                raise ValueError(f"source input exceeds {MAX_FILE_BYTES} bytes: {name}")
            contents = path.read_bytes()
            kind = "executable" if metadata.st_mode & 0o111 else "regular"
        else:
            raise ValueError(f"unsupported source input type: {name}")
        digest = hashlib.sha256(contents).hexdigest()
        files[name] = {"kind": kind, "sha256": digest}
        aggregate.update(name.encode("utf-8", "surrogateescape"))
        aggregate.update(b"\0")
        aggregate.update(kind.encode("ascii"))
        aggregate.update(b"\0")
        aggregate.update(digest.encode("ascii"))
        aggregate.update(b"\n")
    dirty = git(root, "status", "--porcelain", "--untracked-files=normal", "--", *INPUTS)
    return {"schema_version": 1, "revision": revision, "source_digest": aggregate.hexdigest(),
            "source_file_count": len(files), "input_dirty_count": len(dirty.splitlines()), "files": files}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repository", type=Path)
    parser.add_argument("--verify", type=Path)
    args = parser.parse_args()
    current = fingerprint(args.repository)
    if args.verify:
        previous = json.loads(args.verify.read_text())
        current["unchanged"] = previous.get("source_digest") == current["source_digest"] and previous.get("revision") == current["revision"]
    print(json.dumps(current, sort_keys=True))
    if args.verify and not current["unchanged"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
