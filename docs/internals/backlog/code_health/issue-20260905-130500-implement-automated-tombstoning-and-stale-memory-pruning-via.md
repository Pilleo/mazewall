---
title: "Implement automated tombstoning and stale memory pruning via Codanna AST verification"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "testing"
target_modules:
  - ":platform"
target_files:
  - "scripts/audit_negative_memory.py"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
review_verdict: skipped
has_side_effects: true

paperclip_issue_id: "66975a69-6190-48d3-ba1e-5b195b23ae07"
paperclip_identifier: "MAZ-1050"
---

# 🟡 [Severity: MEDIUM]: Implement automated tombstoning and stale memory pruning via Codanna AST verification

**Context:**
As a codebase evolves through refactorings, classes and methods get renamed, moved, or deleted entirely. Negative memories and past bug records in `agentmemory` that reference deleted symbols become obsolete noise, cluttering the token budget and confusing autonomous agents with ghost invariants. By leveraging Codanna's fast AST symbol index, an automated audit script can verify the existence and caller counts of all symbols referenced in memory, pruning or tombstoning records whose target symbols no longer exist in the code tree.

**Needed:**
1. Update `scripts/audit_negative_memory.py` to extract symbol identifiers from all indexed negative memory records.
2. For each extracted symbol, query `codanna retrieve symbol <symbol_name>`.
3. If Codanna reports the symbol is missing or has 0 callers after a refactor, mark the memory entry as tombstoned in `.codanna/negative_memory_tombstones.json`.
4. Call `agentmemory.memory_governance_delete` or flag the memory to prevent it from being injected during agent turns.
5. Provide a CLI command `--dry-run` to preview stale memory candidates before committing deletions.
6. Verify by running `python3 scripts/audit_negative_memory.py --dry-run`.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260905-130500  file: issue-20260905-130500-implement-automated-tombstoning-and-stale-memory-pruning-via.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
