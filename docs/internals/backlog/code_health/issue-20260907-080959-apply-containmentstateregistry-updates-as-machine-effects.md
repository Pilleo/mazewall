---
title: "Apply ContainmentStateRegistry updates as machine effects"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - "issue-20260907-080955"
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
  - "enforcer/src/integrationTest/kotlin/io/mazewall/enforcer/ContainedExecutorsTest.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/ContainedExecutors.kt"
target_symbols:
  - "ContainedExecutors"
verify_cheap:
  - "./gradlew :enforcer:test --tests io.mazewall.enforcer.ContainedExecutorsTest"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
has_side_effects: true

paperclip_issue_id: "2f4b3d32-c97d-4466-9355-642364d5d88f"
paperclip_identifier: "MAZ-1176"
---

# 🟡 [Severity: MEDIUM]: Apply ContainmentStateRegistry updates as machine effects

**Context:**
`ContainedExecutors` mutates `ContainmentStateRegistry` after kernel Landlock/seccomp calls inside `installInternal`, rather than applying registry updates as effects of the install machine. That interleaves process state with native I/O and makes install order hard to review.

**Needed:**
1. Treat registry write (Landlock applied / seccomp installed / rejected) as an effect returned from evaluate, applied by the interpreter after the kernel call result is known.
2. Keep Landlock-before-seccomp order in the machine, not as comments on a missing `applyContainment()` method.
3. Run `:enforcer:test` install/registry tests. Do not swallow `EPERM`/`EACCES`.

## Side effects
- Registry mutations happen after evaluate, not inline after kernel Landlock or seccomp calls

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260907-080959  file: issue-20260907-080959-apply-containmentstateregistry-updates-as-machine-effects.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
