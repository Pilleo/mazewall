---
title: "SupervisorSessionHandlerTest: 66 reflection call sites ? fragile, breaks silently on signature changes"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "testing"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/test/kotlin/io/mazewall/enforcer/supervisor/SupervisorSessionHandlerTest.kt"
verify_cheap:
  - "./gradlew :enforcer:test --tests io.mazewall.enforcer.supervisor.SupervisorSessionHandlerTest"
needs_kernel: false
core_lock: false
effort: "medium"
autonomy: "supervised"
open_questions: false
---

# 🔴 [Severity: HIGH]: SupervisorSessionHandlerTest: 66 reflection call sites ? fragile, breaks silently on signature changes

**Context:** SupervisorSessionHandlerTest.kt (2480 lines) contains 66 Java-reflection call sites
(`getDeclaredMethods().first { ... } + isAccessible + invoke`) reaching into private methods of
SupervisorSessionHandler. This made the test suite the primary blocker during the MAZ-105 loop run:
when Jules changed `openFileInSupervisor`'s signature (adding openHow parameter), all reflection-based
tests broke with runtime `IllegalArgumentException: argument type mismatch` — no compile-time signal.
Changing methods from private to internal doesn't help because Kotlin mangles internal JVM names
(`openFileInSupervisor-2jcnego\`), breaking name/parameterCount predicates differently.

The correct fix is to eliminate reflection entirely: either make tested methods `internal` and rewrite
tests as direct typed calls, or extract logic into separate testable classes.
**Needed:**
1. Make the five reflection-targeted methods `internal` (not private):
   sendRequestToJvm, readAndHandleJvmResponse, handleInjectFd, openFileInSupervisor, handleAcceptAsync
2. Rewrite all 66 reflection call sites in SupervisorSessionHandlerTest.kt as direct typed calls
3. Delete all getDeclaredMethods/isAccessible/invoke blocks
4. Verify 148+ tests still pass
5. Add a lint rule banning java.lang.reflect in enforcer test sources

Evidence: MAZ-105 stall (Jules session 3948400735408327018 spent its entire budget fixing
reflection mismatches instead of doing real work). Also caused the earlier tgidResolver
signature-change failure that surfaced as "argument type mismatch" at runtime.
