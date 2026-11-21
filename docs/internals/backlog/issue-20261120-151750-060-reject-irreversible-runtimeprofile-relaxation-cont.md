---
title: "Reject irreversible RuntimeProfile relaxation, contradictory vararg profiles, and re-check sticky installationFailure inside processLock"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "enforcer/src/main/kotlin/io/mazewall/seccomp/ProcessFilterInstaller.kt"
target_symbols: 
  - "Mazewall"
open_questions: false
---

# 🟡 [Severity: HIGH]: Reject irreversible RuntimeProfile relaxation, contradictory vararg profiles, and re-check sticky installationFailure inside processLock

**Context:**
Calling installOnProcess(HOTSPOT_JIT) after NATIVE_IMAGE silently returned filterDepth=1 while PROT_EXEC remained irreversibly blocked; passing conflicting RuntimeProfiles in one call silently intersected to NATIVE_IMAGE; installationFailure was checked outside synchronized(processLock).

**Needed:**
1. Reject conflicting RuntimeProfiles in Mazewall.installOnProcess, reject irreversible relaxation of allowMmapExec inside synchronized(processLock) without corrupting healthy process state, and re-check sticky installationFailure inside processLock.

## Progress Evidence
- Added conflicting `RuntimeProfile` rejection inside `Mazewall.installOnProcess`, plus synchronized `installationFailure` re-check and `IncompatiblePolicyException` handling (rejecting `allowMmapExec = true` after an installed `!allowMmapExec` baseline without poisoning healthy `processState`) inside `ProcessFilterInstaller`.
- Verified in `MazewallInstallOnProcessTest`, `LockdownJavaApiTest` (`:lockdown:test`), and `MazewallLockdownNativeImageIntegrationTest` (`:lockdown:integrationTestFreshJvm`).

---
<!-- id: issue-20261120-151750-060-reject-irreversible-runtimeprofile-relaxation-cont  file: issue-20261120-151750-060-reject-irreversible-runtimeprofile-relaxation-cont.md -->
