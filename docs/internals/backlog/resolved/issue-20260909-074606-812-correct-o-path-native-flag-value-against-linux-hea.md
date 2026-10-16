---
title: "Correct O_PATH native flag value against Linux headers"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "platform"
target_modules: ["platform", "profiler", "enforcer"]
target_files:
  - "platform/src/main/kotlin/io/mazewall/ffi/NativeConstants.kt"
  - "platform/src/main/kotlin/io/mazewall/ffi/networking/SupervisorSocketUtils.kt"
  - "platform/src/main/kotlin/io/mazewall/core/SocketManager.kt"
  - "platform/src/main/kotlin/io/mazewall/ffi/networking/UnixServerSocket.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/compiler/BobCompiler.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/compiler/StraceLogParser.kt"
target_symbols:
  - "NativeConstants"
open_questions: false

paperclip_issue_id: "0d8e770a-f03a-48eb-b45e-96330432a38e"
paperclip_identifier: "MAZ-1371"
---

# 🟡 [Severity: HIGH]: Correct O_PATH native flag value against Linux headers

**Context:**
Task 8 literal native-flag test found O_PATH is 0x01000000 in NativeConstants.kt:86, but /usr/include/asm-generic/fcntl.h defines octal 010000000, hexadecimal 0x00200000. The decomposition preserves legacy native flags; correcting this cross-module constant needs its own ABI and kernel regression review.

**Needed:**

1. Add failing assertions against the Linux header value and fresh-JVM tests of O_PATH behavior, then fix the constant and audit all consumers.
2. Harden adjacent descriptor acquisition: atomic CLOEXEC on SCM_RIGHTS receive, exact one-FD validation, rejected-message cleanup, and UNIX server cleanup on all post-acquisition failures.

**Resolution (2026-09-09):**

- Corrected the platform flag to Linux `0x00200000`; compiler and strace parser use that shared value. A quoted pathname containing `O_PATH` cannot suppress read evidence.
- Audited Landlock and secure-exec consumers; both already use the platform constant. Updated the secure-exec test's independent combined `O_PATH | O_CLOEXEC` fixture to 2621440.
- Receive uses `MSG_CMSG_CLOEXEC`, rejects surplus/truncated/malformed/missing rights, and closes every complete delivered SCM_RIGHTS descriptor on rejection. Native ABI layouts and wire payloads are unchanged.
- UNIX address preparation precedes socket creation; acquisition failure closes the one owned socket and retains cleanup failures as suppressed evidence.

**Evidence:**

- Focused RED: 8 platform failures (including host C header oracle, real O_PATH semantics, native socketpair cases and acquisition cleanup) and 4 profiler failures. Focused GREEN passed after repair.
- Additional pathname/flag ambiguity regression failed, then passed after quoted-argument handling.
- `NativeBoundaryRepairTest`: 10 passing tests, including a fresh JVM O_PATH/read-EBADF test, real one/two/three-FD socketpair messages, CLOEXEC, truncation, malformed length, EINTR, missing rights and acquisition failures.
- `CAbiLayoutOracleTest`, `BobCompilerTest` (46), `StraceLogParserTest` (16), and `SecureExecRouteExecutorTest` passed. Full platform (304) and profiler (518) tests passed; platform Detekt and Gradle backlog-index validation passed.
- ADK syntax guards passed. Codanna impact evidence is PARTIAL because its index is missing. Full backlog validation remains blocked by unrelated plan documents without frontmatter; this issue's schema and index are validated separately.

---
<!-- id: issue-20260909-074606-812-correct-o-path-native-flag-value-against-linux-hea  file: issue-20260909-074606-812-correct-o-path-native-flag-value-against-linux-hea.md -->
