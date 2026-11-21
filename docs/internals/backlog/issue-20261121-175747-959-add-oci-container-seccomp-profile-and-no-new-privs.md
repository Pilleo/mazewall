---
title: "Add OCI container seccomp profile and no_new_privs matrix integration tests for mazewall-lockdown"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files: 
  - "lockdown/src/integrationTest/kotlin/io/mazewall/lockdown/MazewallLockdownIntegrationTest.kt"
target_symbols: 
  - "MazewallLockdownIntegrationTest"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Add OCI container seccomp profile and no_new_privs matrix integration tests for mazewall-lockdown

**Context:**
Post-0.1.0 verification enhancement: execute mazewall-lockdown under Docker/OCI default seccomp, allowPrivilegeEscalation=false (pre-set no_new_privs with PR_SET_NO_NEW_PRIVS blocked), and restricted outer seccomp profiles blocking seccomp(2) to verify container diagnostics and fail-closed behavior end-to-end in CI containers.

**Needed:**
1. Add containerized kernel matrix tests exercising pre-locked no_new_privs, EPERM/EACCES/EINVAL seccomp outer-filter failures, and diagnostic output verification.

---
<!-- id: issue-20261121-175747-959-add-oci-container-seccomp-profile-and-no-new-privs  file: issue-20261121-175747-959-add-oci-container-seccomp-profile-and-no-new-privs.md -->
