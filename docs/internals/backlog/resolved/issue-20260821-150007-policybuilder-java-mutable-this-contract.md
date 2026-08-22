---
title: "Lock PolicyBuilder Java fluent contract (same instance, snapshot on build)"
severity: "LOW"
status: "resolved"
priority: medium
dependencies: []
component: "enforcer"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/test/kotlin/io/mazewall/PolicyBuilderContractTest.kt"
effort: "small"
autonomy: "autonomous"
---

# 🟢 [Severity: LOW]: Lock PolicyBuilder Java fluent contract (same instance, snapshot on build)

**Context:** The side-effect analysis wanted copy-on-write / immutable builders. That is a breaking Java API (`Policy.builder().block(...).build()` returns `this` today). Tests cannot ban a rewrite forever, but they can lock: (1) fluent methods return the **same** instance; (2) `build()` snapshots — mutating the builder after `build()` does not change the already-built `PolicyDefinition`.

**Needed:**
1. New test class `PolicyBuilderContractTest.kt`.
2. Do not change `PolicyBuilder` unless a test shows snapshot aliasing (the built definition sharing a mutable map with the builder). If aliasing exists, copy on `build()` — that is a bugfix, not an immutable-builder rewrite.

**New cases:**
- `val b = Policy.builder(); assertSame(b, b.block(Syscall.EXECVE)); assertSame(b, b.allow(Syscall.READ)); assertSame(b, b.defaultAction(SeccompAction.ACT_ERRNO))` using the real `PolicyBuilder` methods that exist.
- Snapshot: `val def1 = b.block(Syscall.EXECVE).build(); b.block(Syscall.CONNECT);` then `def1.syscallActions` does not contain the post-build `CONNECT` mutation as an added mapping that was absent at `def1` build time.
- `build()` twice after more mutations yields different definitions; the first remains stable.
- Do **not** require `PolicyBuilder` to be a `data class` or `value class`.

**Do not:**
- Convert `PolicyBuilder` to copy-on-write returning a new instance per call.
- Split packages into `pure/` vs `impure/` or add an effect-system framework.

**Verify:** `./gradlew :enforcer:test --tests io.mazewall.PolicyBuilderContractTest`
