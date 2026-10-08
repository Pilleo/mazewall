---
title: "Bind approved synchronous Spring singleton operations and JDBC outcomes"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "spring-integration"
target_modules: ["integrations:spring:mazewall-spring-boot"]
target_files: ["integrations/spring/mazewall-spring-boot/src/main/java/io/mazewall/spring/SpringOperationBinder.java"]
target_symbols: ["SpringOperationBinder", "DomainLifecycle", "SpringTransactionGuard"]
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Bind approved synchronous Spring singleton operations and JDBC outcomes

**Context:**
Approved S0 proved placement only. S1 must match actual singleton/bridged method,
reviewed implementation metadata and explicit lifecycle; S2 must establish actual
synchronous JDBC propagation and honest outcomes without claiming native/resource
proof from metadata or mocks. Task 6 owns adapter changes; task 5 owns preparation.

**Needed:**
1. Bind exact reviewed existing singleton proxies; preserve advisors and reject
   unmatched required operations, unsupported results and identity drift.
2. Prepare explicitly, drain boundedly and retain unfinished runtime owners.
3. Verify all seven JDBC propagation modes with embedded H2, evidence-before-commit,
   ordinary business semantics and independent transaction-effect outcomes.
4. Review S1/S2 before S3. Fresh native and packaged proof remain coordinator gates.

**S1 evidence:** focused host tests and owning SpotBugs analysis in assigned worktree
`build/fleet-spring-release-evidence/s1-green-2.log`; bridge regression failed in the
preceding executable red run. See module S1-S2-contract.md for the exact limitations
and the required preparation seam. No native protection/resource pinning claim.

---
<!-- id: issue-20261008-033416-558-bind-approved-synchronous-spring-singleton-operati  file: issue-20261008-033416-558-bind-approved-synchronous-spring-singleton-operati.md -->
