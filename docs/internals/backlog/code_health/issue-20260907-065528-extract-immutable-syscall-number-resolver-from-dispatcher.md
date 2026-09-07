---
title: "Extract immutable syscall-number resolver from dispatcher"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "platform"
target_modules:
  - ":platform"
  - ":enforcer"
  - ":profiler"
target_files:
  - "platform/src/main/kotlin/io/mazewall/core/Syscall.kt"
  - "platform/src/main/kotlin/io/mazewall/core/SyscallNumberResolver.kt"
  - "platform/src/test/kotlin/io/mazewall/core/SyscallNumberResolverTest.kt"
target_symbols:
  - "Syscall"
verify_cheap:
  - "./gradlew :enforcer:test --tests io.mazewall.SyscallTest"
needs_kernel: true
core_lock: true
effort: "medium"
autonomy: "supervised"
open_questions: false
review_verdict: skipped
has_side_effects: true

paperclip_issue_id: "283e246c-0544-4ce2-9cc3-02bd376b0000"
paperclip_identifier: "MAZ-1148"
---

# 🟡 [Severity: MEDIUM]: Extract immutable syscall-number resolver from dispatcher

**Context:**
Architecture-specific syscall mappings are embedded in the `Syscall` dispatcher. They are pure data
selection but coexist with parsing and public dispatch behavior, making it hard to read the mapping
as a specification or cover architecture × syscall combinations without broad integration setup.

**Needed:**
1. Extract an internal immutable resolver; preserve the existing public API and unsupported-syscall
   sentinel exactly.
2. Characterize supported architecture mappings before moving them, including representative
   filesystem, network, process, alias, and unsupported syscalls.
3. Add readable parameterized tables in `:platform`; use a property only for a genuine invariant
   such as alias equivalence, never for line coverage.
4. Run focused `:platform:test`, inspect the platform JaCoCo report, then run `unitCheck`.

## Important details
- ### Work Package DAG
```mermaid
graph TD
  Stage1[":platform: Part 1"]
  Stage2[":enforcer: Part 2"]
  Stage3[":profiler: Part 3"]
  Stage1 --> Stage2
  Stage2 --> Stage3
```

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260907-065528  file: issue-20260907-065528-extract-immutable-syscall-number-resolver-from-dispatcher.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
