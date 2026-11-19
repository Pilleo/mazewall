---
title: "Define independent profiler outcomes and upstream maintenance budget"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: []
target_files:
  - "profiler/HOTSPOT_AGENTLESS.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Define independent profiler outcomes and upstream maintenance budget

**Context:**
Architecture assessment finding 4: useful observational attribution is distinct from policy eligibility and future-path coverage. docs/internals/research/hotspot-sbob-coverage-and-performance.md:37-72 records 400 marked operations and 380 useful routes with completeness and policy eligibility false. profiler/hotspot-agentless/patches/README.md:10-24 lists local upstream overlay families. Acceptance: a bounded profiler milestone defines supported behavior scope, unknown/loss accounting, upgrade-diff usefulness, collection CPU/RSS and application latency measurements; an overlay inventory names ownership, upstream disposition and maintenance budget. Enforcement delivery must not require universal agentless attribution. Existing release and native-qualification issues retain ownership of their work.

**Needed:**
1. Record profiler-specific success and stop criteria, map each local overlay family to its maintenance owner and upstream strategy, and distinguish observation milestones from enforcement eligibility gates.

## Repository review addendum — 2026-11-04 (ARCH-06)

[Finding and acceptance](../reviews/2026-11-04-repository-review-findings.md#arch-06).
`profiler/hotspot-agentless/patches/README.md:12–34,133–160` inventories nine coupled
local review families and native/layout obligations, but not named maintenance
owners, bounded upgrade budgets or stop criteria. Keep one canonical collector;
collector capability, product adapters, recovery and release are separate outcomes.

Extend the existing overlay inventory with owner, upstream disposition, compatibility
corpus and practical upgrade/stop budget. Qualify one deliberately selected upstream/
JDK tuple for identity, accounting, physical retirement and Kotlin/Go conformance,
then the actual relevant adapter outcome. Record collection and application costs
against a bounded use case. Broader capture may validly remain no-go; protocol replay
is not native or Kubernetes persistence acceptance. Current definition-accounting
work and its original-attachment qualification remain with issue042. No second engine,
native run or external-release status is inferred by this review.

---
<!-- id: issue-20261028-230730-812-define-independent-profiler-outcomes-and-upstream-  file: issue-20261028-230730-812-define-independent-profiler-outcomes-and-upstream-.md -->
