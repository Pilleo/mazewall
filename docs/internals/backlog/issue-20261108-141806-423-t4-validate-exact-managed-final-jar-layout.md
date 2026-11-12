---
title: "T4 validate exact managed final jar layout"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules:
  - "platform"
  - "enforcer"
target_files:
  - "platform/src/main/java/io/mazewall/ffi/internal/LiveStackBootstrap.java"
  - "enforcer/build.gradle.kts"
  - "platform/src/test/kotlin/io/mazewall/ffi/internal/LiveManagedLayoutTest.kt"
  - "enforcer/src/test/java/io/mazewall/ffi/internal/OwnedStackScopingPackagedFixture.java"
  - "enforcer/src/test/kotlin/io/mazewall/ffi/internal/OwnedStackScopingPackagedBridge.kt"
  - "enforcer/src/test/java/io/mazewall/ffi/internal/OwnedStackScopingPackagedWorkload.java"
  - "enforcer/src/test/kotlin/io/mazewall/ffi/internal/OwnedStackScopingPackagedAccessTest.kt"
target_symbols:
  - "LiveStackBootstrap"
open_questions: false
---

# 🟡 [Severity: HIGH]: T4 validate exact managed final jar layout

**Context:**
Parent issue 359: accepted T4 managed layout requires exact final-JAR role/order and CodeSource checks beyond the current ten-role helper-only checker.

**Needed:**
1. Implement the accepted fixed managed role/order/CodeSource validator against the real platform and enforcer main JARs, separately resolved main runtime dependencies and narrowly owned fixture JAR; preserve exact checked identity and loader/module/refusal boundaries.
2. Keep the current native projection exactly ten path/build pairs plus route mask, in its original order and length. No native parser, JNI/C ABI or native readiness change is authorized by this component; coordinate later T4 native expansion separately.
3. Exercise the actual private validator through a seven-final-JAR owned child, with valid baseline before discriminating order, substitution, CodeSource, manifest and duplicate-definition negatives. Preserve producer typed-Unsupported as access evidence only; no fake Ready, guard bypass or native qualification.
4. Bind resolved runtime dependencies and actual final artifact provenance/bytes; keep independent no-writer, actual B4 cold closure, final-byte audit and subsequent unchanged-byte qualification open. Run focused and build checks under reviewed exclusive release.

Parent issues 359/957; D4 issue 842 and existing 013/509/752/856/869/885/892 remain open. [Preparation](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b2-t4-managed-component-preparation086/plan.md). No implementation release.

## Binding component boundary

The [successor plan](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b2-t4-managed-component-preparation086/plan-fixed1.md) preserves zero declared helper fields and the original strict ten-role production callback/native 21-string projection. A finite new managed candidate runs a shared pure structural prefix then refuses Unsupported before witness loading/JNI when independent per-role deployment expectations are unavailable. The full seven-JAR validator is reflected only by the explicitly test-owned child with test fixture records, not a production authority source. No positive expanded T4 layout, NoWriters, Ready, or Live activation is authorized.

## Bounded component acceptance

The controller accepted this component **with explicit limits** after independent source and full-build evidence review. See the [088 controller decision](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b2-t4-managed-component-full-build088/controller-decision.md) and [execution report](../../../.superpowers/sdd/2026-11-03-tier-e-improvement-roadmap/b2-t4-managed-component-full-build088/report.md). The full build exercised 19 outer test producers: 4,479 executed, 45 skipped and no failures/errors. The fixture JAR was a build producer, not another test producer. The seven-JAR component suite executed 13 tests; its child and pump exit receipts are recorded, but the single early descendant snapshot does **not** prove complete descendant retirement.

This issue remains open for broader qualification. Test-supplied digest/build/source records do not establish independent deployment provenance, authorized pins or a NoWriters lifetime. The finite production candidate continues to refuse before managed I/O, witness loading or JNI when trusted expectations are absent; no native Ready or Live activation follows. The qualified old JNI callback, expanded T4 native projection/parser, final distribution and affected native/load/provider/ZIP/Cleaner/JFR audits remain open. Captured-engine counted pre-filter helper authority under issue 842, B4 cold closure, both D4 guards and B6 remain open. Daemon descriptor EBADF under issue 869 and profiler/listener ENOSYS/retirement under issue 752 remain Unknown. The 45 skipped cases are not execution qualification.

---
<!-- id: issue-20261108-141806-423-t4-validate-exact-managed-final-jar-layout  file: issue-20261108-141806-423-t4-validate-exact-managed-final-jar-layout.md -->
