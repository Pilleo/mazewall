---
title: "Thread FileDescriptor through supervisor dirfd and Landlock fds"
severity: "HIGH"
status: "open"
priority: high
dependencies:
  - "issue-20260824-203500"
  - "issue-20260907-080829"
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisedOpen.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorFastPath.kt"
  - "enforcer/src/main/kotlin/io/mazewall/landlock/Landlock.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SupervisedOpenTest.kt"
target_symbols:
  - "SupervisedOpen"
verify_cheap:
  - "./gradlew :enforcer:test --tests io.mazewall.enforcer.supervisor.SupervisedOpenTest"
needs_kernel: true
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
has_side_effects: true

paperclip_issue_id: "41a071e3-832b-4e49-84fc-015ffc611661"
paperclip_identifier: "MAZ-1174"
---

# 🔴 [Severity: HIGH]: Thread FileDescriptor through supervisor dirfd and Landlock fds

**Context:**
After platform downcalls take `FileDescriptor`, enforcer still leaks `Int` on supervisor and Landlock paths: `SupervisedOpen.OpenAt`/`OpenAt2.dirfd`, `SyscallArguments.dirfd`, `SupervisorFastPath.resolveAbsolutePath(dirfd: Int)`, `Landlock.OpenResult.Success.fd`, and `closeLocalFd(fd: Int)` (the value class appears only at close).

**Needed:**
1. Carry `FileDescriptor` (owned/unowned per `issue-20260824-203500`) through `SupervisedOpen`, `SyscallArguments.dirfd`, `resolveAbsolutePath`, and Landlock success fds.
2. Close only through the owned token; do not wrap an invented int at close.
3. Run focused supervisor and Landlock unit tests (`SupervisedOpenTest` and existing Landlock tests).

## Side effects
- Supervisor dirfd and Landlock OpenResult.fd become FileDescriptor

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260907-080949  file: issue-20260907-080949-thread-filedescriptor-through-supervisor-dirfd-and-landlock.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
