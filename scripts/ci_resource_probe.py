"""Measure Linux memory evidence while running one owned command."""

from collections.abc import Callable, Sequence
import argparse
import ctypes
from dataclasses import dataclass, field
from enum import Enum
import errno
import json
import os
from pathlib import Path, PurePosixPath
import re
import signal
import subprocess
import sys
import time


_PROC = Path("/proc")
_RESERVE_BYTES = 2147483648


@dataclass(frozen=True)
class MemorySample:
    headroom_bytes: int | None
    peak_headroom_bytes: int | None
    oom_kills: int | None
    _metadata: dict = field(default_factory=dict, repr=False, compare=False)


class MemoryVerdict(Enum):
    PASS = "pass"
    UNSAFE = "unsafe"
    UNKNOWN = "unknown"


def evaluate_samples(samples: Sequence[MemorySample]) -> MemoryVerdict:
    if not samples:
        return MemoryVerdict.UNKNOWN
    for sample in samples:
        bounds = (
            sample.headroom_bytes,
            sample.peak_headroom_bytes,
            sample._metadata.get("known_headroom_bytes"),
            sample._metadata.get("known_peak_headroom_bytes"),
        )
        if any(value is not None and value < _RESERVE_BYTES for value in bounds):
            return MemoryVerdict.UNSAFE
    delta = _oom_delta(samples)
    if delta is not None and delta > 0:
        return MemoryVerdict.UNSAFE
    if delta is None or any(
        sample.headroom_bytes is None
        or sample.peak_headroom_bytes is None
        or sample.oom_kills is None
        or sample.oom_kills < 0
        for sample in samples
    ):
        return MemoryVerdict.UNKNOWN
    return MemoryVerdict.PASS


def _oom_delta(samples: Sequence[MemorySample]) -> int | None:
    if not samples:
        return None
    identity = samples[0]._metadata.get("cgroup_identity")
    if any(sample._metadata.get("cgroup_identity") != identity for sample in samples):
        return None
    first, last = samples[0].oom_kills, samples[-1].oom_kills
    if first is None or last is None or first < 0 or last < first:
        return None
    counters = [sample.oom_kills for sample in samples if sample.oom_kills is not None]
    if any(later < earlier for earlier, later in zip(counters, counters[1:])):
        return None
    return last - first


def _read(path: Path) -> dict:
    try:
        return {"raw": path.read_text(encoding="utf-8"), "error": None}
    except (OSError, UnicodeError) as error:
        return {"raw": None, "error": str(error), "errno": getattr(error, "errno", None)}


def _number(text: str) -> int | None:
    return int(text) if re.fullmatch(r"[0-9]+", text) else None


def _counter(path: Path) -> dict:
    record = _read(path)
    text = (record["raw"] or "").strip()
    if path.name == "memory.events":
        entries = [line.split() for line in text.splitlines() if line.split()[:1] == ["oom_kill"]]
        value = _number(entries[0][1]) if len(entries) == 1 and len(entries[0]) == 2 else None
    elif path.name == "memory.max" and text == "max":
        value = "max"
    else:
        value = _number(text)
    record["value"] = value
    if value is None and record["error"] is None:
        record["error"] = f"malformed {path.name}"
    return record


def _mount_path(text: str) -> PurePosixPath:
    decoded = re.sub(r"\\([0-7]{3})", lambda match: chr(int(match[1], 8)), text)
    path = PurePosixPath(decoded)
    if not path.is_absolute() or ".." in path.parts:
        raise ValueError("non-absolute or traversing cgroup path")
    return path


def _cgroup_paths(membership: str, mountinfo: str) -> tuple[list[Path], list[str]]:
    members = [line[3:] for line in membership.splitlines() if line.startswith("0::")]
    if len(members) != 1:
        raise ValueError("missing or ambiguous cgroup-v2 membership")
    group = PurePosixPath(members[0])
    if not group.is_absolute() or ".." in group.parts:
        raise ValueError("non-absolute or traversing cgroup membership")
    mounts = []
    for line in mountinfo.splitlines():
        before, separator, after = line.partition(" - ")
        if separator and after.split()[:1] == ["cgroup2"]:
            fields = before.split()
            if len(fields) < 6:
                raise ValueError("malformed cgroup-v2 mountinfo")
            root, mount = _mount_path(fields[3]), _mount_path(fields[4])
            if group.is_relative_to(root):
                mounts.append((root, mount))
    if not mounts:
        raise ValueError("no visible cgroup-v2 mount matches membership")
    # Prefer the broadest visible mount so bind mounts cannot hide an ancestor limit.
    root, mount = min(mounts, key=lambda item: len(item[0].parts))
    leaf = Path(mount / group.relative_to(root))
    paths = [leaf]
    while paths[-1] != Path(mount):
        paths.append(paths[-1].parent)
    return paths, [str(group), str(root), str(mount)]


