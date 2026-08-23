---
title: "SBoB Policy Artifacts Workflow: Pinned, Signed, Versioned Policies for CI Admission"
severity: "ENHANCEMENT"
status: "open"
priority: medium
component: "enforcer"
target_modules:
  - ":enforcer"
  - ":profiler"
  - ":tools:orchestrator"
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/sbob"
  - "docs/internals/designs"
effort: "large"
autonomy: "supervised"
open_questions: true
dependencies: []
---

# 🟢 [Severity: ENHANCEMENT]: SBoB Policy Artifacts Workflow — Pinned, Signed, Versioned Policies for CI Admission

**Context:** The profile→SBoB→policy pipeline exists (`IterativeProfiler`, `BobCompiler`,
`PolicyTransformer`), but there is no artifact lifecycle: nothing pins a discovered policy to a
build, signs it, versions it, or lets CI *admit* a build only when it runs under an approved policy
("this build may only execute under SBOB X, revision N"). The external Bill of Behavior standard
(https://billofbehavior.com/bob/#standard) formalizes exactly this direction: vendor-signed,
machine-checkable YAML behavior profiles intended for EU CRA / NIS 2 readiness, with upstream
tooling at github.com/k8sstormcenter/bob (bobctl) and Kubescape as reference implementation.
Notably, the standard states enforcement today covers network policies only — mazewall's
seccomp/Landlock enforcement could be the missing enforcing backend for syscall/filesystem facets.

**Needed (directional, design-first):**
1. Define a mazewall policy artifact format: canonical serialization of a discovered/approved
   `PolicyDefinition` (+ provenance: profiler run metadata, tool version, source SBoB profile).
2. Pinning & admission: a build/test gate (Gradle task + orchestrator hook) that runs the workload
   under the pinned artifact and fails on divergence (new syscalls, new FS paths).
3. Signing & verification: detached signatures over artifacts (verify-on-load, fail closed);
   align wire format with the BoB spec where feasible rather than inventing a parallel one.
4. Versioning & diffing: semantic policy revisions with human-readable diffs between revisions
   (syscall added? path widened?) feeding both admission decisions and detection engineering.
5. Coordinate with the upstream BoB community (k8sstormcenter) instead of fragmenting the ecosystem;
   mazewall's enforcing backend could feed their detection-rule generation.

## ❓ Open Questions
1. Trust model: who signs (vendor vs platform team vs both)? Root of trust in CI?
2. Should admission failure be fail-closed at install time (refuse to run unpinned builds) or
   report-only initially?
3. Format: adopt BoB YAML wholesale with a mazewall enforcing profile extension, or dual-export?
