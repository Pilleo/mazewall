---
title: "Dispatch legal Object-name Portal service overloads to the guest"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "portal-runtime"
target_modules: [":portals:runtime", ":portals:codegen"]
target_files:
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/PortalRuntime.kt"
target_symbols:
  - "get"
open_questions: false
---

# 🟡 [Severity: HIGH]: Dispatch legal Object-name Portal service overloads to the guest

**Context:**
Repository-wide review, 2026-11-04: [PRT-05](../../reviews/2026-11-04-repository-review-findings.md#prt-05). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P1 · generated-contract blocker · NEW · source-confirmed and reproduced against existing bytecode · release boundary: PortalRuntime bindings.** PRT-05 and PCG-11 share this owner: accepted service operations never reach the worker. The consolidated priority is P1/high for the advertised generated contract, rather than the runtime lane's narrower P2 API assessment.

**Trigger:** A valid service declares `String toString(int)`, `boolean equals(int)`, or `int hashCode(int)`.

**Evidence:** `PortalRuntime.kt:61–75` dispatches solely on method name. `toString`/`hashCode` overloads with parameters return null; every one-parameter `equals` uses proxy reference equality. These calls never enter the delegate or `statefulGate`. The schema compiler accepts non-Object interface methods (`portals/codegen/src/main/kotlin/io/mazewall/portal/codegen/PortalServiceSchema.kt:25–35`), including these supported primitive/String shapes.

**Effect:** Service operations silently return the wrong result or fail during primitive unboxing, without any worker invocation. A legitimate `equals(int)` returning true instead returns false. This affects stateless and singleton bindings independently of the native transport.

**Reproduction:** `10-PortalProxyNameProbe.java` invokes the actual compiled `PortalRuntime$get$1` invocation-handler helper through a real JDK proxy, with a counting service delegate. It constructs no runtime/broker/worker. Output:

```text
toString(int): null, expected remote-7
equals(int): false, expected true
hashCode(int): NullPointerException, expected 8
Object-method controls: PASS; service delegate calls: 0
REPRODUCED against existing bytecode; source dispatch branches checked separately
```

The helper's bytecode was checked with `javap -p -c` and agrees with the cited current branches. This is auxiliary existing-artifact evidence, not a fresh module build or generated-consumer round trip.

**Smallest complete correction:** Handle only actual `Object` methods (for a JDK proxy, check `method.declaringClass == Object/Any`, or exact Object signatures), and forward all other interface methods through the existing open/stateful/delegate path. Do not reserve three entire method names or expand the proxy architecture.

**Regression:** Generate and invoke a service containing these overloads through `PortalRuntime.get`; prove each reaches the delegate/worker exactly once and returns its real value. Retain local identity behavior for actual `equals(Object)`, `hashCode()`, and `toString()`, plus closed-runtime and singleton gate controls for the overloads.

**Mapping/dependencies:** No matching active/resolved issue found for proxy Object-method/name collisions. Coordinate the consumer fixture with lane 11; the fix belongs to runtime, not schema rejection or the known CG01 boundary-type bug. No prerequisite feature is needed.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

## Resolution Evidence (2026-11-21)
- Updated `PortalRuntime.createServiceProxy` in `PortalRuntime.kt` to match only `equals(Object)`, `hashCode()`, and `toString()` locally on the proxy and route overloaded `equals(Int)`, `hashCode(Int)`, and `toString(Int)` through `statefulGate` / `ensureOpen()`, verified in `PortalRuntimeTest`.

---
<!-- id: issue-20261103-235254-202-dispatch-legal-object-name-portal-service-overload  file: issue-20261103-235254-202-dispatch-legal-object-name-portal-service-overload.md -->
