---
title: "Prove actual Spring advisor placement on owned invocation chain"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "integrations"
target_modules: ["integrations:spring:mazewall-spring-boot"]
target_files: ["integrations/spring/mazewall-spring-boot/src/main/java/io/mazewall/spring/BoundInvocationInterceptor.java"]
target_symbols: ["BoundInvocationInterceptor"]
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Prove actual Spring advisor placement on owned invocation chain

**Context:**
S0 must prove actual Spring invocation placement before S1–S3 integration work.
Moving a raw target call would bypass transactions/security and bean identity.
Frozen proxies alone do not freeze advisor objects; mutable cached chains must
not be mistaken for approved placement. Core and root integration are separately owned.

**Needed:**
1. Build the optional Boot4.1.1/Spring7.0.9 module without root/compiler changes.
2. Execute a real Spring invocation clone on the worker, preserving the existing
   singleton and transaction/diagnosis/authorization chain, exactly once.
3. Reject unproved order, nested proxy topology, unsupported method shapes,
   cross-binding nesting and configuration drift before protected target entry.
4. Wire the existing public ManagedRuntime API; keep host dispatch evidence
   explicitly separate from native proof.
5. Coordinator reviews S0 and integrates optional root settings/index before
   starting S1–S3. This issue remains open until that integration/review.

**Evidence:** scoped host `AdvisorPlacementTest` and `spotbugsMain` passed in
`build/fleet-spring-evidence/green-analysis-graph.log`. Actual Spring framework
chain/transaction execution is tested, using an uncontained dedicated platform
worker and instrumented host transaction manager. No JDBC, Security context or
native protection completion is claimed. Shared task-2 checkpoint/report retain
commands and acceptance limits. Backlog README is coordinator-owned.

**Round 1 review follow-up:** production construction requires the actual final
ManagedRuntimeDispatcher; generic injection is explicitly trusted/unprotected
host characterization. Nested offload must match the exact active physical
worker/context. Transaction drift uses an explicit immutable snapshot, including
name and structured labels. Declared checked/fatal bridge identities and virtual
workers are host-characterized; the lazy-return negative uses the correct
proxied interface and has a guard-removal sensitivity check. Review remains
pending; no S1–S3/native/JDBC/Security completion or automatic archive is claimed.

---
## Repository-review expansion — 2026-11-04

[SPR-03](../reviews/2026-11-04-repository-review-findings.md#spr-03) identifies retained
S0 validating the configured default manager rather than the manager actually selected by
Spring for the advised method. Qualify named/qualified/effective-manager selection through
real advisor dispatch before readiness; retain the single-manager restriction. S2's related
qualified-manager correction and common helper/classloader work remain implemented.

---
<!-- id: issue-20261008-011322-838-prove-actual-spring-advisor-placement-on-owned-inv  file: issue-20261008-011322-838-prove-actual-spring-advisor-placement-on-owned-inv.md -->
