---
title: "Bind checked live bootstrap to original request and runtime references"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: [platform, enforcer]
target_files:
  - "platform/src/main/kotlin/io/mazewall/ffi/internal/RealNativeStackContexts.kt"
  - "platform/src/test/kotlin/io/mazewall/ffi/internal/RealNativeStackContextsTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/ffi/internal/OwnedStackScopingPackagedBridge.kt"
  - "enforcer/src/test/kotlin/io/mazewall/ArchitectureTest.kt"
target_symbols:
  - "RealNativeStackContexts"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Bind checked live bootstrap to original request and runtime references

**Context:**
Scoped child of issues 842 and 885: preserve existing live bootstrap while representing an actually checked native adapter bound to exact original identity and runtime references.

**Needed:**
1. Implement typed checked/refused outcomes bound to the exact original request and runtime references, preserving the old bootstrap descriptors, default Unsupported behavior, JNI check/lookup ordering and raw failure causes.
2. Audit actual JVM constructors, synthetic bridges and sealed implementation visibility. Exercise a genuine protected main-JAR/Only-DSO positive with distinct equal request and distinct snapshot refusals before a single Reserve and retirement.
3. Keep helper and canonical acquisition association separate; add no public authority provider, native ABI change or activation claim. Existing issues 842 and 885 remain open.

---
<!-- id: issue-20261111-204426-270-bind-checked-live-bootstrap-to-original-request-an  file: issue-20261111-204426-270-bind-checked-live-bootstrap-to-original-request-an.md -->
