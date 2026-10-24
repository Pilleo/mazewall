---
title: "Qualify published Koog to Spring Security and JDBC invocation"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "integrations"
target_modules: []
target_files:
   - "src/consumerVerification/kotlin/io/mazewall/verification/OptionalIntegrationAcceptanceTest.kt"
   - "src/consumerVerification/kotlin/io/mazewall/verification/KoogSpringSummaryTest.kt"
   - "src/consumerVerification/kotlin/io/mazewall/verification/KoogSpringPublicationTest.kt"
   - "tests/consumers/koog-spring/settings.gradle.kts"
   - "tests/consumers/koog-spring/build.gradle.kts"
   - "tests/consumers/koog-spring/gradle/verification-metadata.xml"
   - "tests/consumers/koog-spring/src/main/java/consumer/koogspring/JdbcService.java"
   - "tests/consumers/koog-spring/src/main/java/consumer/koogspring/ApprovedJdbcService.java"
   - "tests/consumers/koog-spring/src/main/kotlin/consumer/koogspring/SpringServiceTool.kt"
   - "tests/consumers/koog-spring/src/main/kotlin/consumer/koogspring/NativeKoogSpringConsumer.kt"
   - "tests/consumers/koog-spring/src/test/kotlin/consumer/koogspring/SpringServiceToolTest.kt"
   - "tests/consumers/koog-spring/README.md"
   - "tests/consumers/koog-spring/locks"
   - "integrations/koog/README.md"
   - "integrations/spring/mazewall-spring-boot/README.md"
target_symbols: ["SpringServiceTool", "requireKoogSpringSummaries"]
open_questions: false
needs_kernel: true
core_lock: false
autonomy: "supervised"
has_side_effects: true
---

# 🟡 [Severity: MEDIUM]: Qualify published Koog to Spring Security and JDBC invocation

**Context:**
Add a consumer-only upstream Koog Tool to approved Spring singleton Security and JDBC contract. Preserve original-caller checks, one protected execution owner, real published JAR custody, cooperative cancellation and physical retirement; no production API, policy or dependency-version changes.

**Needed:**
1. Write host bridge tests for original caller placement, business interruption identity, bounds and coroutine cancellation before implementing the consumer-local Tool.
2. Stage exact published libraries and an externally approved finished ordinary-JAR fixture; prove actual upstream registry, Spring binding, authorization/JDBC commit/rollback, kernel denial and cooperative cancellation/physical retirement for platform and virtual callers.
3. Add strict native summary corruption controls and the new consumer phase to existing optional acceptance without removing gates or increasing budgets.
4. Verify focused host/native checks, the exact-commit optional aggregate, the full build, one independent final review and exact-head hosted CI.

**Constraints:** The Tool stays consumer-local and invokes the actual Spring proxy
through `runInterruptible` without a dispatcher hop. Business outcomes are boxed
inside the interruptible region and unwrapped afterward. The application selects
a suitable unrestricted host caller before invocation; one host waiter blocks per
active call. No second protected offloader, public library API, dependency-version,
native-policy or enforcement change is authorized. Security context is captured
by the existing Spring bridge on the original caller. Cancellation is the final
case because starting drain closes admission permanently.

**Schema correction:** Actual Koog1.3 registry construction rejects scalar String
argument schemas. Use one consumer-local serializable object with a bounded
`mode: String` field and the already-used stable2.4.20 serialization compiler
plugin. The Spring `JdbcService.run(String)` contract and all library APIs stay
unchanged. The malformed-schema setup failure is not counted as behavioral RED.

**Host bridge acceptance:** The compiling direct-call baseline fails three of the
five actual registry contracts on UTF-8 bounds and cancellation. The original-
caller interruptible bridge then passes all five with no skips, preserving the
exact business InterruptedException and active coroutine. Published closure checks
select only the exact staged six first-party Maven modules. Runtime versions match
the plan. Consumer verification matches 246 existing approvals; 60 stable tooling
records match fresh official repository bytes. The Kotlin plugin marker POM is
pinned to the declared Plugin Portal bytes, not Central's different official POM;
no checksum or verification gate was loosened.

**Native and parent contracts:** Fresh JAR-only platform and virtual consumers
both pass against the staged baseline publications. Actual upstream registry,
approved singleton/advice, pre/post Security, JDBC commit/rollback, original business
interruption/caller transaction, actual sibling denial and cancellation retained
through physical worker/resource retirement are asserted. Parent summary and
new-coordinate checksum contracts each fail their no-op baseline before passing;
checksums append only the fixed six staged first-party coordinates, without
rewriting existing approval records or historical locks. Completion results and
remaining hosted/operator checkpoints are recorded below.

**Exact-publication acceptance:** `optionalIntegrationCheck` passes all original
Portal/thread/JDBC/Security/Boot phases plus both composed native callers against
committed producer `0bfde97cea6c14e9332c0807094eb87c71499340`, in 9m48s, with no
skips. The five parent contracts and five consumer host tests pass without skips.
The initial full gate detects two new formatting violations and a public lateinit
test-accessor SpotBugs finding; standard formatting and a private fixture field
fix those without suppressions. Focused ktlint, SpotBugs and the entire consumer
harness suite pass. The full `./gradlew build --console=plain --no-configuration-cache`
then passes in 5m09s on `d9285d7c`, with its configured prerequisite skips unchanged.
One independent whole-branch review finds no Critical, Important or Minor issues;
no corrective pass or deferred minors remain. Exact-head hosted CI and separate
operator merge/publication approval remain outstanding.

**Ruling:** Native receipt expectations are tested before wiring the consumer into
the aggregate, so parent corruption controls are already meaningful when native
output arrives. This changes task order only, not scope or verification.

**Final review rulings:** Keep the documented trusted synchronous ordinary-JAR
boundary, not a general nonblocking/web/reactive/ACE contract; using it outside
that boundary would not acquire those protections. Keep reference-based Security
capture and immutable deployment assumptions, not deep authentication snapshots
or hostile concurrent JAR mutation support; concurrent mutation is unsupported.
The reviewer does not independently reauthenticate external repository provenance;
the author's existing-approval comparison and fresh official-byte checks remain
the evidence, not a new provenance claim. PR556 and NVD freshness remain separate
tracks; neither is silently closed by this consumer acceptance.

**Administrative state:** PR556 remains ready pending separate merge approval;
that does not block this independent consumer work. NVD freshness qualification
is observed only on eligible ordinary default runs, with no timer or artificial
publication run. Counts/index must be reconciled if PR556 lands before integration.

---
<!-- id: issue-20261024-153357-714-qualify-published-koog-to-spring-security-and-jdbc  file: issue-20261024-153357-714-qualify-published-koog-to-spring-security-and-jdbc.md -->
