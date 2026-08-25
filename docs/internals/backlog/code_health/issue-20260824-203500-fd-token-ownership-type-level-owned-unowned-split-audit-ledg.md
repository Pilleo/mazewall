---
title: "FD token ownership: type-level Owned/Unowned split + audit ledger + literal-int sweep"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "platform"
target_modules:
  - ":platform"
target_files:
  - "platform/src/main/kotlin/io/mazewall/core/FileDescriptor.kt"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
paperclip_issue_id: c326a656-da01-4a95-83a6-1c31ba0a3e2d
---

# 🔴 [Severity: HIGH]: FD token ownership: type-level Owned/Unowned split + audit ledger + literal-int sweep

**Context:** FileDescriptorTest/FileDescriptorReproductionTest minted tokens around invented
integers (10, 90, ...) and closed them - real close(int) syscalls in the shared worker JVM.
The lazily opened /dev/urandom fd of NativePRNG was destroyed, so every later SecureRandom user
(JUnit @TempDir via Files.createTempDirectory) failed with EBADF; gradle worker pipes were also
hit, killing unrelated test batches. Failures moved nondeterministically between call sites.
Production minting is currently clean (all from owned SafeLocalFd results), but the API surface
(generic()/unsafe()) permits this class of bug and 61 literal-int minting sites remain across
enforcer/profiler/platform tests. Full incident write-up:
docs/internals/designs/core/fd-token-ownership.md
**Needed:**
1. Type-level split: generic()/unsafe() return an Unowned token without close();
   only open*/adopt/replace/claimDupIfNeeded yield Owned tokens with close rights.
2. FdEpoch audit ledger (mazewall.fd.audit=true): verify target liveness via
   fcntl(F_GETFD) before close; log closes of fds never opened through the epoch.
3. Sweep and classify all 61 literal-int minting sites in tests (equality-only
   assertions are safe; anything calling close() must use owned integers).
4. Adopt ForeignFdGuard (platform test sources) across enforcer/profiler suites.
5. Audit pid-handle discipline analogously (invented pids + signal-bearing syscalls).
