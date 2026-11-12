---
title: "Record Tier E LSM fingerprints on kernels without process labels"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files:
  - "profiler/src/test/resources/tier-e-a4-fingerprints.py"
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/TierEAllTargetDaemonIntegrationTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/testing/tierE/TierEA4DiagnosticSupportTest.kt"
  - "docs/internals/designs/profiler/tier-e-design.md"
  - "docs/internals/backlog/README.md"
  - "docs/internals/backlog/issue-20261104-092359-647-record-tier-e-lsm-fingerprints-on-kernels-without-.md"
target_symbols:
  - "main"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Record Tier E LSM fingerprints on kernels without process labels

**Context:**
Cycle003 passed BPF loading and mapped-object fingerprints but failed preREADY on /proc/<owned>/attr/current EINVAL. The host active LSM list is capability,landlock,lockdown,yama,bpf; these do not supply a textual process label. Host self and a fresh owned collector-equivalent probe reproduce EINVAL. The probe also proves securityfs lsm is not mounted inside the container, so host kernel inventory must be captured explicitly rather than assumed there.

**Needed:**
1. Record the host active LSM inventory with owned process label observations, accepting EINVAL only for an explicitly recognized nonlabeling inventory and retaining unavailable status/errno. Preserve permission/unknown-provider failures and qualify with the unchanged fresh diagnostic.

**Host evidence and scope:** Kernel 7.2.11-x64v3-xanmod1 reported the inventory above; securityfs was unavailable inside the owned collector-equivalent probe. This is not evidence of absent LSMs, absent Landlock, or an unconfined process. Scope is the six paths in the metadata: private fingerprint resource and caller, focused tests, design, this issue and index. Keep open until independent review and the fresh unchanged diagnostic qualify the change; host checks do not resolve the missing-open cause.

---
<!-- id: issue-20261104-092359-647-record-tier-e-lsm-fingerprints-on-kernels-without-  file: issue-20261104-092359-647-record-tier-e-lsm-fingerprints-on-kernels-without-.md -->
