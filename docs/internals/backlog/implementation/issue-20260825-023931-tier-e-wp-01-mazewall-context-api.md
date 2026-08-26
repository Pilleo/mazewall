---
title: "Tier E WP-01: In-memory MazewallContext API (explicit scopes, guards)"
severity: "ENHANCEMENT"
status: "open"
priority: high
component: "platform"
target_modules:
  - ":platform"
target_files:
  - "platform/src/main/kotlin/io/mazewall/core/MazewallContext.kt"
  - "platform/src/test/kotlin/io/mazewall/core/MazewallContextTest.kt"
effort: "small"
autonomy: "supervised"
open_questions: true
dependencies:
  - "issue-20260825-023930-tier-e-initiative.md"
paperclip_issue_id: 6e81476f-d704-48bc-b3ca-639bd44c5f40
---

# 🟢 [Severity: ENHANCEMENT]: WP-01 — In-memory MazewallContext API

**Context:** Pure-JVM, no BPF, no native code. The boring foundation every later work package
stands on. `ContextId` and `AttributionKind` already exist in `io.mazewall.core` (landed with the
initiative). This WP adds the scope API around them.

Design reference: [tier-e-design.md §4.1, §11 risk 5](../../designs/profiler/tier-e-design.md).

**Needed:**

1. `MazewallContext` object with:
   ```kotlin
   public fun <T> withContext(context: ContextId, block: () -> T): T
   public fun current(): ContextId
   ```
2. Backed by `ThreadLocal.withInitial { ContextId.UNKNOWN }`.
3. **Nested contexts must restore correctly:** save previous, set new, restore previous in
   `finally`. Exceptions must restore too.
4. **Virtual-thread guard (invariant 4):** if `Thread.currentThread().isVirtual()` → throw
   `IllegalStateException` BEFORE any state change. Rationale: a vthread unmounting mid-scope
   leaves its label on the carrier LWP; the next vthread on that carrier inherits a wrong fact.
5. Optional fast path (document semantics precisely): skip the marker downcall when
   `current() == context` — the storage already holds that value. Must be exactly equivalent to
   calling it.
6. Public surface placement: default is inside an existing artifact (no new Gradle module).
   Flag in PR description if you believe a separate published module is warranted — this is the
   design doc's Open Question §12.1.

### Tests

```text
UNKNOWN initially on a fresh thread
context visible inside scope
restored afterwards (normal return)
restored after exception
nested scopes restore innermost-first (HTTP → PDF_PARSE → back)
two platform threads do not see each other's context
100 threads updating contexts concurrently remain isolated
virtual thread invocation throws IllegalStateException and changes nothing
skip-if-unchanged path leaves observable state identical to normal path
```

**PR is done when:** all tests pass, `./gradlew build` green, and application code can annotate
logical operations without any profiler involved.

## ❓ Open Questions

1. Should `withContext` accept a suspend-friendly variant now? **No for v1** — coroutines are
   explicitly out of scope; note it in KDoc as unsupported.
