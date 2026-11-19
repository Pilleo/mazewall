---
title: "Publish qualified platform envelopes for each containment mode"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/ContainedExecutors.kt"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Publish qualified platform envelopes for each containment mode

**Context:**
Architecture assessment finding 2: installOnDedicatedProcess requires Landlock TSYNC ABI 8+; core requires JDK22+, while agentless observations have a separately pinned Linux-amd64/Temurin envelope. Feature minimums are not deployment qualification. See GETTING_STARTED.md:7-22 and profiler/HOTSPOT_AGENTLESS.md:34-46. Acceptance: one maintained matrix separates core, Portal and profiler requirements, actual tested tuples and unsupported configurations; a selected packaged application has startup, denial and shutdown evidence. Older-kernel pre-JVM confinement is an explicit design decision, never a silent weaker fallback.

**Needed:**
1. Inventory existing qualification receipts and select the first supported deployment tuple; publish the per-mode matrix and define missing qualification work without claiming untested platforms.

## Repository review addendum — 2026-11-04 (ARCH-05 / LL-01)

[Deployment finding](../reviews/2026-11-04-repository-review-findings.md#arch-05) and
[Landlock qualification](../reviews/2026-11-04-repository-review-findings.md#ll-01).
JDK22 runtime/bytecode compatibility, JDK25 build requirements, Landlock feature
minimums, Portal ABI8 and strict/observed HotSpot pins are distinct claims. Historical
P617 consumers do not qualify today's Kotlin2.4.21 source/native closure. Select one
explicit artifact/JDK/vendor/architecture/kernel/ABI/outer-policy tuple for each
promoted mode; record real startup, denial, cancellation and physical-retirement
evidence, unsupported dimensions and exact source/toolchain/artifact identities.

For process Landlock, document baseline-before-local-filesystem-domain ordering,
including parked manually restricted siblings; current process enforcement uses
TSYNC despite stale zero-flags/universal monotonicity wording. Coordinate raw-scope
rejection with BPF-05 rather than duplicating its owner. Qualify the ABI8 sibling
controls before claiming order independence. The platform missing-symbol downcall
fallback also needs an injected absent-symbol check if the selected libc tuple can
reach it; no native failure on every untested platform is asserted.

The first promotion tuple remains an operator decision. No native matrix or fallback
relaxation was performed, and historical receipts retain their original scope.

---
<!-- id: issue-20261028-230730-359-publish-qualified-platform-envelopes-for-each-cont  file: issue-20261028-230730-359-publish-qualified-platform-envelopes-for-each-cont.md -->
