#!/usr/bin/env python3
"""Bounded host-PID reuse probe; never changes host PID allocation controls."""

import argparse
import json
import pathlib
import platform
import subprocess
import time


def starttime(stat_line):
    end = stat_line.rfind(")")
    if end < 0:
        raise ValueError("malformed proc stat")
    fields = stat_line[end + 1:].strip().split()
    if len(fields) < 20:
        raise ValueError("truncated proc stat")
    return int(fields[19])


def observe(pid):
    return starttime(pathlib.Path(f"/proc/{pid}/stat").read_text())


def run(attempts, deadline_seconds):
    original = subprocess.Popen(["/bin/sleep", "0.05"])
    try:
        identity = {"pid": original.pid, "starttime": observe(original.pid)}
    finally:
        original.wait(timeout=2)
    deadline = time.monotonic() + deadline_seconds
    attempted = 0
    observed = None
    while attempted < attempts and time.monotonic() < deadline:
        next_process = subprocess.Popen(["/bin/sleep", "0.01"])
        try:
            candidate_start = observe(next_process.pid)
            attempted += 1
            if next_process.pid == identity["pid"] and candidate_start != identity["starttime"]:
                observed = {"pid": next_process.pid, "starttime": candidate_start}
                break
        finally:
            next_process.wait(timeout=2)
    return {"schema_version": 1, "kernel": platform.release(),
            "pid_max": int(pathlib.Path("/proc/sys/kernel/pid_max").read_text()),
            "ns_last_pid": int(pathlib.Path("/proc/sys/kernel/ns_last_pid").read_text()),
            "original": identity, "attempts": attempted, "replacement": observed,
            "host_pid_reuse_verdict": "OBSERVED" if observed else "UNVERIFIED",
             "policy_eligibility": False,
             "probe_contract": "real host PIDs and /proc starttime; no sysctl or ns_last_pid mutation"}


def run_for_identity(identity, attempts, deadline_seconds, output, hold_seconds):
    """Publish a replacement while it is alive, so a paused collector can inspect it."""
    deadline = time.monotonic() + deadline_seconds
    attempted = 0
    observed = None
    while attempted < attempts and time.monotonic() < deadline:
        candidate = subprocess.Popen(["/bin/sleep", "60"])
        try:
            candidate_start = observe(candidate.pid)
            attempted += 1
            if candidate.pid == identity["pid"] and candidate_start != identity["starttime"]:
                observed = {"pid": candidate.pid, "starttime": candidate_start}
                break
        finally:
            if observed is None:
                candidate.terminate()
                candidate.wait(timeout=2)
    try:
        result = {"schema_version": 1, "kernel": platform.release(),
                  "pid_max": int(pathlib.Path("/proc/sys/kernel/pid_max").read_text()),
                  "ns_last_pid": int(pathlib.Path("/proc/sys/kernel/ns_last_pid").read_text()),
                  "original": identity, "attempts": attempted, "replacement": observed,
                  "host_pid_reuse_verdict": "OBSERVED" if observed else "UNVERIFIED",
                  "policy_eligibility": False,
                  "probe_contract": "real guest host PIDs and /proc starttime; registered target already exited"}
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(result, indent=2) + "\n")
        if observed is not None:
            time.sleep(hold_seconds)
    finally:
        if observed is not None:
            candidate.terminate()
            candidate.wait(timeout=2)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--max-attempts", type=int, default=256)
    parser.add_argument("--deadline-seconds", type=float, default=10)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--registered-pid", type=int)
    parser.add_argument("--registered-starttime", type=int)
    parser.add_argument("--hold-seconds", type=float, default=0)
    args = parser.parse_args()
    if args.max_attempts <= 0 or args.deadline_seconds <= 0 or not 0 <= args.hold_seconds < 60:
        parser.error("attempts/deadline must be positive and hold must be between 0 and 60 seconds")
    if (args.registered_pid is None) != (args.registered_starttime is None):
        parser.error("registered PID and starttime must be provided together")
    if args.registered_pid is not None:
        if args.registered_pid <= 0 or args.registered_starttime <= 0:
            parser.error("registered identity must be positive")
        result = run_for_identity({"pid": args.registered_pid, "starttime": args.registered_starttime},
                                  args.max_attempts, args.deadline_seconds, args.output, args.hold_seconds)
    else:
        result = run(args.max_attempts, args.deadline_seconds)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
