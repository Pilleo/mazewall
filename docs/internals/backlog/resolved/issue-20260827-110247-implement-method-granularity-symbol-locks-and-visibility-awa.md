---
title: "Implement method-granularity symbol locks and visibility-aware side effect planning"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "orchestrator"
target_modules:
  - ":tools:orchestrator"
target_files:
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/AstImpactScanner.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/DispatchSelector.kt"
  - "tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/IssueClarifier.kt"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
paperclip_issue_id: 803f6429-8d6e-423a-a95f-ff80651ee39b
paperclip_identifier: MAZ-693
---

# 🟡 [Severity: MEDIUM]: Implement method-granularity symbol locks and visibility-aware side effect planning

**Context:**
Currently, task conflict checking operates coarsely at file or module granularity, forcing tasks touching disjoint methods to run strictly sequentially.

To make scheduling and side-effect determination pragmatic:
1. **Method-Granularity Symbol Locks:** Member visibility (`private`, `internal`, `public`) is used to scope **compile-time call-site locks** so independent methods can run concurrently.
2. **Conservative Side-Effects (`has_side_effects`):** Rather than attempting brittle AST static analysis to infer I/O or state side effects, `has_side_effects` **defaults to `true` by default**.
3. **Vibe LLM Evaluation & Conversational Clarification:** When Vibe is active, the prompt instructs Vibe to analyze behavioral side-effects (I/O, state, ABI, thread safety) and toggle `has_side_effects: false` only when confirmed pure. Furthermore, any open questions or investigation findings produced by Vibe are explicitly surfaced to the pairing AI agent (and developer) for interactive review before implementation.

**Needed:**
1. In `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/IssueClarifier.kt`:
   - Default `has_side_effects` to `true` unless explicitly declared `false` or toggled by Vibe.
   - Refine Vibe prompt instructions to reason deeply about potential runtime side effects (I/O, shared memory, downcalls, state mutation).
   - Ensure open questions and investigation findings from Vibe are structured clearly in the markdown for AI agent / developer review.
2. In `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/DispatchSelector.kt`:
   - Support fine-grained `target_symbols` mutual exclusion for independent methods.
3. Update `.agents/skills/create_backlog_issue/SKILL.md` to instruct pair-programming AI agents to inspect Vibe-generated `## ❓ Open Questions` and `## Investigation` sections when picking up an issue.
4. Run `./gradlew :tools:orchestrator:test -PincludeOrchestrator=true`.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260827-110247  file: issue-20260827-110247-implement-method-granularity-symbol-locks-and-visibility-awa.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
