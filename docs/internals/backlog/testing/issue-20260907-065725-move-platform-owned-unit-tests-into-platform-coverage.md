---
title: "Move platform-owned unit tests into platform coverage"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "platform"
target_modules:
  - ":platform"
  - ":enforcer"
target_files:
  - "platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/SeccompDaemonEngine.kt"
  - "enforcer/src/test/kotlin/io/mazewall/platform/seccomp/daemon/SeccompDaemonEngineTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/SyscallResultTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/ffi/networking/SupervisorSocketUtilsTest.kt"
target_symbols:
  - "SeccompDaemonEngine"
verify_cheap:
  - "./gradlew :enforcer:test --tests io.mazewall.platform.seccomp.daemon.SeccompDaemonEngineTest"
needs_kernel: true
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
has_side_effects: true

paperclip_issue_id: "10a3572a-2a8b-404f-ba1e-d40676f876cf"
paperclip_identifier: "MAZ-1150"
---

# 🟡 [Severity: MEDIUM]: Move platform-owned unit tests into platform coverage

**Context:**
Platform production classes are tested from `:enforcer`. The tests execute successfully but their
JaCoCo data belongs only to `:enforcer`, understating platform host-unit coverage and preventing the
platform gate from expressing its real confidence level.

**Needed:**
1. Move the existing tests for `ErrnoMapping`, `SupervisorSocketUtils`, and `SeccompDaemonEngine`
   into `:platform:test`, preserving their behavioral assertions and avoiding duplicate copies.
2. Add a shared fixture only when it is used by two or more modules; otherwise keep the fixture
   private to platform.
3. Run each moved class through `:platform:test` and verify the platform JaCoCo XML marks the
   corresponding production class covered; then run `unitCheck`.

## Investigation
- AST identifier scan: 0 hits outside origin files

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

## Side effects
- Relocates existing host-unit tests without changing containment behavior

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260907-065725  file: issue-20260907-065725-move-platform-owned-unit-tests-into-platform-coverage.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
