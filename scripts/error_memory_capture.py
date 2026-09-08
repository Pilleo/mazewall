#!/usr/bin/env python3
"""
Continuous Error Memory Recorder (Negative Example Repository).
Extracts concise, deduplicated negative examples (anti-patterns) from:
- Compiler errors (kotlinc / tsc)
- Test assertion / exception failures
Persists them directly to ~/.agentmemory/standalone.json with category="anti-pattern".
"""

import sys
import os
import json
import time
import re
import hashlib

MEMORY_FILE = os.path.expanduser("~/.agentmemory/standalone.json")

def clean_error_text(raw_error):
    # Remove ANSI codes and normalize paths
    clean = re.sub(r'\x1B(?:[@-Z\\-_]|\[[0-?]*[ -/]*[@-~])', '', raw_error)
    lines = [l.strip() for l in clean.splitlines() if l.strip()]
    # Keep the most informative 5 lines
    return "\n".join(lines[:6])

def extract_rule_summary(error_snippet, source_file=""):
    # Heuristic signature extraction
    file_base = os.path.basename(source_file) if source_file else "Code"
    if "Type mismatch" in error_snippet:
        return f"{file_base}: Type mismatch error when assigning or returning types"
    if "Unresolved reference" in error_snippet:
        m = re.search(r'Unresolved reference:\s*(\w+)', error_snippet)
        ref = m.group(1) if m else "symbol"
        return f"{file_base}: Unresolved reference to '{ref}'"
    if "Cannot find name" in error_snippet:
        m = re.search(r"Cannot find name '(\w+)'", error_snippet)
        name = m.group(1) if m else "symbol"
        return f"{file_base}: TypeScript unresolved symbol '{name}'"
    if "Property" in error_snippet and "does not exist on type" in error_snippet:
        return f"{file_base}: TypeScript property access on invalid or unexpanded type"
    if "NullPointerException" in error_snippet or "null" in error_snippet.lower():
        return f"{file_base}: Nullability violation or unhandled null check"
    return f"{file_base}: Compilation or runtime failure"

def record_negative_example(source_file, error_text, module_name=""):
    if not os.path.exists(MEMORY_FILE):
        return

    cleaned = clean_error_text(error_text)
    if not cleaned or len(cleaned) < 10:
        return

    # Deterministic fingerprint to avoid duplicate negative examples
    err_hash = hashlib.sha256(cleaned.encode("utf-8")).hexdigest()[:12]
    mem_id = f"mem_err_{err_hash}"

    try:
        with open(MEMORY_FILE, "r") as f:
            store = json.load(f)

        memories = store.setdefault("mem:memories", {})
        if mem_id in memories:
            # Already captured this exact negative pattern
            return

        summary = extract_rule_summary(cleaned, source_file)
        full_content = (
            f"ANTI-PATTERN in {source_file or module_name}:\n"
            f"Failure output:\n{cleaned}\n"
            f"Rule: Avoid introducing this syntax, type, or coordination mismatch."
        )

        iso_time = time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime())
        memories[mem_id] = {
            "id": mem_id,
            "type": "anti-pattern",
            "title": f"Anti-Pattern: {summary}"[:100],
            "content": full_content,
            "concepts": ["anti-pattern", "error-prevention", module_name.strip(":")],
            "files": [source_file] if source_file else [],
            "createdAt": iso_time,
            "updatedAt": iso_time,
            "strength": 5,
            "version": 1,
            "isLatest": True,
            "sessionIds": []
        }

        with open(MEMORY_FILE, "w") as f:
            json.dump(store, f, indent=2)

    except Exception as e:
        sys.stderr.write(f"[ErrorMemory] Failed to record: {e}\n")

if __name__ == "__main__":
    # Test or CLI usage: python3 error_memory_capture.py <source_file> <error_text>
    if len(sys.argv) > 2:
        record_negative_example(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else "")
