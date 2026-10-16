---
title: "Reuse unchanged CET fixture compilation with complete local toolchain inputs"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: [enforcer]
target_files:
  - "enforcer/build.gradle.kts"
  - "scripts/cet_fixture_fingerprint.sh"
  - "scripts/tests/cet_fixture_cache_test.sh"
  - "docs/internals/designs/core/testing-strategy.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Reuse unchanged CET fixture compilation with complete local toolchain inputs

**Context:**
Performance inspection found compileVulnerableRop is an Exec without declared inputs/outputs, so every native verification recompiles the same fixture. Existing Gradle/configuration/test caches already work (warm focused check 5s). Add local up-to-date reuse using source/script/preprocessed headers/compiler/linker/environment fingerprint; keep kernel test freshness and cross-machine native result caching disabled.

**Needed:**
1. Verify unchanged fixture compilation is UP-TO-DATE and source/header/toolchain/output changes invalidate reuse.

## Findings and optimization evidence

- Existing build and configuration caches are enabled. An unchanged focused test/analysis invocation completed in 5s with enforcer tests and Detekt UP-TO-DATE (`run-695d03175908`); these are reused results, not fresh test execution.
- Full gates still require externally sensitive kernel/endpoint suites. Observed suite XML totaled about 184s; the largest individual suite was about 14s. Repeating broad kernel gates, JVM startup and build work dominate the multi-minute checkpoints. Iteration now uses named owning-module host checks; broad merge/native verification runs at coherent checkpoints.
- The CET producer now declares C/script/output inputs plus a local fingerprint of preprocessed effective headers, compiler/cc1/linker, specs/link objects and relevant compiler environment. Portable output caching remains disabled and kernel test freshness/isolation is unchanged.
- The actual native task executed once (`run-9edc905d9180`) and was UP-TO-DATE on the unchanged follow-up in 2s (`run-d9f62063fe01`).
- `bash scripts/tests/cet_fixture_cache_test.sh` passed an independent Gradle fixture using the actual task declaration: unchanged reuse, C source change, included-header change, compiler wrapper change and deleted-output rebuild (`run-e47cb2842113`).
- Focused existing CET kernel acceptance passed (`run-3745b995e06d`); no new positive CET prerequisite claim is made. One shared merge checkpoint passed in 5m42s, 265 tasks (86 executed, 179 up-to-date), `run-b303371e7eee`, after correcting scaffolded issue membership before the rerun.

This improves local producer reuse and verification selection. It does not claim a shorter full kernel gate or cache native protection evidence. Issue remains open for repository review.

---
<!-- id: issue-20261007-162843-691-reuse-unchanged-cet-fixture-compilation-with-compl  file: issue-20261007-162843-691-reuse-unchanged-cet-fixture-compilation-with-compl.md -->
