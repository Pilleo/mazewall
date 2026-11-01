---
title: "Recognize boxed Boolean is-accessors in Portal POJO boundaries"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "codegen"
target_modules: [":portals:codegen"]
target_files:
   - "portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/BoundaryTypes.kt"
   - "portals/codegen/src/test/kotlin/io/mazewall/portal/codegen/PortalCodegenFunctionalTest.kt"
   - "docs/internals/designs/enforcer/process-portal-design.md"
target_symbols:
  - "readableAccessor"
open_questions: false
needs_kernel: false
core_lock: false
effort: "small"
autonomy: "supervised"
has_side_effects: false
---

# 🟡 [Severity: MEDIUM]: Recognize boxed Boolean is-accessors in Portal POJO boundaries

**Context:**
Recursive SpotBugs analysis exposed a repeated Boolean condition. In Kotlin Boolean::class.java and Boolean::class.javaPrimitiveType both denote primitive boolean, so the existing disjunction never admits java.lang.Boolean isX readers. Fix the boxed alternative rather than weakening analysis or changing the protocol.

**Needed:**
1. Add an actual Java boxed-Boolean constructor-backed POJO with only an isX reader to existing codegen tests; observe rejection before correction and retain primitive/nonboolean reader guards.
2. Change only the boxed Boolean alternative to javaObjectType and verify actual generated codec or binding behavior using the existing codegen harness.

## Verified local correction

The real Java POJO fixture initially failed generation because its boxed-Boolean
`isBoxedEnabled()` reader was not recognized. Replacing only the duplicate
primitive-type alternative with `Boolean::class.javaObjectType` makes generated
sources compile and round-trip both Boolean forms and both values. Boolean wire
encoding is unchanged. The complete codegen host suite passes47 tests, its analyzer
passes, and the full optional build exits0. Evidence:
`.superpowers/sdd/2026-10-19-analyzer-gates-resumed/boolean-red.log`,
`codegen-suite.log`, and
`.superpowers/sdd/2026-10-19-strict-analyzer-repair/completion-receipt.json`.
Keep open for scoped integration with recursive analyzer qualification.

---
<!-- id: issue-20261019-093313-349-recognize-boxed-boolean-is-accessors-in-portal-poj  file: issue-20261019-093313-349-recognize-boxed-boolean-is-accessors-in-portal-poj.md -->
