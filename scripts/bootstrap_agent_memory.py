#!/usr/bin/env python3
"""
Bootstrap agent memory with high-density architectural ground rules,
invariants, and layout maps for jseccomp (mazewall) and paperclip-adapters.
"""
import urllib.request
import json
import os

MEMORIES = [
    {
        "title": "mazewall: Core Architecture & Invariants",
        "content": (
            "mazewall (jseccomp) is a Linux Seccomp-BPF and Landlock LSM sandboxing library for the JVM "
            "via the JDK Foreign Function & Memory (FFM) API. Minimum JDK is 22; targets Java 25 idioms. "
            "Hard Invariants: Never catch EPERM or EACCES exceptions silently (no bypasses). "
            "Never block JVM coordination syscalls. Never combine SECCOMP_FILTER_FLAG_TSYNC and "
            "SECCOMP_FILTER_FLAG_NEW_LISTENER. Never use JAVA_LONG for 32-bit sock_filter fields. "
            "Default FallbackBehavior is FAIL (fail-closed)."
        )
    },
    {
        "title": "mazewall: Testing & Modules Guidelines",
        "content": (
            "mazewall module split: :enforcer (core sandbox engine, downcalls, PureJavaBpfEngine, Landlock) "
            "and :profiler (USER_NOTIF ACK loops, syscall tracer, strace). "
            "Tests must run via nested-seccomp OCI containers using Podman scripts: "
            "./gradlew test (host unit tests), ./gradlew integrationTest (forkEvery=0), "
            "./gradlew integrationTestFreshJvm (@NeedsFreshJvm, forkEvery=1), ./scripts/run_tests.sh. "
            "Always follow TDD; reproduce bugs with failing tests before fixing."
        )
    },
    {
        "title": "paperclip-adapters: Architecture & Invariants",
        "content": (
            "paperclip-adapters is the multi-agent fleet adapters and orchestration monorepo for Paperclip AI. "
            "Key packages: packages/orchestrator (deterministic state machine, dependency graph, PR reconciliation), "
            "packages/worker-adapter (isolated workspace runner), packages/telegram (bridge). "
            "Hard Invariants: Never mock database calls in integration tests (use real SQLite/Postgres instances). "
            "Adhere strictly to CONTRIBUTING.md. Built with pnpm workspaces, tsx, vitest."
        )
    },
    {
        "title": "Local Hardware & Runtime Environment",
        "content": (
            "System profile: Intel Core i5-1335U, Intel Iris Xe iGPU (running KDE Plasma desktop session), "
            "NVIDIA RTX A500 Laptop GPU (4 GB VRAM, dedicated to compute on CUDA 13.2). "
            "Local Ollama serves qwen3.5:4b and qwen-memory at 32k context on dGPU (~11-12 tokens/s). "
            "Semantic memory embeddings use BAAI/bge-m3 via Gonka Broker."
        )
    }
]

def main():
    print("Bootstrapping agent memory entries...")
    # Read standalone.json
    path = os.path.expanduser("~/.agentmemory/standalone.json")
    try:
        with open(path, "r") as f:
            data = json.load(f)
    except Exception:
        data = {"mem:memories": {}}

    memories = data.setdefault("mem:memories", {})
    import time
    for item in MEMORIES:
        # Create deterministic slug key
        slug = item["title"].lower().replace(":", "").replace(" ", "_")[:32]
        mem_id = f"mem_boot_{slug}"
        now = "2026-09-05T00:50:00.000Z"
        memories[mem_id] = {
            "id": mem_id,
            "type": "fact",
            "title": item["title"],
            "content": item["content"],
            "concepts": [],
            "files": [],
            "createdAt": now,
            "updatedAt": now,
            "strength": 10,
            "version": 1,
            "isLatest": True,
            "sessionIds": []
        }
        print(f"  + Seeded: {item['title']}")

    with open(path, "w") as f:
        json.dump(data, f, indent=2)
    print("Bootstrapping complete.")

if __name__ == "__main__":
    main()
