---
title: "Bind supported exploded and Boot deployment origins to genuine prepared owners"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "enforcer"
target_modules: [enforcer]
target_files:
  - enforcer/src/main/kotlin/io/mazewall/ffi/internal/ImplementationArtifact.kt
  - enforcer/src/main/kotlin/io/mazewall/enforcer/api/execution/PreparedOperationResources.kt
target_symbols: [ImplementationArtifact, PreparedOperationResources]
open_questions: false
---

# [Severity: HIGH]: Bind supported exploded and Boot deployment origins to genuine prepared owners

**Context:**
Reviewed Task5 ordinary-JAR binding rejects actual executable Boot/exploded target classes.
C4/C6 require actual artifact/classloader/resource closure, exact grants and physical
resource retirement. Labels or a newly generated digest/document do not establish review.
Task8 owns enforcer only; Spring/Portal/native layouts/root configs/index are excluded.

**Needed:**
1. Preserve typed resolve/prepare signatures and ordinary custom-system-loader rejection.
2. Bind complete bounded exploded roots under explicit directory grants, genuine directory/
   leaf owners and selected loader bytes; reject descendant symlinks, changes and unbounded IO.
3. Bind original supported Boot4.1.1 launcher hierarchy/bytes and actual application/stored
   nested-library selected origins/loader inputs to the genuine finished outer JAR owner.
4. Verify real packaging/mutations/rejection and both native installed workers, sibling
   denial, incomplete drain, physical retirement, fatal-first tree release and rollback.
5. Preserve nonblocking retirement and coherent late-failure drain publication unchanged.
6. Retain explicit mutable deployment/redefinition/shared-heap/already-open-FD limits.

**Implementation/evidence:** scoped Task8 code and fixtures; initial packaging RED
run-cbf33f9df1f7, focused host/ABI/quality/native GREEN run-c0f575db2138 (13 native,
zero skips). Later focused final evidence and commit will be appended to shared Task8
report/checkpoint; this issue stays open pending independent review and combined acceptance.
Supported closure/byte grammar and loader-release bounds: prepared-resources-design.md.
No declaration that combined framework/publication or physical bytecode-attestation gates pass.

**Coordinator index proposal:** add this open HIGH enforcer issue; open count +1.
Task writer intentionally does not edit backlog README/index.

---
<!-- id: issue-20261008-072510-298-bind-supported-exploded-and-boot-deployment-origin  file: issue-20261008-072510-298-bind-supported-exploded-and-boot-deployment-origin.md -->
