"""Filter external perf samples using buffered CLOCK_MONOTONIC workload windows.

Usage: python3 scripts/analyze_tier_e_perf_windows.py ARTIFACT_DIRECTORY
Exit-time maps are incomplete temporal symbol evidence; named frames alone are
never sufficient for a claim that all overhead is attributed.
"""

import bisect
import collections
import json
import pathlib
import re
import sys


WINDOW = re.compile(r"tier-e-perf-window: pid=(\d+) phase=measured index=(\d+) start_ns=(\d+) end_ns=(\d+) clock=monotonic")
HEADER = re.compile(r"^\s*\S+\s+(\d+)/(\d+)\s+(\d+)\.(\d+):")


def lines(path):
    with path.open() as source:
        yield from source


def analyze(root):
    roles = {}
    for line in lines(root / "results.jsonl"):
        row = json.loads(line)
        if row.get("kind") != "fork-output":
            continue
        output = row.get("output", "")
        windows = [(int(p), int(s), int(e)) for p, _, s, e in WINDOW.findall(output)]
        if not windows:
            raise ValueError(f"Missing measured windows: {row['variant']} fork {row['fork']}")
        pids = {p for p, _, _ in windows}
        if len(pids) != 1:
            raise ValueError("Multiple workload PIDs in one fork")
        spans = sorted((s, e) for _, s, e in windows)
        if any(e <= s for s, e in spans) or any(a[1] > b[0] for a, b in zip(spans, spans[1:])):
            raise ValueError("Invalid or overlapping measured windows")
        identities = [(pids.pop(), "workload")]
        collector = re.search(r"collector_pid=(\d+)", output)
        if collector:
            identities.append((int(collector[1]), "collector"))
        for pid, role in identities:
            if pid in roles:
                raise ValueError("PID reuse requires lifetime-qualified sample matching")
            roles[pid] = dict(variant=row['variant'], fork=row['fork'], role=role,
                              spans=spans, starts=[s for s, _ in spans], samples=0,
                              outside=0, leaves=collections.Counter())
    current = None
    for line in lines(root / "perf-timed-samples.txt"):
        header = HEADER.match(line)
        if header:
            pid, _, seconds, fraction = header.groups()
            current = None
            record = roles.get(int(pid))
            if record is None:
                continue
            timestamp = int(seconds) * 1_000_000_000 + int(fraction.ljust(9, '0'))
            index = bisect.bisect_right(record['starts'], timestamp) - 1
            if index >= 0 and timestamp < record['spans'][index][1]:
                record['samples'] += 1
                current = record
            else:
                record['outside'] += 1
        elif current is not None and line.strip():
            current['leaves'][line.strip()] += 1
            current = None
    return dict(status="diagnostic_only", policy_eligibility=False,
                limitation="Exit-time maps cannot prove symbol identity across code retirement or reuse. Zero-sample windows are insufficient evidence.",
                processes=[dict(pid=pid, variant=r['variant'], fork=r['fork'], role=r['role'],
                                measured_ns=sum(e-s for s,e in r['spans']), measured_samples=r['samples'],
                                outside_samples=r['outside'], top_leaves=r['leaves'].most_common(20))
                           for pid,r in roles.items()])


if __name__ == '__main__':
    print(json.dumps(analyze(pathlib.Path(sys.argv[1])), indent=2))
