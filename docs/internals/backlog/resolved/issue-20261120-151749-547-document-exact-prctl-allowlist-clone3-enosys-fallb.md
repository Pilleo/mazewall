---
title: "Document exact prctl allowlist, clone3 ENOSYS fallback, and fork/vfork scope in Mazewall lockdown 0.1.0"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "lockdown/src/main/kotlin/io/mazewall/lockdown/ProcessPolicy.kt"
target_symbols: 
  - "ProcessPolicy"
open_questions: false
---

# 🟡 [Severity: HIGH]: Document exact prctl allowlist, clone3 ENOSYS fallback, and fork/vfork scope in Mazewall lockdown 0.1.0

**Context:**
ProcessPolicy.denyProcessCreation uses allowUnsafePrctl=false (restricting prctl to 6 safe options and returning EPERM on all other prctl options such as PR_GET_DUMPABLE) and Clone3Inspector (returning ENOSYS for clone3 so glibc falls back to clone), while README.md, GETTING_STARTED.md, CHANGELOG.md, and ProcessPolicy KDoc previously listed only the 5 blocked syscalls.

**Needed:**
1. Update ProcessPolicy KDoc, README.md, GETTING_STARTED.md, STABILITY.md, and CHANGELOG.md to document exact 0.1.0 prctl allowlist, clone3 ENOSYS fallback, and fork/vfork/clone retention semantics.

## Resolution Evidence (2026-11-21)
- Updated `ProcessPolicy` KDoc (`lockdown/src/main/kotlin/io/mazewall/lockdown/ProcessPolicy.kt`), `README.md`, `GETTING_STARTED.md`, `STABILITY.md`, and `CHANGELOG.md` to document the exact `0.1.0` filter contract: `EPERM` on `execve`/`execveat`/`memfd_create`/`io_uring_setup`/`io_uring_enter` plus non-allowlisted `prctl` subcommands (leaving `PR_SET_NAME`, `PR_GET_NAME`, `PR_GET_SECCOMP`, `PR_SET_SECCOMP`, `PR_SET_NO_NEW_PRIVS`, `PR_GET_NO_NEW_PRIVS` allowed), `ENOSYS` for `clone3` (glibc fallback to `clone`), and inherited retention of `fork`/`vfork`/`clone`.
- Verified kernel enforcement of `EPERM` on those 5 syscalls + `PR_SET_PDEATHSIG`, `ENOSYS` on `clone3`, and `PR_GET_SECCOMP == 2` in `MazewallLockdownIntegrationTest` (`:lockdown:integrationTestFreshJvm`).

- Full completion gate verified in commits `52e81d2e1` and `7e62bacf7` (`./gradlew build -PmazewallKernelLane=BASELINE` and rootful Docker `CONFINED` kernel lane passed).
---
<!-- id: issue-20261120-151749-547-document-exact-prctl-allowlist-clone3-enosys-fallb  file: issue-20261120-151749-547-document-exact-prctl-allowlist-clone3-enosys-fallb.md -->
