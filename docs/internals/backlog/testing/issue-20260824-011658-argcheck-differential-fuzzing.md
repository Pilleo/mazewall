---
title: "Differential Fuzzing of ArgCheck Semantics vs Real Kernel (EqualsAny32/MaskEquals)"
severity: "LOW"
status: "open"
priority: medium
component: "testing"
target_modules:
  - ":enforcer"
target_files:
  - "enforcer/src/integrationTest/kotlin/io/mazewall/seccomp"
effort: "medium"
autonomy: "autonomous"
open_questions: false
dependencies:
  - "issue-20260823-171500"
---

# 🟡 [Severity: LOW]: Differential Fuzzing of ArgCheck Semantics vs Real Kernel

**Context:** Issue-075 problem 3 introduced `ArgCheck.EqualsAny32` after discovering that 64-bit
comparisons on int-ABI arguments fail on high-word garbage. The oracle (`BpfSimulator`) gained
argument-vector injection (`simulate(..., args)`), which enables something stronger than the
current fixed-sample tests: **randomized differential testing** of inspection semantics against
the real kernel. Today's coverage is hand-picked vectors (zero/one/garbage); subtle bugs at
boundary values (e.g. negative ints sign-extended into the u64 slot, INT_MIN/MAX) would slip
through both the simulator and the samples if they share a wrong assumption.

**Needed:**
1. Property-style fuzz harness (deterministic seed) generating random `(syscallNr, argVector)`
   pairs across the inspection types (`EqualsAny32`, `MaskEquals`, `EqualsAny`) and boundary arg
   patterns (sign-extended negatives, high-garbage, zero, all-ones).
2. For each generated program: assert simulator verdict == kernel verdict (install on a worker
   thread per issue-20260823-172000 rules; skip KILL_* groups).
3. Seed printed on failure for reproduction; fixed default seed list checked in.
4. Wire into the existing `SeccompDifferentialVerdictTest` infrastructure rather than a new task.

