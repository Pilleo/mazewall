---
title: "Remove cold no-op callback linkage after seccomp installation"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "enforcer"
target_modules: [enforcer]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/InstallationBootstrapTest.kt"
target_symbols:
  - "applyBpfFilter"
open_questions: false
---

# 🟡 [Severity: HIGH]: Remove cold no-op callback linkage after seccomp installation

**Context:**
Retained hs_err_pid2602181.log identifies applyBpfFilter bytecode offset385 resolving invokedynamic after executable mappings have been denied. Source returns an unused AutoCloseable lambda only for unsupervised installs. A fresh-child real JFR ClassLoad regression run-e4e0403443c7 deterministically records ContainedExecutors Lambda loading after the mocked SECCOMP_SET_MODE_FILTER commit; native failure run-414d65775111 retains the actual EPERM CodeHeap expansion. This is one concrete path inside broader executable-mapping issue-20261015-120935-494.

**Needed:**
1. Return no supervisor lease for unsupervised installs rather than linking a dummy callback, retain the cold-boundary regression and actual protection/receipt/ABI gates, and do not weaken PROT_EXEC denial or claim arbitrary NATIVE_IMAGE-on-HotSpot safety.

**Resolution:** The private unsupervised path returns absence of a supervisor lease;
no callback is linked after installation. Fresh-child JFR red/green, real native
protection/retirement checks, receipt/failure matrices, ABI/lint/static analysis and
the entire native-enabled GraalVM build pass. See
[evidence](../../../plans/2026-10-17-containment-post-install-bootstrap-evidence.md).
Broader historical FutureTask/VarHandle paths remain under the existing parent
issue; this resolution makes no arbitrary strict-profile-on-HotSpot safety claim.

---
<!-- id: issue-20261017-001100-102-remove-cold-no-op-callback-linkage-after-seccomp-i  file: issue-20261017-001100-102-remove-cold-no-op-callback-linkage-after-seccomp-i.md -->
