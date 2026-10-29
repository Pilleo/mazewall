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

- [ ] Task6 bounded typed operation-document comparison and negative controls.
- [ ] Development-only named reader profile, coverage refusal and reviewed enforcement.
- [ ] Profiler/static checks and final whole-change review/gates.

Pre-flight: consume existing OperationPolicyDocument/CanonicalPolicyArtifact and
coverage fields, not a new policy algebra. Comparison reports changes only.
Profiling starts in its own development JVM before any baseline forbids helpers;
no observation, hash or candidate grants its own approval.

---
<!-- id: issue-20261029-112338-046-explain-reviewed-integration-policy-changes-withou  file: issue-20261029-112338-046-explain-reviewed-integration-policy-changes-withou.md -->