def collect_sample() -> MemorySample:
    host = _read(_PROC / "meminfo")
    available = re.findall(r"(?m)^MemAvailable:[ \t]+([0-9]+)[ \t]+kB[ \t]*$", host["raw"] or "")
    host_bytes = int(available[0]) * 1024 if len(available) == 1 else None
    host["mem_available_bytes"] = host_bytes
    if host_bytes is None and host["error"] is None:
        host["error"] = "missing or malformed MemAvailable"
    membership = _read(_PROC / "self/cgroup")
    mounts = _read(_PROC / "self/mountinfo")
    # Retain only relevant mount lines; no environment or credential inspection.
    if mounts["raw"] is not None:
        mounts["raw"] = "\n".join(
            line for line in mounts["raw"].splitlines() if " - cgroup2 " in line
        )
    metadata = {"host": host, "membership": membership, "mountinfo": mounts, "cgroups": []}
    current_bounds = [] if host_bytes is None else [host_bytes]
    peak_bounds = list(current_bounds)
    current_complete = peak_complete = host_bytes is not None
    oom_counters = []
    try:
        paths, identity = _cgroup_paths(membership["raw"] or "", mounts["raw"] or "")
        metadata["cgroup_identity"] = identity
    except ValueError as error:
        metadata["cgroup_error"] = str(error)
        paths = []
        current_complete = peak_complete = False
    for path in paths:
        counters = {
            name: _counter(path / name)
            for name in ("memory.max", "memory.current", "memory.peak", "memory.events")
        }
        not_applicable = (
            path == paths[-1]
            and identity[1] == "/"
            and all(counter.get("errno") == errno.ENOENT for counter in counters.values())
        )
        metadata["cgroups"].append(
            {"path": str(path), "status": "not_applicable" if not_applicable else "controller", "counters": counters}
        )
        if not_applicable:
            continue
        limit = counters["memory.max"]["value"]
        current = counters["memory.current"]["value"]
        peak = counters["memory.peak"]["value"]
        current_complete &= limit is not None and current is not None
        peak_complete &= limit is not None and peak is not None
        if isinstance(limit, int):
            if current is not None:
                current_bounds.append(limit - current)
            if peak is not None:
                peak_bounds.append(limit - peak)
        oom_counters.append(counters["memory.events"]["value"])
    metadata["known_headroom_bytes"] = min(current_bounds, default=None)
    metadata["known_peak_headroom_bytes"] = min(peak_bounds, default=None)
    # memory.events is hierarchical: the outermost counter covers descendants once.
    oom = oom_counters[-1] if oom_counters and all(value is not None for value in oom_counters) else None
    if re.search(r"[, ]memory_localevents(?:[, \n]|$)", mounts["raw"] or ""):
        # shortcut: memory_localevents remains unqualified; add per-ancestor deltas if needed.
        metadata["oom_error"] = "memory_localevents counters are not hierarchical"
        oom = None
    return MemorySample(
        min(current_bounds) if current_complete else None,
        min(peak_bounds) if peak_complete else None,
        oom,
        metadata,
    )


def _reason(verdict: MemoryVerdict, samples: Sequence[MemorySample]) -> str:
    if verdict is MemoryVerdict.PASS:
        return "observed headroom meets 2147483648-byte reserve; no additional OOM kills"
    if verdict is MemoryVerdict.UNSAFE:
        delta = _oom_delta(samples)
        if delta is not None and delta > 0:
            return f"OOM kill counter increased by {delta}"
        return "observed or cumulative-peak headroom below 2147483648 bytes; peaks may predate command"
    return "memory evidence missing, malformed, reset, or changed; hosted qualification required"


