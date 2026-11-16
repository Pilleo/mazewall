#!/usr/bin/env python3
"""Validate the disjoint confined-helper integration-test lanes."""

import argparse
import json
import os
import pathlib
import stat
import sys
import xml.etree.ElementTree as ET

CLASSES = frozenset({
    "io.mazewall.enforcer.api.execution.ConfinedHelperReadinessIntegrationTest",
    "io.mazewall.ffi.networking.ConfinedHelperProcessIntegrationTest",
    "io.mazewall.landlock.OwnedProcFdReopenKernelTest",
})
MAX_XML = 16 * 1024 * 1024


def check_state(state: dict) -> None:
    if (state.get("Status") != "exited" or state.get("Running") is not False or
            state.get("Pid") != 0 or state.get("ExitCode") != 0 or
            state.get("OOMKilled") is not False or state.get("Dead") is not False or state.get("Error") != ""):
        raise ValueError("container did not retire successfully")


def check_host(config: dict, policy: dict) -> None:
    caps = {"CAP_CHECKPOINT_RESTORE", "CAP_SETUID", "CAP_SETGID", "CAP_SETPCAP"}
    if set(config.get("CapAdd") or []) != caps or set(config.get("CapDrop") or []) != {"ALL"}:
        raise ValueError("unexpected container capabilities")
    options = config.get("SecurityOpt") or []
    profiles = [json.loads(x[8:]) for x in options if x.startswith("seccomp=")]
    if len(options) != 2 or "no-new-privileges" not in options or profiles != [policy]:
        raise ValueError("unexpected applied seccomp or no-new-privileges")
    if (config.get("Privileged") is not False or config.get("Devices") or config.get("DeviceRequests") or
            config.get("PidMode") == "host" or config.get("IpcMode") == "host" or config.get("LxcConf") or
            config.get("Init") is not True):
        raise ValueError("unexpected privileged container configuration")
    if (config.get("UsernsMode") != "host" or config.get("NetworkMode") != "bridge" or
            config.get("ReadonlyRootfs") is not True or config.get("NanoCpus") != 2000000000 or
            config.get("Memory") != 8589934592 or config.get("PidsLimit") != 1024 or
            config.get("Tmpfs") != {"/tmp": "rw,nosuid,nodev,exec,size=1g"} or
            config.get("Ulimits") != [{"Name": "core", "Hard": 0, "Soft": 0}]):
        raise ValueError("unexpected container isolation or resources")


def check_identity(status: str, uid: int, gid: int) -> None:
    fields = dict(line.split(":", 1) for line in status.splitlines() if ":" in line)
    if fields.get("Uid", "").split() != [str(uid)] * 4 or fields.get("Gid", "").split() != [str(gid)] * 4:
        raise ValueError("unexpected workload identity")
    if fields.get("Groups", "").strip() or fields.get("NoNewPrivs", "").strip() != "1":
        raise ValueError("unexpected groups or privilege state")
    for field in ("CapInh", "CapPrm", "CapEff", "CapBnd", "CapAmb"):
        if fields.get(field, "").strip() != "0000010000000000":
            raise ValueError("unexpected workload capability mask")


def check_mounts(mounts: list[dict], root: str, gradle_home: str) -> None:
    expected = {(root, root, True), (gradle_home, "/container-gradle-home", True)}
    actual = {(item["Source"], item["Destination"], item["RW"]) for item in mounts if item["Type"] == "bind"}
    if len(mounts) != 2 or actual != expected:
        raise ValueError("unexpected container mounts")


def check_engine(security_options: list[str]) -> None:
    if any("rootless" in item.lower() for item in security_options):
        raise ValueError("rootless Docker is unsupported")


def check_namespace(host_namespace: str, workload_namespace: str, uid_map: str) -> None:
    if not host_namespace.startswith("user:[") or host_namespace != workload_namespace:
        raise ValueError("workload user namespace differs from host")
    if uid_map.split() != ["0", "0", "4294967295"]:
        raise ValueError("initial user namespace mapping required")


def check_ownership(paths: list[pathlib.Path], uid: int, recursive: bool) -> None:
    for path in paths:
        if path.lstat().st_uid != uid:
            raise ValueError(f"non-caller-owned output: {path}")
        if recursive and path.is_dir() and not path.is_symlink():
            for child in path.iterdir():
                check_ownership([child], uid, True)


