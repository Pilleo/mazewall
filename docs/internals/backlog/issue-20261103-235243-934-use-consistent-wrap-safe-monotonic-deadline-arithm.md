---
title: "Use consistent wrap-safe monotonic deadline arithmetic"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "core"
target_modules: [":platform"]
target_files:
  - "platform/src/main/kotlin/io/mazewall/platform/lifecycle/valueClasses.kt"
target_symbols:
  - "Deadline"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Use consistent wrap-safe monotonic deadline arithmetic

**Context:**
Repository-wide review, 2026-11-04: [PLAT-02](../reviews/2026-11-04-repository-review-findings.md#plat-02). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P2 · liveness/correctness · reproduced against freshly compiled current source · high confidence · shared core/supervisor/profiler/Portal timing.**

- **Evidence:** `platform/src/main/kotlin/io/mazewall/platform/lifecycle/valueClasses.kt:122–134` computes `Long.MAX_VALUE - nowNanoTime` to test addition overflow, and `isExpired` uses absolute comparison. For negative `nowNanoTime`, the overflow check itself overflows. Shared reactor expiry instead uses subtraction (`platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/SeccompSessionHandler.kt:156–160,221–229`); daemon handshake uses `isExpired` plus `remainingMillis` (`platform/src/main/kotlin/io/mazewall/platform/seccomp/daemon/SeccompDaemonEngine.kt:397–425`); `platform/src/main/kotlin/io/mazewall/platform/socket/SocketIo.kt:49–54` trusts remaining milliseconds.
- **Reproduction:** at `now=-1_000_000_000`, a 30-second deadline becomes `Long.MAX_VALUE` instead of `29_000_000_000`. `remainingMillis(now)=0`, `isExpired(now)=false`, and the reactor's `now-deadline>=0` is **true**. A positive-origin control returns 30,000 ms. A signed-wrap case also leaves absolute `isExpired` false. See `01-platform-pure-repro.kt/.log`.
- **Effect:** immediate timeout/denial on subtraction-based paths, while an idle handshake can zero-timeout poll without expiring. The current host's clock was not observed to be negative; this is a legal clock-origin/input defect, not evidence that every present Linux deployment triggers it. OpenJDK [System.java:343–377](https://github.com/openjdk/jdk/blob/jdk-25-ga/src/java.base/share/classes/java/lang/System.java#L343-L377) explicitly permits negative origins and requires difference-based timeout comparisons.
- **Smallest correction:** keep durations bounded to the signed-difference horizon, allow monotonic addition to wrap, and make expiration/remaining time use one consistent relative comparison. Do not saturate an absolute nanoTime timestamp to `Long.MAX_VALUE`.
- **Regression:** negative origins, crossing zero, signed wrap, zero/negative/huge durations, and agreement among `isExpired`, remaining time and actual reactor/handshake expiry. `DeadlineTest.kt:15–31` only checks current/near-MAX clocks and currently prescribes the flawed saturation behavior.
- **Tracking:** **expanded deadline defect**, related to resolved `issue-20260910-225751-953-bound-user-notif-reactor-work-and-terminal-reply-o.md` (RH-05–08). The exact faulty arithmetic is also the recommendation in `docs/plans/implementation_plan.md:100–105`; correct that plan rather than reapplying it. The negative-origin case is not covered by the historical resolution.
- **Dependency:** common arithmetic fix first; then qualify dependent reactor, install, IPC and Portal deadline behavior without changing timeout budgets.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235243-934-use-consistent-wrap-safe-monotonic-deadline-arithm  file: issue-20261103-235243-934-use-consistent-wrap-safe-monotonic-deadline-arithm.md -->
