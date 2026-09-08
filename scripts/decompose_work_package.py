#!/usr/bin/env python3
"""
Automated Dependency Graph Work-Package Decomposer (Codanna-Powered).
Analyzes blast radius, call hierarchy, and module boundaries to decompose
monolithic issues into ordered, atomic subtasks.
"""

import sys
import os
import subprocess
import json
import re

REPO_ROOT = os.path.expanduser("~/Documents/code/java/jseccomp")

def resolve_symbol_ids(symbol_name):
    try:
        res = subprocess.run(
            ["codanna", "retrieve", "describe", symbol_name],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            timeout=5
        )
        output = res.stdout + res.stderr
        ids = re.findall(r'symbol_id:(\d+)', output)
        return list(set(ids))
    except Exception:
        return []

def get_symbol_callers(symbol_identifier):
    try:
        res = subprocess.run(
            ["codanna", "retrieve", "callers", symbol_identifier],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            timeout=5
        )
        files = []
        for line in (res.stdout + res.stderr).splitlines():
            m = re.search(r'at \./(.*?):(\d+)', line)
            if m:
                files.append(m.group(1))
        return list(set(files))
    except Exception:
        return []

def decompose(title, symbols, explicit_files=None):
    explicit_files = explicit_files or []
    all_files = set(explicit_files)
    symbol_trace = {}

    for s in symbols:
        ids = resolve_symbol_ids(s)
        targets = [f"symbol_id:{i}" for i in ids] if ids else [s]
        callers = []
        for t in targets:
            callers.extend(get_symbol_callers(t))
        symbol_trace[s] = list(set(callers))
        all_files.update(callers)

    modules_affected = set()
    for f in all_files:
        if f.startswith("enforcer/"): modules_affected.add(":enforcer")
        elif f.startswith("platform/"): modules_affected.add(":platform")
        elif f.startswith("profiler/"): modules_affected.add(":profiler")
        elif f.startswith("portal/"): modules_affected.add(":portal")
        elif f.startswith("demos/"): modules_affected.add(":demos")

    print(f"\n==================================================")
    print(f" 📦 WORK PACKAGE DECOMPOSITION REPORT")
    print(f"==================================================")
    print(f"Feature/Task: {title}")
    print(f"Target Symbols: {', '.join(symbols)}")
    print(f"Direct & Indirect Call-Sites: {len(all_files)} files across {len(modules_affected)} module(s):")
    for mod in sorted(list(modules_affected)):
        mod_files = [f for f in all_files if f.startswith(mod.strip(':'))]
        print(f"  {mod} ({len(mod_files)} files):")
        for mf in sorted(mod_files)[:8]:
            print(f"    - {mf}")
        if len(mod_files) > 8:
            print(f"    - ... and {len(mod_files)-8} more")

    # Topological decomposition plan
    plan = []
    p1_files = [f for f in all_files if f.startswith("platform/")]
    p2_files = [f for f in all_files if f.startswith("enforcer/") or f.startswith("portal/")]
    p3_files = [f for f in all_files if f.startswith("profiler/") or f.startswith("demos/")]

    if p1_files:
        plan.append({
            "stage": 1,
            "title": f"Foundation: {title} (Types, Primitives, FFM)",
            "module": ":platform",
            "files": p1_files,
            "depends_on": []
        })
    if p2_files:
        plan.append({
            "stage": len(plan) + 1,
            "title": f"Core Engine: {title} (Seccomp/Landlock Handlers)",
            "module": ":enforcer",
            "files": p2_files,
            "depends_on": [plan[0]["title"]] if p1_files else []
        })
    if p3_files:
        plan.append({
            "stage": len(plan) + 1,
            "title": f"Diagnostics & Integration: {title} (Profiler/Demos)",
            "module": ":profiler",
            "files": p3_files,
            "depends_on": [plan[-1]["title"]] if plan else []
        })

    if not plan:
        plan.append({
            "stage": 1,
            "title": title,
            "module": list(modules_affected)[0] if modules_affected else ":enforcer",
            "files": list(all_files),
            "depends_on": []
        })

    print(f"\n🧩 Recommended DAG Decomposition ({len(plan)} subtask(s)):")
    for p in plan:
        dep_str = f" [Blocks on {', '.join(p['depends_on'])}]" if p["depends_on"] else " [Independent / Ready]"
        print(f"\n  Step {p['stage']}: {p['title']}{dep_str}")
        print(f"    Module: {p['module']}")
        print(f"    Scoped Target Files ({len(p['files'])}): {p['files'][:5]}")

if __name__ == "__main__":
    symbols = sys.argv[1:] if len(sys.argv) > 1 else ["isSupported"]
    decompose("Refactor Platform Capability Resolution", symbols)
