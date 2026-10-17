---
title: "Match exact Java owners in benchmark readiness and reporting"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "experiments/hotspot-agentless/internal/benchmark/readiness.go"
  - "experiments/hotspot-agentless/internal/candidate/verify.go"
target_symbols:
  - "applicationSegment"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Match exact Java owners in benchmark readiness and reporting

**Context:**
Continuation review reproduces benchmark readiness accepting WrongBenchmarkFixture and other.BenchmarkFixture leaf/route frames as the real fixture, because applicationSegment uses string Contains. A malformed display also becomes useful and another owner's route1 falsely invalidates a correct route0. Red run-ce806fabfdb0 records false ready=true and incorrect segment classification. Candidate oracles already have an exact renderer parser that can be shared without adding dependencies or changing native attribution semantics.

**Needed:**
1. Reuse one exact renderer owner/method parser for candidate and benchmark consumers; preserve benchmark leaf/router ordering, unavailable/native-only handling and wrong same-owner route rejection; verify both readiness and report consumers, retained real packets and the mandatory full commit build.

**Resolution:** Benchmark and candidate consumers share exact renderer owner/method
parsing. Wrong-owner readiness and segment classifications reject; valid, native-
only and wrong same-owner controls remain. Full Go host/race170tests and retained
actual benchmark/candidate packet revalidation pass. The committing revision must
pass the mandatory full-build hook; see
[evidence](../../../plans/2026-10-17-benchmark-exact-owner-evidence.md).

---
<!-- id: issue-20261017-120338-807-match-exact-java-owners-in-benchmark-readiness-and  file: issue-20261017-120338-807-match-exact-java-owners-in-benchmark-readiness-and.md -->
