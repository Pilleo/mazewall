---
title: "Allow bounded result and execution-end overlap in Java metadata fixture"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: [":enforcer"]
target_files:
  - "enforcer/src/test/java/io/mazewall/MazewallJavaApiTest.java"
target_symbols: ["applicationDeclaredMetadataUsesExistingUncastCallableEntry"]
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Allow bounded result and execution-end overlap in Java metadata fixture

**Context:**
Exact CI38831550610 fails the second call at MazewallJavaApiTest.java154 with
Domain admission limit reached. The fixture uses the synchronous uncast Callable
entry, which returns a result independently of executionEnd/admission release.
Its one worker correctly preserves sequential context restoration, but one
admission can still be occupied when the next call arrives. This is the same
established result/end distinction already retained in the Kotlin fixture.

**Needed:**
1. Preserve the one physical worker, synchronous Callable entry and all captured-value/caller-context/worker-context assertions; allow exactly two bounded admissions for the two result calls. Do not change runtime limits, completion order or admission logic.

## Evidence

Full remote XML RED retained locally (`run-cfad8dac64aa`) names precisely the
second call's RejectedExecutionException, not containment/setup failure. Only the
fixture limit and explanatory comment change.

## Resolution

The complete Java API test class passes (`run-3b1c35d7b381`), preserving captured
immutable metadata, caller context and the next call's empty worker context.
The same physical worker runs both operations; two admissions permit the
documented result/end overlap. Full included-module build passes
(`run-c00cfdbbd9f3`,415054ms). Connected review confirms no assertion, runtime
quota, completion order, Callable signature, timeout or production code change.
The new exact-head remote result remains the merge gate.

---
<!-- id: issue-20261016-010021-700-allow-bounded-result-and-execution-end-overlap-in-  file: issue-20261016-010021-700-allow-bounded-result-and-execution-end-overlap-in-.md -->
