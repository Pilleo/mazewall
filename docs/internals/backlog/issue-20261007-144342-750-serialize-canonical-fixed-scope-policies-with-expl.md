---
title: "Serialize canonical fixed-scope policies with explicit filesystem intent and strict versioning"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: [enforcer]
target_files:
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/api/CanonicalPolicyArtifact.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/artifact/CanonicalPolicyJson.kt"
  - "enforcer/src/main/kotlin/io/mazewall/enforcer/internal/artifact/StrictArtifactJson.kt"
  - "enforcer/src/test/kotlin/io/mazewall/CanonicalPolicyArtifactTest.kt"
  - "enforcer/src/integrationTest/kotlin/io/mazewall/CanonicalPolicyArtifactNativeTest.kt"
  - "enforcer/src/test/kotlin/io/mazewall/ArchitectureTest.kt"
  - "enforcer/api/enforcer.api"
  - "enforcer/README.md"
  - "docs/internals/designs/api/enforcer-public-api.md"
target_symbols: [CanonicalPolicyArtifact]
open_questions: false
---

# 🟡 [Severity: HIGH]: Serialize canonical fixed-scope policies with explicit filesystem intent and strict versioning

**Context:**
Approved C1/C6 requires a versioned canonical policy representation that round-trips active deny-all, exact grants, runtime, actions and argument flags without broadening. Reject unknown security fields, invalid action data, contradictory filesystem state and malformed/duplicate input. Serialization is not approval; reviewed operation binding remains separate.

**Needed:**
1. Add strict canonical policy artifact tests covering deny-all/unrestricted/resources, immutable snapshots and malformed schema before implementation.

## Implementation and verification evidence

Version 1 artifacts retain fixed worker/process scope, explicit runtime, parameterized Seccomp actions, argument flags, filesystem intent, exact paths and diagnostic options. Worker/process decoded policy types remain distinct. Permission collections are frozen with the existing definition helper; encoding and decoding use the existing JSON dependency and canonical compiler model. No policy engine, native implementation or dependency is added.

Strict parsing rejects unknown/missing fields, unknown versions/actions/scopes, malformed primitive types, duplicate fields/rules/resources, noncanonical token spelling, invalid action data and contradictory filesystem intent. Formatting whitespace is permitted; input string/number token spelling must match the canonical encoder. Artifact resource paths are absolute. Serialization does not imply approval or authorization.

The missing API baseline is `run-29becd524c9e`; six host round-trip/invalid-input/immutability cases passed, along with ABI, host/profiler and unchanged quality gates (`run-9bd707cb27eb`). An isolated actual-kernel case round-trips active deny-all through JSON and requires fresh-file denial on the owned worker while caller access remains available, with zero skips/failures/errors.

`./gradlew build integrationTest integrationTestFreshJvm` passed in 5m24s: 272 tasks, 84 executed and 188 up-to-date (`run-beb061fd07c7`). Serial mapped full Docker gates passed in 58s (`run-943f12510364`). Reviewed operation approval, coverage and immutable implementation/resource identity binding remain C6 follow-up. This issue stays open for repository review.

---
<!-- id: issue-20261007-144342-750-serialize-canonical-fixed-scope-policies-with-expl  file: issue-20261007-144342-750-serialize-canonical-fixed-scope-policies-with-expl.md -->
