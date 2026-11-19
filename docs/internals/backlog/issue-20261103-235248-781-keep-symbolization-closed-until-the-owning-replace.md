---
title: "Keep symbolization closed until the owning replacement completes"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [":profiler"]
target_files:
  - "profiler-native/include/symbolization_generation.hpp"
target_symbols:
  - "SymbolizationGeneration"
open_questions: false
---

# 🟡 [Severity: HIGH]: Keep symbolization closed until the owning replacement completes

**Context:**
Repository-wide review, 2026-11-04: [TE-03](../reviews/2026-11-04-repository-review-findings.md#te-03). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P1 · confirmed source defect; production-header state witnesses reproduced · high confidence · release boundary: advertised cooperative replacement/exact symbolization support. Existing resolved issue needs reopening/expansion.**

- **Trigger 1:** start cooperative retransformation and pause before its installation/return; prepare any unrelated class. `profiler-native/src/agent.cpp:1396–1397` unconditionally invokes `complete_replacement()` for every ClassPrepare callback. `profiler-native/include/symbolization_generation.hpp:47–51` then increments the generation and makes capture stable. ClassPrepare is not completion evidence for an unrelated outstanding retransformation.
- **Trigger 2:** caller A begins replacement; caller B begins while A owns it. `agent.cpp:1904–1912` sets B's thread-local cooperative flag before ignoring failed acquisition. B's later end therefore completes A's transition. `symbolization_generation.hpp:36–40` also uses a load/store admission pair rather than exclusive acquisition, so concurrent starts can both succeed. An external replacement observed during an existing transition is likewise not preserved by the failed begin.
- **Consequence:** captures can be admitted and cached as a stable installed generation while a replacement is still in progress. Lease equality cannot repair an incorrectly reopened state. This contradicts `docs/internals/designs/profiler/tier-e-design.md:1968–1999` and `2343–2367`. Wrong live JVM frames were not reproduced; premature state admission was.
- **Smallest complete correction:** remove unrelated ClassPrepare completion of cooperative transitions; give begin/end explicit exclusive ownership and close only after the owning transform returns. Preserve overlapping/unbracketed replacement as unproven instead of losing that invalidation. A CAS alone does not fix callers that ignore failed acquisition.
- **Regression:** pause a transformation, prepare an unrelated class, overlap cooperative callers and an external replacement, then capture before/during/after. Assert no exact generation is admitted before all relevant installation uncertainty is resolved, and old/new frame metadata cannot share the wrong generation. `profiler-native/tests/symbolization_generation_test.cpp:5–28` is sequential; `profiler-native/tests/RedefinitionAgent.java:20–28` only reports that retransformation returned, and `profiler-native/CMakeLists.txt:307–310` accepts that message.
- **Mapping/dependencies:** reopen/expand `docs/internals/backlog/resolved/issue-20260913-024413-834-synchronize-tier-e-symbolization-epochs-with-insta.md`; its paused-transform acceptance criteria remain pertinent. Plan B B2 explicitly reuses generation invalidation, making this a prerequisite rather than a later optimization.
- **Isolated evidence:** `07-symbolization-repro.cpp` uses the current production header. Both witnesses compiled and ran successfully: unrelated ClassPrepare-shaped completion and failed-second-begin completion reopened capture. No JVM/agent was loaded; this does not substitute for the installation-race regression.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235248-781-keep-symbolization-closed-until-the-owning-replace  file: issue-20261103-235248-781-keep-symbolization-closed-until-the-owning-replace.md -->
