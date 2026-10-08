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
open_questions: true
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

**S2 evidence:** real H2 2.4.240 / Spring7.0.9 under BOM4.1.1; both supported
qualified managers, all seven propagations, savepoints, diagnosis/beforeCommit,
business semantics, delivery separation and committed/rolled-back/unknown outcomes.
Security behavioral red cases proved hidden BeanFactory qualified-manager substitution,
equals-based datasource drift and a fatal rollback wrapper hiding Error from the
owned dispatcher; corresponding adapter fixes retain standard Spring transaction
implementation and restore fatal identity on-worker. Composed reviewed beans share
an explicitly approved identical fixed policy/resource generation; no unions.
Full focused host/analysis evidence and logs are in task-6-checkpoint/report.

**Remaining:** task-5 typed PreparedOperationResources integration and finished-JAR
digest alignment, native owned JDBC/denial/cancellation proof, reviewed S1/S2,
packaged consumer/Boot origin support and S3. Issue remains open; coordinator owns
backlog row/count integration. No archive or release-ready claim.

**Review round 1 corrections:** S1-1 public-only discovery omitted required non-public
declarations. Behavioral red/green now covers package-private singleton scope and bound
private/protected/inherited required declarations, retaining interface/bridge behavior.
S2-1 savepoint notification preceded actual JDBC rollback; intent now remains UNKNOWN
until the exact Spring held-savepoint completion path confirms success. Real rollback-
to-savepoint faults, caught outer commit/uncertainty/full rollback and same-physical-
transaction descendants are verified; independent REQUIRES_NEW commit remains intact.
Related fixture correction binds JdbcTemplate and manager to the SAME fault-wrapped
datasource and asserts active resource identity. Earlier fault-mode raw-datasource
logs alone are not managed SQL-effect proof. TASK5-ADOPTION-PLAN.md defines the gated
typed-owner/JAR adoption and coordinator/root update without copying unreviewed core.

---
<!-- id: issue-20261008-033416-558-bind-approved-synchronous-spring-singleton-operati  file: issue-20261008-033416-558-bind-approved-synchronous-spring-singleton-operati.md -->
