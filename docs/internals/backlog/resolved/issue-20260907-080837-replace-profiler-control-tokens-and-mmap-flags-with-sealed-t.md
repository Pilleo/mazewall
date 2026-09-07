---
title: "Replace profiler control tokens and mmap flags with sealed types"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/tierE/daemon/ControlProtocol.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/engine/TraceEvent.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/tierE/daemon/ControlProtocolTest.kt"
target_symbols:
  - "ControlProtocol"
verify_cheap:
  - "./gradlew :profiler:test --tests io.mazewall.profiler.tierE.daemon.ControlProtocolTest"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
has_side_effects: true

paperclip_issue_id: "ecaeda61-91a7-4f5c-ae8b-7f283486a231"
paperclip_identifier: "MAZ-1171"
---

# 🟡 [Severity: MEDIUM]: Replace profiler control tokens and mmap flags with sealed types

**Context:**
Profiler mmap events and ioctl helpers keep `fd`/`prot`/`flags` as `Int`. Control-plane commands are string tokens with `else` falling through to `USAGE_ERR`/`BAD_MODE`. A typo or a new command is not a compile error. `when` on `String`/`Int` cannot be compiler-exhaustive.

**Needed:**
1. Introduce a sealed `ControlCommand` (or enum) parsed from the wire token; dispatch with an exhaustive `when` and no `else`. Unknown tokens map to a single `Unknown` variant that replies `USAGE_ERR`.
2. Introduce `MmapProt` / mmap flags value types (or named constants wrapping the Linux bits) on trace/mmap events instead of raw `Int` at the Kotlin boundary.
3. Add a unit test that adding a command variant without a handler fails compilation or the exhaustiveness scan.
4. Run `./gradlew :profiler:test --tests io.mazewall.profiler.tierE.daemon.ControlProtocolTest`.

## Side effects
- Control-plane command strings and mmap prot/flags Ints become sealed types

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

**Resolution evidence (2026-09-07):** `ControlCommand` is sealed with an explicit `Unknown`
variant and exhaustive rejection dispatch. Mmap protection, flags, and descriptor arguments are
separate value types at the trace-event boundary. `./gradlew :profiler:test --tests
io.mazewall.profiler.tierE.daemon.ControlProtocolTest` passed.

<!-- id: issue-20260907-080837  file: issue-20260907-080837-replace-profiler-control-tokens-and-mmap-flags-with-sealed-t.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
