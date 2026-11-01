---
title: "Verify Spring thread dispatch with real owned-runtime host contracts"
severity: "LOW"
status: "open"
priority: low
dependencies: ["issue-20261019-064750-154-add-host-contract-coverage-for-koog-thread-backed-"]
component: "testing"
target_modules: []
target_files:
  - "integrations/spring/mazewall-spring-boot/src/test/java/io/mazewall/spring/ManagedRuntimeDispatcherTest.java"
target_symbols:
  - "ManagedRuntimeDispatcher"
open_questions: false
needs_kernel: false
core_lock: false
effort: "small"
autonomy: "supervised"
has_side_effects: false
---

# 🟡 [Severity: LOW]: Verify Spring thread dispatch with real owned-runtime host contracts

**Context:**
Spring already implements synchronous thread-backed operations through ManagedRuntimeDispatcher; do not add a duplicate API. Existing advisor/lifecycle host characterization and native consumers should be complemented by a fast dispatcher suite using actual ManagedRuntime domains and the repository-only native fixture. Keep native enforcement qualification separate.

**Needed:**
1. Reuse the existing shared mock-runtime fixture without production seams or dependency additions; test actual worker placement and caller-side per-invocation context capture.
2. Cover original body failure identity, wrong-owner and closed-owner rejection, and interrupted-caller cancellation with admission retained until explicit cleanup release.
3. Use finite waits, release all held work in finally, require complete drain, and run the optional Spring host suite.

## Verified local implementation

Spring already supplied the requested thread-backed API. Added six host
dispatcher contracts rather than a duplicate feature, reusing the shared fixture
from the dependency above. They cover caller-side context capture, owned-platform
placement, approved same-domain inline nesting, original failure identity,
wrong/closed owners and interruption with retained physical cleanup.

At the initial checkpoint the complete Spring host suite passed110 tests with no failures/errors/skips;
combined Koog/Spring configuration-cache reuse passes. Removing context capture
fails its representative test. Twenty repeated class runs pass120 cases with no
owned manager/domain threads remaining after any run. The production dispatcher
was restored byte-for-byte after the mutation. Independent review found no
remaining test defects.

Evidence: `.superpowers/sdd/2026-10-19-spring-thread-host-contracts/` and
`.superpowers/sdd/2026-10-19-thread-contract-stress/spring/`. This does not replace
native JDBC/Security adoption evidence. Keep open for scoped integration. The
later analyzer correction and full optional build now pass; the Spring suite has
113 tests including three separately scoped origin-comparison contracts. See
`.superpowers/sdd/2026-10-19-strict-analyzer-repair/completion-receipt.json`.

---
<!-- id: issue-20261019-070717-313-verify-spring-thread-dispatch-with-real-owned-runt  file: issue-20261019-070717-313-verify-spring-thread-dispatch-with-real-owned-runt.md -->
