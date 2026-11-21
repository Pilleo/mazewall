---
title: "Make ProcessFilterInstaller active denied-syscall self-verification opt-in so production startup is safe under strict outer seccomp and IDS"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "enforcer/src/main/kotlin/io/mazewall/seccomp/ProcessFilterInstaller.kt"
target_symbols: 
  - "ProcessFilterInstaller"
open_questions: false
---

# 🟡 [Severity: HIGH]: Make ProcessFilterInstaller active denied-syscall self-verification opt-in so production startup is safe under strict outer seccomp and IDS

**Context:**
ProcessFilterInstaller unconditionally runs InstallSelfVerifier on real production JVMs unless -Dio.mazewall.selfVerify=false, issuing synthetic denied syscalls (clone3, execveat, execve, memfd_create) that trigger outer KILL/TRAP seccomp profiles (SIGSYS) and false-positive Falco/auditd alerts at boot.

**Needed:**
1. Verify PR_GET_SECCOMP == 2 plus getpid() liveness by default in ProcessFilterInstaller, gate active InstallSelfVerifier denied-syscall probes on -Dio.mazewall.selfVerify=true, and document this behavior.

## Resolution Evidence (2026-11-21)
- Changed `ProcessFilterInstaller` default verification to check `prctl(PR_GET_SECCOMP) == 2` plus `getpid()` liveness without issuing synthetic denied syscalls; active `InstallSelfVerifier` denied-syscall probing is now opt-in via `-Dio.mazewall.selfVerify=true`.
- Updated `README.md`, `GETTING_STARTED.md`, `STABILITY.md`, and `CHANGELOG.md`.
- Verified both default lightweight installation and `-Dio.mazewall.selfVerify=true` opt-in subprocess installation in `MazewallInstallOnProcessTest` (`:lockdown:test`) and `MazewallLockdownIntegrationTest` (`:lockdown:integrationTestFreshJvm`).

- Full completion gate verified in commits `52e81d2e1` and `7e62bacf7` (`./gradlew build -PmazewallKernelLane=BASELINE` and rootful Docker `CONFINED` kernel lane passed).
---
<!-- id: issue-20261120-151759-970-make-processfilterinstaller-active-denied-syscall-  file: issue-20261120-151759-970-make-processfilterinstaller-active-denied-syscall-.md -->
