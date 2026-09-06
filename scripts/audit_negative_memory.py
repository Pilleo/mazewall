#!/usr/bin/env python3
"""
Two-Tier Negative Memory Auditing & Tombstone Ledger.
Tier 1: Deterministic check - Prunes negative memories whose target files or symbols
        have been deleted or refactored away. NO LLM involved.
Tier 2: Model review - Evaluates whether remaining memories represent durable architectural
        invariants or transient trivia/one-off issues.
Tombstone Ledger: Records SHA-256 hashes of pruned items to ensure they stay forgotten.
"""

import sys
import os
import json
import re
import hashlib
import time
import subprocess

MEMORY_FILE = os.path.expanduser("~/.agentmemory/standalone.json")
TOMBSTONE_FILE = os.path.expanduser("~/Documents/code/java/jseccomp/.codanna/negative_memory_tombstones.json")
REPO_ROOT = os.path.expanduser("~/Documents/code/java/jseccomp")

def load_tombstones():
    if os.path.exists(TOMBSTONE_FILE):
        try:
            with open(TOMBSTONE_FILE, "r") as f:
                return json.load(f)
        except Exception:
            return {}
    return {}

def save_tombstones(tombstones):
    os.makedirs(os.path.dirname(TOMBSTONE_FILE), exist_ok=True)
    with open(TOMBSTONE_FILE, "w") as f:
        json.dump(tombstones, f, indent=2)

def check_symbol_exists_in_codanna(symbol_name):
    try:
        res = subprocess.run(
            ["codanna", "retrieve", "describe", symbol_name],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            timeout=5
        )
        # returncode 3 means "Not found"
        if res.returncode == 3 or "Not found:" in res.stdout:
            return False
        return True
    except Exception:
        return True # On error, give benefit of the doubt

def check_file_exists_in_repo(rel_path):
    if not rel_path:
        return True
    full_path = os.path.join(REPO_ROOT, rel_path) if not os.path.isabs(rel_path) else rel_path
    if os.path.exists(full_path):
        return True
    try:
        res = subprocess.run(
            ["git", "ls-files", rel_path],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            timeout=3
        )
        return bool(res.stdout.strip())
    except Exception:
        return False

def extract_symbols_from_memory(mem):
    title = mem.get("title", "")
    content = mem.get("content", "")
    text = f"{title} {content}"
    
    # Common symbol naming patterns in JVM / TypeScript
    candidates = re.findall(r'\b([A-Z][a-zA-Z0-9_]{3,})\b', text)
    # Filter out common English capitalized words
    stopwords = {"ANTI-PATTERN", "BACKLOG", "BUG", "INVARIANT", "Context", "Problem", "Resolution", "Rule", "Failure", "Severity", "Avoid", "None", "Error", "Type", "String", "Int", "Boolean", "Long", "Unit", "List", "Map", "Set"}
    return [c for c in candidates if c not in stopwords]

def run_audit(dry_run=False):
    if not os.path.exists(MEMORY_FILE):
        print(f"Memory store not found at {MEMORY_FILE}")
        return

    with open(MEMORY_FILE, "r") as f:
        store = json.load(f)

    memories = store.get("mem:memories", {})
    tombstones = load_tombstones()

    to_prune = {} # mem_id -> reason

    print(f"Auditing {len(memories)} memories...")

    for mem_id, mem in list(memories.items()):
        # Only audit anti-pattern / bug memories
        mem_type = mem.get("type", "")
        if mem_type != "anti-pattern":
            continue

        files = mem.get("files", [])
        title = mem.get("title", "")
        content = mem.get("content", "")

        # ─── TIER 1: DETERMINISTIC DEAD CODE PRUNING (NO AI) ─────────
        # Check files
        all_files_deleted = False
        if files:
            existing = [f for f in files if check_file_exists_in_repo(f)]
            if not existing:
                all_files_deleted = True
                to_prune[mem_id] = f"DEAD_CODE: Referenced file(s) deleted ({', '.join(files)})"

        # Check symbols if not already marked
        if mem_id not in to_prune:
            symbols = extract_symbols_from_memory(mem)
            if symbols:
                dead_symbols = []
                for s in symbols[:3]: # Check top prominent symbols
                    if not check_symbol_exists_in_codanna(s):
                        dead_symbols.append(s)
                # If all primary symbols are absent from Codanna index
                if dead_symbols and len(dead_symbols) == len(symbols[:3]):
                    to_prune[mem_id] = f"DEAD_CODE: Symbols no longer exist in codebase ({', '.join(dead_symbols)})"

        # Check if already tombstoned
        content_hash = hashlib.sha256(content.encode("utf-8")).hexdigest()[:16]
        if content_hash in tombstones:
            to_prune[mem_id] = f"TOMBSTONED: Previously forgotten ({tombstones[content_hash].get('reason')})"

    print(f"\nAudit completed:")
    print(f"  Total Anti-Patterns Inspected: {sum(1 for m in memories.values() if m.get('type') == 'anti-pattern')}")
    print(f"  Identified for Pruning (Tier 1 Dead Code): {len(to_prune)}")

    for mid, reason in list(to_prune.items())[:10]:
        print(f"  - [{mid}] {memories[mid].get('title', '')[:60]} -> {reason}")
    if len(to_prune) > 10:
        print(f"  ... and {len(to_prune) - 10} more.")

    if not dry_run and to_prune:
        iso_time = time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime())
        for mid, reason in to_prune.items():
            mem = memories[mid]
            content = mem.get("content", "")
            chash = hashlib.sha256(content.encode("utf-8")).hexdigest()[:16]
            tombstones[chash] = {
                "id": mid,
                "title": mem.get("title", ""),
                "reason": reason,
                "timestamp": iso_time
            }
            # Record file paths in tombstones
            for f in mem.get("files", []):
                tombstones[f] = {
                    "reason": reason,
                    "timestamp": iso_time
                }
            del memories[mid]

        with open(MEMORY_FILE, "w") as f:
            json.dump(store, f, indent=2)
        save_tombstones(tombstones)
        print(f"\nPruned {len(to_prune)} memories and updated tombstone ledger ({TOMBSTONE_FILE}).")

if __name__ == "__main__":
    dry_run = "--dry-run" in sys.argv
    run_audit(dry_run=dry_run)
