#!/usr/bin/env python3
"""
Backlog Bug & Negative Example Indexer (Tombstone-Aware).
Scans docs/internals/backlog/ (and resolved/) to extract:
- Known bugs, failure contexts, and invariants
- Respects .codanna/negative_memory_tombstones.json so audited/pruned issues STAY forgotten.
- Stores active anti-patterns in ~/.agentmemory/standalone.json.
"""

import os
import sys
import json
import re
import hashlib
import time

MEMORY_FILE = os.path.expanduser("~/.agentmemory/standalone.json")
TOMBSTONE_FILE = os.path.expanduser("~/Documents/code/java/jseccomp/.codanna/negative_memory_tombstones.json")

def load_tombstones():
    if os.path.exists(TOMBSTONE_FILE):
        try:
            with open(TOMBSTONE_FILE, "r") as f:
                return json.load(f)
        except Exception:
            return {}
    return {}

def parse_markdown_issue(file_path):
    try:
        with open(file_path, "r") as f:
            content = f.read()
    except Exception:
        return None

    title_match = re.search(r'title:\s*["\']?(.*?)["\']?\s*$', content, re.MULTILINE)
    title = title_match.group(1) if title_match else ""
    if not title:
        h1_match = re.search(r'^#\s+(.*?)$', content, re.MULTILINE)
        title = h1_match.group(1) if h1_match else os.path.basename(file_path)

    context_match = re.search(r'\*\*Context:\*\*\s*(.*?)(?=\n\*\*|\n#|\Z)', content, re.DOTALL)
    context = context_match.group(1).strip() if context_match else ""

    resolution_match = re.search(r'\*\*(?:Resolution|Needed):\*\*\s*(.*?)(?=\n\*\*|\n#|\Z)', content, re.DOTALL)
    resolution = resolution_match.group(1).strip() if resolution_match else ""

    if not context and not resolution:
        lines = [l.strip() for l in content.splitlines() if l.strip() and not l.startswith("---")]
        context = " ".join(lines[:4])

    return {
        "title": title,
        "context": context[:400],
        "resolution": resolution[:400],
        "file": file_path,
        "raw_content": content
    }

def sync_backlog_bugs_to_memory(repo_root):
    if not os.path.exists(MEMORY_FILE):
        return 0

    tombstones = load_tombstones()

    backlog_dirs = [
        os.path.join(repo_root, "docs/internals/backlog"),
        os.path.join(repo_root, "docs/internals/backlog/resolved"),
        os.path.join(repo_root, "docs/internals/backlog/security"),
        os.path.join(repo_root, "docs/internals/backlog/code_health")
    ]

    count = 0
    skipped_tombstones = 0

    try:
        with open(MEMORY_FILE, "r") as f:
            store = json.load(f)
        memories = store.setdefault("mem:memories", {})

        for bdir in backlog_dirs:
            if not os.path.isdir(bdir):
                continue
            for fname in os.listdir(bdir):
                if not fname.endswith(".md") or fname == "README.md":
                    continue
                fpath = os.path.join(bdir, fname)
                rel_fpath = os.path.relpath(fpath, repo_root)

                issue = parse_markdown_issue(fpath)
                if not issue:
                    continue

                # Check tombstones by file path or content hash
                content_hash = hashlib.sha256(issue["raw_content"].encode("utf-8")).hexdigest()[:16]
                if rel_fpath in tombstones or content_hash in tombstones or fname in tombstones:
                    skipped_tombstones += 1
                    continue

                clean_title = re.sub(r'^[🔴🟡🟢⚪]\s*(\[Severity:\s*\w+\]:?\s*)?', '', issue["title"]).strip()
                mem_id = f"mem_bug_{hashlib.sha256(fname.encode('utf-8')).hexdigest()[:12]}"

                if mem_id in memories:
                    continue

                full_content = (
                    f"BACKLOG BUG INVARIANT: {clean_title}\n"
                    f"Problem Context: {issue['context']}\n"
                    f"Resolution Rule: {issue['resolution'] or 'Do not regress this fix.'}"
                )

                iso_time = time.strftime("%Y-%m-%dT%H:%M:%S.000Z", time.gmtime())
                memories[mem_id] = {
                    "id": mem_id,
                    "type": "anti-pattern",
                    "title": f"Bug: {clean_title}"[:100],
                    "content": full_content,
                    "concepts": ["backlog-bug", "anti-pattern", "regression-prevention"],
                    "files": [rel_fpath],
                    "createdAt": iso_time,
                    "updatedAt": iso_time,
                    "strength": 8,
                    "version": 1,
                    "isLatest": True,
                    "sessionIds": []
                }
                count += 1

        if count > 0:
            with open(MEMORY_FILE, "w") as f:
                json.dump(store, f, indent=2)

    except Exception as e:
        sys.stderr.write(f"Failed to sync backlog bugs: {e}\n")

    return count, skipped_tombstones

if __name__ == "__main__":
    root = os.path.expanduser("~/Documents/code/java/jseccomp")
    synced, skipped = sync_backlog_bugs_to_memory(root)
    print(f"Synced {synced} backlog bugs into negative error memory (Skipped {skipped} tombstoned).")
