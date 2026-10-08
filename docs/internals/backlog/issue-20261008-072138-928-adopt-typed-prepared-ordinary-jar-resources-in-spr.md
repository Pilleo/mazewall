---
title: "Adopt typed prepared ordinary-JAR resources in Spring and prove native JDBC lifecycle"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "spring-integration"
target_modules: [":integrations:spring:mazewall-spring-boot", ":integrations:spring:mazewall-spring-boot-starter"]
target_files: ["integrations/spring/mazewall-spring-boot/src/main/java/io/mazewall/spring/DomainLifecycle.java", "integrations/spring/mazewall-spring-boot/src/main/java/io/mazewall/spring/SpringOperationBinder.java", "tests/consumers/spring-jdbc/src/main/java/consumer/spring/NativeJdbcConsumer.java"]
target_symbols: ["DomainLifecycle", "SpringOperationBinder"]
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Adopt typed prepared ordinary-JAR resources in Spring and prove native JDBC lifecycle

**Context:**
The reviewed core typed owner is integrated in base92da1536. Production Spring still
prepared only configured metadata and compared one defining-loader class digest with
the reviewed implementation digest. That cannot establish actual finished-JAR identity,
genuine resource ownership or inclusion of common-domain implementation origins.

**Needed:**
1. Require genuine PreparedOperationResources in production lifecycle and call the
   three-argument prepare. Close unclaimed resources, retain transferred owners through
   failure/incomplete drain and real physical retirement.
2. Bind the existing actual singleton/Class/loader/bridged member and external trusted
   finished-JAR SHA/deployment generation. Reject uncaptured common-domain Classes/origins.
3. Publish the optional adapter as a normal JAR; prove public ordinary-JAR native owned
   JDBC data, template/sibling denial, cooperative cancel and actual physical end.
4. Preserve explicit limitations and unsupported Boot/exploded rejection. Independent
   task9 review and combined Boot/task8 gates remain required; do not archive from a
   host green or infer complete release readiness.
5. Optional approved S3: per-call supported Security delegation, clean worker context,
   actual pre/post-authorization inside JDBC completion, unsupported holder/rules/
   propagation/feature rejection, conditional absence/default preservation and thin
   normal-JAR starter. Executable Boot demo stays gated on reviewed task8 origins.

**Investigation/evidence (2026-10-08):** task9 API contract red run-89ae35882713;
73 host tests/SpotBugs + actual local Maven publications pass run-38f3e8de6ac2.
Published ordinary-JAR native consumer passes run-6c99e72854ab and strengthened
run-fec3d7f23420, including separate actual origins and genuine unclaimed cleanup.
Generic AccessDenied diagnostics remain inferred; JDBC rollback is asserted by SQL.
No core/root/Portal/index changes or Boot/container/full-release claim.
Optional host80/0/0/0 + SpotBugs/publication pass run-925f3bffb63b; actual published
normal-starter Security absence and explicit Security native ordinary-JAR variant pass
run-49358f1a85a2. Actual Security no-rollback override RED run-b8f9a87ba09b, unsupported
holder RED run-9998de79f505 and nontransactional PostAuthorize RED run-4f30f5f2660e are
recorded; final hardened checks are tracked in task9 report/checkpoint.
Final hardened host/SpotBugs/fresh normal publications pass run-51750f24423b;
XML83/0/0/0 and actual normal JAR/POM isolation audited run-dfb22cbd76ae. Final
published normal-starter Security absence and explicit Security ordinary-JAR native
fixtures pass run-e2cac71d3ddf with real postauth SQL rollback, clean context after
success/denial/error/cancel, retained owners and actual physical retirement. Required
native failures never skip/fallback. Independent task9 review and Boot/demo gates stay open.

**Review round1 (base9932b147):** I1 missing Jakarta/Javax DenyAll/PermitAll caused
actual required target entry and committed H2 SQL; eight method/type cases reproduced
with body1/SQL1 in run-d199bf5f3125 and pass with zero body/SQL effects after the four
names are included (run-80b923c52e1c). I2 public binder host factories allowed uncontained
required annotation binding; foreign-package Java compilation reproduced the public
escape in the same RED run. All three forms are now package-private/test-only; public
binding requires genuine typed ownership. The S0 public structural uncontained seam
remains explicitly outside required binding. Final round1 host/API checks and pending
native/rereview evidence are recorded in task9 report; issue stays open, no index edits.

**Side effects:** approved optional-adapter migration removes the metadata-only lifecycle
constructor and renames the host hash helper to classResourceSha256. No core ABI or
dependency delta in typed adoption; optional S3 adds framework compile-only/test-only
dependencies and the thin starter's basic Boot dependency, outside core/Portal. H2
stays consumer/test-only. Coordinator adds index row/count and starter include; issue
stays open pending independent review and remaining combined-release acceptance.

---
<!-- id: issue-20261008-072138-928-adopt-typed-prepared-ordinary-jar-resources-in-spr  file: issue-20261008-072138-928-adopt-typed-prepared-ordinary-jar-resources-in-spr.md -->
