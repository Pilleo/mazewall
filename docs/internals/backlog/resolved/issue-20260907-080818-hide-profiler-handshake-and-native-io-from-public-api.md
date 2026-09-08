---
title: "Hide profiler handshake and native IO from public API"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules:
  - ":profiler"
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/engine/HandshakeSession.kt"
  - "profiler/api/profiler.api"
  - "profiler/src/test/kotlin/io/mazewall/profiler/engine/HandshakeSessionTest.kt"
target_symbols:
  - "HandshakeSession"
verify_cheap:
  - "./gradlew :profiler:test --tests io.mazewall.profiler.engine.HandshakeSessionTest"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
has_side_effects: true

paperclip_issue_id: "7733f848-67ea-4b92-8046-f6fced3fbbf5"
paperclip_identifier: "MAZ-1169"
---

# 🟡 [Severity: MEDIUM]: Hide profiler handshake and native IO from public API

**Context:**
CODE_QUALITY.md wants machines, events, and effects `internal`. `profiler.api` still publishes `HandshakeSession.Active.performHandshake`, `NativeIoOperations` poll/read/write/recv/ioctl, and `ContextEvent.fromSegment` with `MemorySegment` parameters. `@MazewallInternal` is the cross-module opt-in for types another Gradle module must call; handshake I/O is not an operator API.

**Needed:**
1. Make `HandshakeSession`, `NativeIoOperations`, and `ContextEvent.fromSegment` `internal` (or `@MazewallInternal` only if another module must call them).
2. Update `profiler/api/profiler.api` so those members disappear from the public dump.
3. Add an ArchUnit or API-dump test that fails if `MemorySegment` appears in `profiler.api`.
4. Run `./gradlew :profiler:test` and the apiCheck / binary-compatibility task this module already uses.

## Side effects
- profiler.api shrinks; external callers of HandshakeSession and NativeIoOperations will not compile

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

**Resolution evidence (2026-09-07):** `HandshakeSession`, `NativeIoOperations`, and
`ContextEvent.fromSegment` are internal. `ProfilerApiDumpTest` rejects the handshake, native-I/O,
and `MemorySegment` symbols in `profiler.api`. `./gradlew :profiler:test` passed. The historical
`apiCheck` task no longer exists; `:profiler:unitCheck` is the configured coverage gate and must
run with the complete test suite rather than a filtered test.

<!-- id: issue-20260907-080818  file: issue-20260907-080818-hide-profiler-handshake-and-native-io-from-public-api.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