def _receipt(command, exit_code, elapsed, samples, verdict, reason):
    peaks = {}
    for index, sample in enumerate(samples):
        for group in sample._metadata.get("cgroups", []):
            if group["status"] == "not_applicable":
                continue
            peak = group["counters"]["memory.peak"]["value"]
            entry = peaks.setdefault(group["path"], {
                "path": group["path"],
                "initial_bytes": peak if index == 0 else None,
                "final_bytes": None,
                "maximum_bytes": None,
                "attribution": "cumulative; may predate command or include other processes; not reset",
            })
            if peak is not None:
                entry["maximum_bytes"] = max(peak, entry["maximum_bytes"] if entry["maximum_bytes"] is not None else peak)
            if index == len(samples) - 1:
                entry["final_bytes"] = peak
    headrooms = [sample.headroom_bytes for sample in samples]
    return {
        "schema": 1,
        "argv": list(command),
        "command_exit_code": exit_code,
        "elapsed_seconds": elapsed,
        "minimum_headroom_bytes": min(headrooms) if headrooms and all(value is not None for value in headrooms) else None,
        "cgroup_peaks": list(peaks.values()),
        "oom_kill_delta": _oom_delta(samples),
        "qualification": verdict.value,
        "reason": reason,
        "samples": [
            {
                "headroom_bytes": sample.headroom_bytes,
                "peak_headroom_bytes": sample.peak_headroom_bytes,
                "oom_kills": sample.oom_kills,
                "metadata": sample._metadata,
            }
            for sample in samples
        ],
    }


def _subreaper(value: int | None = None) -> int:
    prctl = ctypes.CDLL(None, use_errno=True).prctl
    prctl.argtypes = [ctypes.c_int, *([ctypes.c_ulong] * 4)]
    prctl.restype = ctypes.c_int
    previous = ctypes.c_int()
    # Linux PR_GET_CHILD_SUBREAPER / PR_SET_CHILD_SUBREAPER, confined to this CLI.
    result = prctl(37 if value is None else 36, ctypes.addressof(previous) if value is None else value, 0, 0, 0)
    if result == -1:
        code = ctypes.get_errno()
        raise OSError(code, os.strerror(code))
    return previous.value if value is None else value


def _acquire_ownership() -> int:
    # A fresh, single-threaded CLI has no other descendants to adopt or reap.
    if len(list(Path("/proc/self/task").iterdir())) != 1 or signal.getsignal(signal.SIGCHLD) != signal.SIG_DFL:
        raise OSError("owned lifetime requires a single thread and default SIGCHLD")
    Path("/proc/thread-self/children").read_text(encoding="ascii")
    try:
        os.waitid(os.P_ALL, 0, os.WEXITED | os.WNOHANG | os.WNOWAIT)
    except ChildProcessError:
        pass
    else:
        raise OSError("owned lifetime refuses pre-existing children")
    # Probe every required kernel primitive before allowing any command to launch.
    descriptor = os.pidfd_open(os.getpid())
    try:
        signal.pidfd_send_signal(descriptor, 0)
        try:
            os.waitid(os.P_PIDFD, descriptor, os.WEXITED | os.WNOHANG)
        except ChildProcessError:
            pass
    finally:
        os.close(descriptor)
    previous = _subreaper()
    _subreaper(1)
    return previous