def verify(mode: str, directory: pathlib.Path, since_ns: int | None = None, copy_to: pathlib.Path | None = None) -> None:
    if not directory.is_dir():
        raise ValueError("result directory is missing")
    paths = sorted(directory.glob("TEST-*.xml"))
    if not paths:
        raise ValueError("no test result XML")
    seen = set()
    retained = []
    for path in paths:
        descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(descriptor, "rb") as source:
            info = os.fstat(source.fileno())
            if not stat.S_ISREG(info.st_mode) or info.st_size > MAX_XML:
                raise ValueError(f"nonregular or oversized XML: {path.name}")
            data = source.read(MAX_XML + 1)
        if len(data) > MAX_XML:
            raise ValueError(f"oversized XML: {path.name}")
        root = ET.fromstring(data)
        if root.tag != "testsuite":
            raise ValueError(f"unexpected XML root: {path.name}")
        name = root.get("name")
        cases = root.findall("testcase")
        case_classes = {case.get("classname") for case in cases}
        if mode == "baseline":
            if name in CLASSES or case_classes & CLASSES:
                raise ValueError(f"confined class present in baseline: {path.name}")
            counts = {key: int(root.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
            if counts["tests"] <= 0 or len(cases) != counts["tests"] or counts["failures"] or counts["errors"]:
                raise ValueError(f"empty, inconsistent or failed baseline suite: {path.name}")
            if any(case.find(tag) is not None for case in cases for tag in ("failure", "error")):
                raise ValueError(f"failed baseline testcase: {path.name}")
            continue
        if since_ns is None or info.st_mtime_ns < since_ns:
            raise ValueError(f"stale test result: {path.name}")
        if name not in CLASSES or name in seen or case_classes != {name}:
            raise ValueError(f"foreign, duplicate or ambiguous suite: {path.name}")
        seen.add(name)
        retained.append((path.name, data))
        counts = {key: int(root.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
        if counts["tests"] <= 0 or len(cases) != counts["tests"]:
            raise ValueError(f"missing or inconsistent cases: {path.name}")
        if any(counts[key] for key in ("failures", "errors", "skipped")):
            raise ValueError(f"failed or skipped cases: {path.name}")
        if any(case.find(tag) is not None for case in cases for tag in ("failure", "error", "skipped")):
            raise ValueError(f"failed or skipped testcase: {path.name}")
        names = [case.get("name") for case in cases]
        if any(not name for name in names) or len(names) != len(set(names)):
            raise ValueError(f"ambiguous testcase identity: {path.name}")
    if mode == "confined" and seen != CLASSES:
        raise ValueError("missing confined suite: " + ", ".join(sorted(CLASSES - seen)))
    if mode == "confined" and copy_to is not None:
        copy_to.mkdir()
        for filename, data in retained:
            with (copy_to / filename).open("xb") as destination:
                destination.write(data)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("baseline", "confined", "host", "state", "identity", "mounts", "engine", "namespace", "ownership"))
    parser.add_argument("--results", type=pathlib.Path)
    parser.add_argument("--since-ns", type=int)
    parser.add_argument("--copy-to", type=pathlib.Path)
    parser.add_argument("--input", type=pathlib.Path)
    parser.add_argument("--policy", type=pathlib.Path)
    parser.add_argument("--uid", type=int)
    parser.add_argument("--gid", type=int)
    parser.add_argument("--root")
    parser.add_argument("--gradle-home")
    parser.add_argument("--host-namespace", type=pathlib.Path)
    parser.add_argument("--uid-map", type=pathlib.Path)
    parser.add_argument("--recursive", action="store_true")
    args = parser.parse_args()
    try:
        if args.mode in ("baseline", "confined"):
            if args.results is None:
                raise ValueError("results path required")
            verify(args.mode, args.results, args.since_ns, args.copy_to)
        elif args.mode == "host":
            check_host(json.loads(args.input.read_text()), json.loads(args.policy.read_text()))
        elif args.mode == "state":
            check_state(json.loads(args.input.read_text()))
        elif args.mode == "identity":
            check_identity(args.input.read_text(), args.uid, args.gid)
        elif args.mode == "mounts":
            check_mounts(json.loads(args.input.read_text()), args.root, args.gradle_home)
        elif args.mode == "engine":
            check_engine(json.loads(args.input.read_text()))
        elif args.mode == "namespace":
            check_namespace(args.host_namespace.read_text().strip(), args.input.read_text().strip(), args.uid_map.read_text())
        else:
            check_ownership([args.input], args.uid, args.recursive)
    except (ValueError, KeyError, TypeError, AttributeError, ET.ParseError, OSError) as failure:
        print(f"confined-helper results rejected: {failure}", file=sys.stderr)
        return 1
    print(f"confined-helper {args.mode} results verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
