---
title: "Bind native open arguments to the live publication"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: ["profiler-native"]
target_files:
  - "profiler-native/include/live_stack_context.hpp"
  - "profiler-native/src/live_stack_context.cpp"
  - "profiler-native/include/live_stack_jni.hpp"
  - "profiler-native/src/live_stack_jni.cpp"
  - "profiler-native/src/agent.cpp"
  - "profiler-native/tests/live_stack_context_test.cpp"
  - "profiler-native/tests/live_stack_agent_test.cpp"
target_symbols:
  - "publish"
open_questions: true
---

# 🟡 [Severity: MEDIUM]: Bind native open arguments to the live publication

**Context:**
The controller has released this bounded first increment for TDD implementation after independent corrected-plan review. This release is not an implementation-completion claim or authorization for a bridge, self-read service, or activation. The open problem is provenance: bind the scalar arguments from the actual private `Open0`/`Openat0` proxy invocation into the existing live publication before any later read, without expanding trust. Follow the seven existing native files listed above. Modify `profiler-native/CMakeLists.txt` only if a new test registration is later required; do not expand default targets. The scoped proposal is in the [native argument provenance preparation plan](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/b6-native-argument-provenance-preparation001/implementation-plan.md); the reviewed formal execution plan is [released for this bounded increment](../../../docs/superpowers/plans/2026-11-14-tier-e-b6-native-argument-provenance.md) (plan SHA-256 `320f8066a8f7651a3ff2a4f08718013d91132e07a8979b5dd72a88484d230830`; independent review SHA-256 `466d17486b5996197ff6d499cc70c56876c4ac31eb556de2f1375ddcdfbb0653`).

Preserve the existing `copy_v1` ABI, exports, header layout, `NativeEngine`, diagnostics/ring/logs, defaults, and closed guards. Do not add pointer output, memory reads, a bridge, effects, trust expansion, dependencies, or production activation. The receiver-retirement prerequisite was accepted under issue 094/full002, while issue 094 remains open for broader B6 scope. A future Match result would establish only the stated private scalar-to-publication association; it would not prove notification/register provenance, JNI-to-syscall routing, pathname immutability, daemon custody, or authorize self-read.

**Needed:**
1. **PREPARATION / REVIEW — complete for this bounded increment:** the formal plan and independent corrected-plan review are accepted; controller release is recorded above. The read-only baseline is proposal005 with 32/32 tests, including seven JVM/FIFO prerequisites; the recorded DSO SHA-256 is `e28d673eb0e95ff2189d70c6b62cb3b69699b865e3ff39ffc9724e2fc62f3585`. This baseline is not evidence for implementation correctness.
2. **TDD / RED — released, execution pending:** frozen RED tests are ready to exercise actual `Open0` and `Openat0` proxy publication and core matching. A brief RED attempt was physically retired at 18:02:24 UTC with source/index unchanged; its result must be reported from its own packet. Separate compile/link failures from behavioral RED, and preserve original arguments, errno, return, and throwable behavior.
3. **GREEN after release:** implement the private record/matcher and carry actual proxy scalar bits through `InvocationScope`, `publish_capture`, and the existing publication. Preserve existing `copy_v1` behavior and all non-proxy callers.
4. **Native boundary checks:** verify behavior, layouts, existing ABI and exports remain unchanged; add CMake registration only if needed and without default-target expansion.
5. **Review and completion gate:** obtain independent source/security review and run the owning native/ABI checks followed by one coherent full gate. Keep implementation unreleased until a separate controller decision accepts the evidence.

---
<!-- id: issue-20261114-174301-368-bind-native-open-arguments-to-the-live-publication  file: issue-20261114-174301-368-bind-native-open-arguments-to-the-live-publication.md -->


## Bounded private native provenance accepted with limits — 2026-11-14

Root accepted `ACCEPT_PRIVATE_NATIVE_ARGUMENT_PROVENANCE_WITH_EXPLICIT_LIMITS`; see the [portable evidence](../evidence/tier-e-b6-native-argument-provenance-20261114.md), [controller decision](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/b6-native-argument-provenance-implementation001/controller-decision-20261114-001.md), and [independent full audit](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-manager-retirement-preparation/confined-handoff-step2-001/b6-native-argument-full-independent-audit-20261114-001.md). This accepts the private core publication/matcher and actual Open0/Openat0 proxy wiring within the seven existing target files. The hidden matcher returns only Match/Unavailable for exact validated invocation, publication, binding, and scalar inputs. No bridge, memory read, pointer output, native export, public API, permission, effect, guard change, or activation is included.

The reported GREEN010 selected native tests passed 32/32; full Gradle passed 4,823 tests, 45 skips, zero failures/errors. See evidence for the separate native/full graph distinction, symbol/layout comparison, audit, and limitations. Issue 368 remains OPEN and unarchived to retain those qualification limits. This issue's scope does not include future bridge implementation; any later native boundary requires a separate concrete issue/release. Existing release constraints and all historical attempts remain unchanged.
