---
title: "Split SupervisorSessionHandler along SupervisorRoute with request context types"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandler.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandlerTest.kt"
target_symbols:
  - "SupervisorSessionHandler"
verify_cheap:
  - "./gradlew :enforcer:test --tests io.mazewall.enforcer.supervisor.SupervisorSessionHandlerTest"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Split SupervisorSessionHandler along SupervisorRoute with request context types

**Context:**
`SupervisorSessionHandler` is an 1128-line, 31-method god-file that consumes the otherwise-excellent pure-function router `SupervisorNotificationMachine` (`SupervisorRoute.Continue / AskJvm / InjectFd / SecureExec / Abort`). The route branches then dissolve back into long mutable procedures: six `@Suppress("LongParameterList")` sites, e.g. `sendRequestToJvm(id, pidVal, archVal, ppid, nr, args, pathStr, sockaddrBytes)` (`SupervisorSessionHandler.kt:366-420`) with eight positional primitives where swapping two same-typed `Int`s compiles silently, plus repeated nullable-var accumulation blocks (`extractNotificationArgs`, `:279-282`). Reviewing a change here requires holding the whole file in mind because route handling, argument extraction, FFM reads, and response writing are interleaved.

**Needed:**
1. Introduce parameter-object types for the long lists: `NotifHeader(nr, tid, arch, ppid, args: SyscallArguments)` (already exists in parts), `JvmVerdictRequest(id, header, resolvedPath, sockaddrBytes)` — replace all six `LongParameterList` suppressions.
2. Split the handler into one file per `SupervisorRoute` branch (`ContinueRoute`, `InjectFdRoute`, `SecureExecRoute`, `AbortRoute`) exposing functions shaped like `(route input context) -> NotifResult`; the dispatcher method reduces to parse -> classify -> route -> execute.
3. Keep all ACK semantics intact per profiler/enforcer AGENTS rules: every path must still CONTINUE, KILL_THREAD, or ABORT exactly once (the existing deadlock invariant). Add a unit test enumerating routes asserting exactly-one-response per notification.
4. No behavioral changes: this is a mechanical extraction verified by the existing `SupervisorSessionHandlerTest` suite plus the new route-enumeration test.

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260826-102722  file: issue-20260826-102722-split-supervisorsessionhandler-along-supervisorroute-with-re.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
