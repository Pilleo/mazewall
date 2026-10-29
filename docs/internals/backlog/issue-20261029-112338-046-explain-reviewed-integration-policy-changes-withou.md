---
title: "Explain reviewed integration policy changes without automatic approval"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: []
target_files:
  - "profiler/src/tooling/kotlin/io/mazewall/profiler/tools/PolicyDiff.kt"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Explain reviewed integration policy changes without automatic approval

**Context:**
Approved pitch Task6: bounded2MiB operation-document comparison using existing canonical typed policies and coverage/binding model, report runtime/mode/syscall/argument/filesystem/artifact/resource/coverage/exception changes without universal safer/equivalent claims. Development-only profile same named read workload, incomplete evidence refuses conversion, explicit review remains mandatory.

**Needed:**
1. Read profiler instructions/designs, add literal diff/input-bound tests RED->GREEN, implement standard-library CLI and development example using existing profiler, preserve host/body/review boundaries; verify profiler tests and full gates.

## Inline progress

- [x] Task6 bounded typed operation-document comparison and negative controls.
- [x] Development-only named reader profile, coverage refusal and reviewed enforcement.
- [ ] Profiler/static checks and final whole-change review/gates.

Pre-flight: consume existing OperationPolicyDocument/CanonicalPolicyArtifact and
coverage fields, not a new policy algebra. Comparison reports changes only.
Profiling starts in its own development JVM before any baseline forbids helpers;
no observation, hash or candidate grants its own approval.

PolicyDiff tests observed RED for missing logic, then GREEN for canonical identity,
runtime, arguments, syscall maps, filesystem resources and coverage/exception
changes, exact2MiB input, malformed UTF8, invalid versions and duplicate-key refusal.
Output explicitly denies approval and universal ordering/equivalence claims.
New tooling remains outside the published profiler JAR/API; no dependency added.

Named reader profile observed RED for missing entry then ran the actual profiler
successfully in a fresh development JVM. Existing measured completeness gate
produced the candidate; independent external synthetic-test approval then exercised
actual reviewed enforcement/content checks and physical owned-worker retirement.
Wrong document digest refused before enforcement. This was a local synthetic test,
not operator approval for real data, remote release provenance or human rehearsal.

Ruling: bind this in-process development reader as CALLABLE with its exact actual
JVM member, not KOOG_TOOL (which the existing core correctly reserves for the process
adapter). Move the existing reader body into an application-local object so the
same business member can be selected directly; do not relax core checks or wrap an
invented member identity. Cost if wrong: sample binding/startup failure, not wider
permissions. Normal agent factory/API and profiler library behavior stay unchanged.

Additional opt-in detektTooling invocation exposed86 preexisting tooling findings
in unrelated sources; no new PolicyDiff finding was reported and no suppression or
baseline change was made. Normal/full completion gates and final review remain.

---
<!-- id: issue-20261029-112338-046-explain-reviewed-integration-policy-changes-withou  file: issue-20261029-112338-046-explain-reviewed-integration-policy-changes-withou.md -->
