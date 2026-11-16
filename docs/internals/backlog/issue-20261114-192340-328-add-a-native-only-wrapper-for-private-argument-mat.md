---
title: "Add a native-only wrapper for private argument matching"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - "issue-20261114-174301-368"
component: "profiler"
target_modules:
  - "profiler-native"
target_files:
  - "profiler-native/include/live_stack_match_abi.h"
  - "profiler-native/src/live_stack_context.cpp"
  - "profiler-native/tests/live_stack_context_test.cpp"
target_symbols:
  - "mazewall_live_match_arguments_v1"
open_questions: true
---

# 🟡 [Severity: MEDIUM]: Add a native-only wrapper for private argument matching

**Context:**
This is **PLANNING / INDEPENDENT REVIEW ONLY**. Implementation is not released. The fixed2 proposal is scoped to exactly three files: one new `profiler-native/include/live_stack_match_abi.h`, `profiler-native/src/live_stack_context.cpp`, and the existing `profiler-native/tests/live_stack_context_test.cpp`. The proposed boundary adds exactly one default-visible status-only C export, `mazewall_live_match_arguments_v1`, around the accepted hidden matcher. The new header is currently absent.

See the [fixed2 implementation plan](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/b6-native-match-wrapper-preparation001/implementation-plan-fixed2.md), [accepted prerequisite issue 368](issue-20261114-174301-368-bind-native-open-arguments-to-the-live-publication.md), and [accepted prerequisite decision](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/b6-native-argument-provenance-implementation001/controller-decision-20261114-001.md). The separate [bridge security review](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/b6-native-argument-provenance-implementation001/optional-match-bridge-independent-security-review-20261114-001.md) constrains a later platform adapter but releases no platform work here.

The proposed 128-byte request and 16-byte result are 8-byte aligned. Validate the output pointer/alignment/size and checked `uintptr_t` input/output ranges first; overflow or overlap must return Malformed without reading input or writing output. For safe valid output, malformed input receives a fully initialized Malformed result; well-formed stale or mismatched identity returns Unavailable. Reject zero process cookie/start ticks and `tgid`/`tid` outside `1..INT32_MAX`; nonzero high-bit cookie/start values are valid format. Require exact embedded v1 copy form, nonzero invocation/publication, zero reserved fields, valid kind, and zero absent Open0 dirfd. Never dereference or output `path_bits`; the exception barrier does not make arbitrary pointers safe.

Preserve `live_stack_context_abi.h`, all v1 structures/layouts/statuses/copy behavior, every existing export name/type/binding/visibility/version, and all NEEDED entries. The only allowed export delta is this one symbol. No platform, JNI/FFM, NativeEngine, enforcer, consumer, read, pointer/scalar output, permission/effects, guard change, activation, diagnostics/ring change, dependency, CMake edit, or default-target expansion is included. The native result is not kernel-request provenance, permission, or continuing authority. Both Live guards remain closed.

**Needed:**
1. **PLANNING / REVIEW — current status:** independently review the exact ABI, pointer-range/alias/overflow ordering, output initialization, status mapping, exception contract, and separate boundary decision. Implementation remains unreleased until a separate controller release.
2. **Honest RED after release:** test the actual wrapper from the existing context target; distinguish compile/link RED from behavior, without stubs or manufactured failures.
3. **GREEN after release:** implement only the new header, wrapper in `live_stack_context.cpp`, and tests in `live_stack_context_test.cpp`; call the hidden matcher exactly once only for well-formed input.
4. **Boundary and compatibility checks:** exercise malformed/aliased/overflowed buffers, canaries, strict cookie/start/tgid/tid, Match/Unavailable/Malformed, v1 compatibility, and precisely one new export with existing NEEDED entries unchanged. Reuse existing context/export tests; do not edit CMake.
5. **Completion:** run the 32 selected native CTests and full Gradle gate, submit independent source/binary review, then await a separate controller acceptance. No platform adapter, bridge, memory read, guard movement, or B6 activation follows automatically.

---
<!-- id: issue-20261114-192340-328-add-a-native-only-wrapper-for-private-argument-mat  file: issue-20261114-192340-328-add-a-native-only-wrapper-for-private-argument-mat.md -->


## Bounded native-only match wrapper accepted with explicit limits — 2026-11-14

Root accepted `ACCEPT_NATIVE_ONLY_MATCH_WRAPPER_WITH_EXPLICIT_LIMITS`; see the [portable evidence](../evidence/tier-e-b6-native-match-wrapper-20261114.md), [controller decision](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/b6-native-match-wrapper-implementation001/controller-decision-20261114-001.md), [independent native audit](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-current-interface-preparation/b6-native-match-wrapper-preparation001/independent-native-audit-20261114-001.md), and [independent full audit](../../../.superpowers/sdd/2026-11-08-tier-e-remaining-work/task2-manager-retirement-preparation/confined-handoff-step2-001/b6-native-match-wrapper-full-independent-audit-20261114-001.md). The acceptance covers exactly the new native header, wrapper in `live_stack_context.cpp`, and existing context test, with one additive status-only native ABI symbol. v1 ABI/layout/validation/copy behavior remain unchanged; this is not a blanket no-ABI-change claim.

GREEN002 passed 32/32 selected native CTests; the full Gradle gate passed 4,823 tests, 45 skips, zero failures/errors. The portable evidence records the distinct native/full gate sources, export/NEEDED comparisons, exception limits, and qualification boundaries. Issue 192340 remains OPEN and unarchived for qualification limits. Its scope does not include any future platform adapter or bridge; such work requires separate issue/review/release. The deferred duplicate issue 192453 remains unchanged.