def _retire(child: subprocess.Popen, signum: int, sample: Callable[[], None]) -> str | None:
    descriptors = {}
    killed = set()
    started = time.monotonic()
    deadline = started + 3
    next_sample = started + 1
    try:
        sample()  # Observe the primary's tail before signalling descendants.
        while True:
            # Drain direct roots and newly adopted orphans, not racy ancestry
            # snapshots; setsid/double-forks from any JVM thread remain owned.
            for text in Path("/proc/thread-self/children").read_text(encoding="ascii").split():
                pid = int(text)
                if pid not in descriptors:
                    # No other thread or SIGCHLD handler can reap/reuse this PID.
                    descriptors[pid] = os.pidfd_open(pid)
                    try:
                        signal.pidfd_send_signal(descriptors[pid], signum)
                    except ProcessLookupError:
                        pass
            for pid, descriptor in list(descriptors.items()):
                if time.monotonic() >= deadline and pid not in killed:
                    try:
                        signal.pidfd_send_signal(descriptor, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    killed.add(pid)
                if child.returncode is None and pid == child.pid:
                    exited = child.poll() is not None
                else:
                    exited = os.waitid(os.P_PIDFD, descriptor, os.WEXITED | os.WNOHANG) is not None
                if exited:
                    os.close(descriptor)
                    del descriptors[pid]
                    killed.discard(pid)
            try:
                os.waitid(os.P_ALL, 0, os.WEXITED | os.WNOHANG | os.WNOWAIT)
            except ChildProcessError:
                return None
            if time.monotonic() >= deadline + 1:
                return "owned descendants did not retire within cleanup bound"
            if time.monotonic() >= next_sample:
                sample()
                next_sample += 1
            time.sleep(0.02)
    except (OSError, ValueError) as error:
        return f"owned descendant cleanup failed: {error}"
    finally:
        for descriptor in descriptors.values():
            os.close(descriptor)


def main(argv: Sequence[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("a command after -- is required")

    started = time.monotonic()
    samples = []
    interrupted = 0
    child = None
    ownership = None
    exit_code = None
    result = 1

    def on_signal(signum, _frame):
        nonlocal interrupted
        if not interrupted:
            interrupted = signum

    def sample():
        try:
            samples.append(collect_sample())
        except Exception as error:
            samples.append(MemorySample(None, None, None, {"collection_error": str(error)}))

    def save(verdict, reason):
        try:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(_receipt(
                command, exit_code, time.monotonic() - started, samples, verdict, reason
            ), indent=2) + "\n", encoding="utf-8")
            return True
        except OSError as error:
            print(f"ci_resource_probe: cannot write receipt: {error}", file=sys.stderr)
            return False

    handlers = {signum: signal.signal(signum, on_signal) for signum in (signal.SIGINT, signal.SIGTERM)}
    try:
        sample()
        verdict = evaluate_samples(samples)
        reason = _reason(verdict, samples)
        if verdict is MemoryVerdict.UNSAFE:
            reason = "command not launched: " + reason
        elif not save(MemoryVerdict.UNKNOWN, "command not launched yet; evidence incomplete"):
            return 1
        elif not interrupted:
            try:
                ownership = _acquire_ownership()
                if not interrupted:
                    child = subprocess.Popen(command, start_new_session=True)
            except (OSError, AttributeError) as error:
                result = 127 if ownership is not None and getattr(error, "errno", None) == errno.ENOENT else 126
                verdict = MemoryVerdict.UNKNOWN
                reason = f"command could not launch with owned lifetime: {error}"
            else:
                cleanup_error = None
                try:
                    while child is not None and not interrupted and child.poll() is None:
                        try:
                            child.wait(timeout=1)
                        except subprocess.TimeoutExpired:
                            if not interrupted:
                                sample()
                finally:
                    if child is not None:
                        cleanup_error = _retire(child, interrupted or signal.SIGTERM, sample)
                code = child.returncode if child is not None else None
                exit_code = None if code is None else (128 - code if code < 0 else code)
                sample()
                verdict = evaluate_samples(samples)
                reason = _reason(verdict, samples)
                result = exit_code if exit_code else (0 if verdict is MemoryVerdict.PASS else 1)
                if cleanup_error:
                    reason += "; " + cleanup_error
                    result = result or 1
                if exit_code:
                    reason = f"command exited {exit_code}; " + reason
            finally:
                if ownership is not None:
                    try:
                        _subreaper(ownership)
                    except OSError as error:
                        reason += f"; cannot restore child subreaper: {error}"
                        result = result or 1
        while True:
            handled_signal = interrupted
            if handled_signal:
                result = 128 + handled_signal
                if exit_code is None:
                    verdict = MemoryVerdict.UNKNOWN
                reason = f"interrupted by {signal.Signals(handled_signal).name}; " + reason
            if not save(verdict, reason):
                result = result or 1
            # A signal during publication still needs a cancellation receipt.
            if interrupted == handled_signal:
                return result
    finally:
        for signum, handler in handlers.items():
            signal.signal(signum, handler)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
