---
title: "Synchronize Tier E symbolization epochs with installed class replacements"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules:
  - "profiler"
  - "profiler-native"
target_files: 
  - "profiler-native/src/agent.cpp"
target_symbols: 
  - "InvocationScope"
open_questions: false
---

# 🟡 [Severity: HIGH]: Synchronize Tier E symbolization epochs with installed class replacements

**Context:**
A ClassFileLoadHook notification precedes replacement installation, so a monotonic epoch alone does not prove that captured frames belong to the installed class version.

**Needed:**
1. Define the replacement transition protocol and exercise capture during a paused transformation with old frames still active.

## Acceptance criteria

1. The epoch transition has a defined relationship to replacement installation, not merely hook notification.
2. Captures during an unproven transition are unresolved or causal-uncertain and cannot be published under the replacement epoch.
3. Old active frames and new invocations remain distinguishable across retransformation.
4. Logical stack bytes remain stable for equivalent installed versions and distinct for non-equivalent versions.

## Required verification

1. Pause `ClassFileLoadHook` between notification and installation while workload threads capture old and new frames.
2. Assert no cross-version cache reuse and retain before/transition/after dictionary records.
3. Report `dictionary_integrity`, `attribution_coverage`, and `lifecycle_validity` independently.

Verification command: `cmake -S profiler-native -B build/tier-e-epoch -DCMAKE_BUILD_TYPE=Debug && cmake --build build/tier-e-epoch -j2 && ctest --test-dir build/tier-e-epoch -V -R redefinition`.

---
<!-- id: issue-20260913-024413-834-synchronize-tier-e-symbolization-epochs-with-insta  file: issue-20260913-024413-834-synchronize-tier-e-symbolization-epochs-with-insta.md -->
