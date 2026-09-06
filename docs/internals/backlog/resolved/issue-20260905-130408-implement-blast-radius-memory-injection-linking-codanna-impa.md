---
title: "Implement blast-radius memory injection linking Codanna impact symbols to agentmemory"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/BlastRadiusMemoryScanner.kt"
  - "tools/orchestrator/src/test/kotlin/io/mazewall/orchestrator/BlastRadiusMemoryScannerTest.kt"
  - "scripts/code_atlas.sh"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
review_verdict: skipped
has_side_effects: true

paperclip_issue_id: "88296c79-b007-4101-bc40-f9c2b3bc615f"
paperclip_identifier: "MAZ-1051"
---

# 🔴 [Severity: HIGH]: Implement blast-radius memory injection linking Codanna impact symbols to agentmemory

**Context:**
Currently, agents querying `agentmemory` only search for immediate keywords or symbols mentioned in user instructions. However, critical regressions often occur in transitive dependencies (e.g., changing a socket method in `:platform` breaks invariants in `:enforcer` or `:profiler`). By integrating Codanna's `analyze_impact` symbol analysis into `scripts/pre_invocation_memory.py`, the system can trace the call hierarchy of target files/symbols up to depth 3, extract the affected symbols, and query `agentmemory` for known gotchas, past bugs, and kernel invariants on the entire downstream blast radius before any code is written.

**Needed:**
1. Update `scripts/pre_invocation_memory.py` to call `codanna mcp analyze_impact <symbol>` for all target symbols/files.
2. Parse the returned downstream symbols from Codanna's impact graph.
3. Query `agentmemory.memory_smart_search` with the aggregated blast radius symbols to recall historical bugs, invariants, and operator decisions.
4. Format the retrieved memories into high-priority constraints in the pre-invocation context block.
5. Verify that running `python3 scripts/pre_invocation_memory.py --symbol connectWithRetry` surfaces the invariants of both `SupervisorSocketUtils` and downstream callers like `SupervisorSeccompNotifInstaller`.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260905-130408  file: issue-20260905-130408-implement-blast-radius-memory-injection-linking-codanna-impa.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
