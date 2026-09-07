---
title: "Thread FileDescriptor through platform downcalls and classification"
severity: "HIGH"
status: "open"
priority: high
dependencies:
  - "issue-20260824-203500"
component: "platform"
target_modules:
  - ":platform"
target_files:
  - "platform/src/main/kotlin/io/mazewall/ffi/internal/SyscallInvoker.kt"
  - "platform/src/main/kotlin/io/mazewall/core/FileDescriptor.kt"
  - "platform/src/main/kotlin/io/mazewall/platform/seccomp/SupervisedKind.kt"
target_symbols:
  - "SyscallInvoker"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
has_side_effects: true

paperclip_issue_id: "e916b27e-3aa7-49ba-ba4c-bc3267cd3e8d"
paperclip_identifier: "MAZ-1170"
---

# 🔴 [Severity: HIGH]: Thread FileDescriptor through platform downcalls and classification

**Context:**
`FileDescriptor` and `SyscallNumber` already exist, but `SyscallInvoker` `ioctl`/`fcntl`, `FdEpoch.claimOpen`, and `SupervisedKind.classify(nr: Int)` still take raw `Int`. `classify` uses `else -> Unknown`, so a new supervised syscall number is not a compile break. Reviewers cannot tell an fd from a prot/flags integer at the call site.

**Progress:**
- ✅ `NativeEngine.ioctl`/`fcntl` already take `FileDescriptor`; `SyscallInvoker` keeps `Int` only at `invokeExact`
- ✅ `SupervisedKind.classify` takes `SyscallNumber`
- ✅ `FdEpoch.claimOpen` has a `FileDescriptor` overload; the `Int` table key stays internal

**Needed:**
1. Sweep remaining `classify(nr: Int)` wrappers (`SupervisorNotificationMachine`) onto `SyscallNumber` at their public edges.
2. Run `./gradlew :platform:test --tests io.mazewall.platform.seccomp.SupervisedKindTest`.

## Side effects
- ioctl fcntl FdEpoch and SupervisedKind.classify take FileDescriptor or SyscallNumber instead of Int

---

**Verification:** `./gradlew :tools:orchestrator:checkBacklog` plus the `verify_cheap` commands above (if any).

<!-- id: issue-20260907-080829  file: issue-20260907-080829-thread-filedescriptor-through-platform-downcalls-and-classif.md -->
<!-- Agent: fill Context and Needed; add files/symbols if the impact walk missed them. Do not rename the file. -->
