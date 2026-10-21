---
title: "Assess and patch high-severity compress advisories in agentless Go modules"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: ["profiler"]
target_files:
  - "profiler/hotspot-agentless/go.mod"
  - "profiler/hotspot-agentless/go.sum"
  - "experiments/hotspot-agentless/go.mod"
  - "experiments/hotspot-agentless/go.sum"
  - "buildSrc/gradle/verification-metadata.xml"
target_symbols: []
open_questions: false
needs_kernel: true
core_lock: true
effort: "medium"
autonomy: "supervised"
has_side_effects: true
---

# Patch the agentless compression dependency

**Context:**
Both Go modules pin `github.com/klauspost/compress` v1.18.4, affected by
[CVE-2026-63209](https://github.com/advisories/GHSA-259r-337f-4rfw).
An overflowing S2 dictionary repeat value can pass validation and lead to a crash.
The first patched release is v1.18.7. Dependabot alerts5/6 track the two modules;
the Java dependency scan does not cover them.

**Needed:**
1. Update both module pins and checksums to the patched release without unrelated upgrades.
2. Verify overflow rejection, selected module versions and race-enabled Go tests.
3. Run the project quality gate and use the scoped review/merge path.
4. Restore the existing trusted JUnit and coroutines BOM POM checksums in buildSrc metadata so the required CI gate can compile.

---
<!-- id: issue-20261020-213826-813-assess-and-patch-high-severity-compress-advisories  file: issue-20261020-213826-813-assess-and-patch-high-severity-compress-advisories.md -->
