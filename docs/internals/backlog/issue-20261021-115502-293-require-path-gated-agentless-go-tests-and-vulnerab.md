---
title: "Require path-gated agentless Go tests and vulnerability checks in CI"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "ci"
target_modules: ["profiler"]
target_files:
  - ".github/workflows/ci.yml"
  - "build.gradle.kts"
  - "gradle/libs.versions.toml"
  - "gradle/verification-metadata.xml"
  - "docs/internals/backlog/issue-20260908-173331-804-investigate-dependency-check-nvd-record-processing.md"
  - "buildSrc/src/main/kotlin/io/mazewall/build/CheckCiCachePolicy.kt"
  - "buildSrc/src/verification/kotlin/io/mazewall/build/CiCachePolicyTest.kt"
  - "profiler/hotspot-agentless/Makefile"
  - "experiments/hotspot-agentless/Makefile"
  - "profiler/hotspot-agentless/internal/capture/fatal_readout_test.go"
  - "experiments/hotspot-agentless/internal/capture/fatal_readout_test.go"
  - "profiler/hotspot-agentless/README.md"
  - "experiments/hotspot-agentless/README.md"
  - "docs/internals/designs/core/testing-strategy.md"
  - "profiler/hotspot-agentless/go.mod"
  - "profiler/hotspot-agentless/go.sum"
  - "profiler/hotspot-agentless/dependencies.lock.json"
  - "experiments/hotspot-agentless/go.mod"
  - "experiments/hotspot-agentless/go.sum"
  - "experiments/hotspot-agentless/dependencies.lock.json"
  - "profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotNativeAttachTest.kt"
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/hotspot/HotSpotAcceptanceHarness.kt"
  - "profiler/src/integrationTest/kotlin/io/mazewall/profiler/hotspot/HotSpotPublicIntegrationTest.kt"
  - "profiler/hotspot-agentless/patches/gadget-containerd-v2.patch"
  - "experiments/hotspot-agentless/patches/gadget-containerd-v2.patch"
  - "profiler/hotspot-agentless/patches/README.md"
  - "experiments/hotspot-agentless/patches/README.md"
  - "experiments/hotspot-agentless/source-snapshot.json"
  - "profiler/hotspot-agentless/internal/tooling/prepare.go"
  - "experiments/hotspot-agentless/internal/tooling/prepare.go"
  - "profiler/hotspot-agentless/internal/tooling/prepare_test.go"
  - "experiments/hotspot-agentless/internal/tooling/prepare_test.go"
  - "profiler/hotspot-agentless/internal/tooling/assets.go"
  - "experiments/hotspot-agentless/internal/tooling/assets.go"
  - "profiler/hotspot-agentless/internal/capture/containerd_client_test.go"
  - "experiments/hotspot-agentless/internal/capture/containerd_client_test.go"
  - "profiler/hotspot-agentless/internal/capture/failure_wire_test.go"
  - "experiments/hotspot-agentless/internal/capture/failure_wire_test.go"
  - "profiler/hotspot-agentless/internal/qualification/jitgc/capture_audit.go"
  - "experiments/hotspot-agentless/internal/qualification/jitgc/capture_audit.go"
  - "profiler/hotspot-agentless/internal/qualification/jitgc/capture_audit_test.go"
  - "experiments/hotspot-agentless/internal/qualification/jitgc/capture_audit_test.go"
  - "experiments/hotspot-agentless/internal/candidate/capture_audit_test.go"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotAssets.kt"
  - "profiler/src/main/kotlin/io/mazewall/profiler/internal/hotspot/HotSpotProtocol.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotAssetsTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotProtocolTest.kt"
  - "profiler/src/test/kotlin/io/mazewall/profiler/hotspot/HotSpotChildFixture.kt"
target_symbols: []
open_questions: false
needs_kernel: true
core_lock: true
autonomy: "supervised"
has_side_effects: true
---

# 🟡 [Severity: HIGH]: Require path-gated agentless Go tests and vulnerability checks in CI

**Context:**
Automate both Go modules' race tests and pinned govulncheck only for relevant changes.
Fix capture test subprocesses to use the prepared modfile so ordinary make unit works.
Require Go job success in the publication join and preserve failures from either module.
Use Go1.26.10 and patched x/net with its minimum required x/* versions; keep locked
images and native fixtures aligned. Replace the actual containerd v1 client with
patched v2.3.7, preserving namespace/error behavior and complete provenance. Rebuild
and verify candidate assets and real native acceptance; do not suppress scanner failures.
The approved NVD repair also includes compatible retained-data recovery, the
Dependency-Check 13.0.0 ingestion fix, and cold seeding followed by mandatory live
NVD refresh. Reject dropped records during update or analysis; preserve all gates.

**Needed:**
1. Fix and regression-check ordinary make unit in both modules.
2. Add pinned vulnerability target and bounded, path-gated required CI job; test actual workload failure propagation and publication join.
3. Verify local unit/policy/full-build checks; keep real vulnerability findings blocking until separately remediated.
4. Qualify completed NVD bootstrap, warm reuse and retained-artifact recovery without stale-data bypasses, timeout increases or shared-cache deletion.
5. Migrate both prepared Go graphs to the v2 client, regression-check real namespace/permission RPC behavior, and qualify the new fingerprint and native bundle without overwriting installations.

## Remaining qualification

Both modules pass their race suites and unchanged vulnerability gates with
containerd v2.3.7. Fresh native acceptance ran eight cases with zero skips: seven
passed; retention-pressure finish failed with `HotSpot owned cleanup deadline
expired` and a subsequent collector exit 137. Paired old/new-bundle diagnostics
showed collector exit preceding Docker runtime teardown by about a second.
The fixture now supervises collection with `exec` in an owned idle runtime and
checks collector-process absence before runtime teardown. Standalone capture keeps
its original container-identity SIGTERM path. The lifecycle regression fails with
the old launcher and passes with the separated ownership. Final-source native
acceptance passes all nine cases with zero skips, including retention pressure,
standalone SIGTERM and the new lifecycle regression. The full repository build
passes in 5m11s, and both modules' race/vulnerability gates pass again afterward.
Required CI qualification and approved integration remain pending; this item stays
open. The cleanup deadline and retirement checks are unchanged.
This failure is separate from the previously observed Portal retirement failure.

---
<!-- id: issue-20261021-115502-293-require-path-gated-agentless-go-tests-and-vulnerab  file: issue-20261021-115502-293-require-path-gated-agentless-go-tests-and-vulnerab.md -->
