---
title: "Adopt typed prepared ordinary-JAR resources in Spring and prove native JDBC lifecycle"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "spring-integration"
target_modules: [":integrations:spring:mazewall-spring-boot"]
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

**Investigation/evidence (2026-10-08):** task9 API contract red run-89ae35882713;
73 host tests/SpotBugs + actual local Maven publications pass run-38f3e8de6ac2.
Published ordinary-JAR native consumer passes run-6c99e72854ab and strengthened
run-fec3d7f23420, including separate actual origins and genuine unclaimed cleanup.
Generic AccessDenied diagnostics remain inferred; JDBC rollback is asserted by SQL.
No core/root/Portal/index changes or Boot/container/full-release claim.

**Side effects:** approved optional-adapter migration removes the metadata-only lifecycle
constructor and renames the host hash helper to classResourceSha256. No core ABI or
dependency delta; H2 stays consumer/test-only. Coordinator adds index row/count; issue
stays open pending independent review and remaining combined-release acceptance.

---
<!-- id: issue-20261008-072138-928-adopt-typed-prepared-ordinary-jar-resources-in-spr  file: issue-20261008-072138-928-adopt-typed-prepared-ordinary-jar-resources-in-spr.md -->
