---
title: "Verify materialized HotSpot dependency sources before preparation reuse"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: []
component: "profiler"
target_modules: []
target_files:
  - "profiler/hotspot-agentless/internal/tooling/prepare.go"
target_symbols:
  - "verifiedPreparationIdentity"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Verify materialized HotSpot dependency sources before preparation reuse

**Context:**
Repository-wide review, 2026-11-04: [HS-04](../reviews/2026-11-04-repository-review-findings.md#hs-04). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**P2 · confirmed verification gap / build reproducibility debt · confidence high on behavior, medium on operational incidence · native tooling.**

**Trigger:** after a successful preparation, a copied Go source or module file under `build/deps/profiler-<id>` or `gadget-<id>` is accidentally edited, deleted or replaced while the preparation marker and three recorded native artifacts remain unchanged. Re-running preparation/release treats the copied tree as the same prepared identity. No corruption was performed in this checkout.

**Evidence:**

- `profiler/hotspot-agentless/internal/tooling/prepare.go:88–103` hashes authored input patches/additions, not the materialized dependency tree.
- `prepare.go:332–371,377–399` skips copy/patch/addition steps when `.mw-prepared.json` matches. The earlier `go mod verify` at line298 verifies the module cache, not these writable replacement copies.
- `prepare.go:260–282,372–374` checks exactly `support/types.go` and the amd64/arm64 embedded BPF objects; the Gadget copy has no corresponding source-byte verification on reuse.
- `internal/tooling/distribution.go:235–261` calls this marker/native-only check `verifiedPreparationIdentity`, then returns the authored input ID. The build embeds that ID (`Makefile:20–24`) while compilation uses the potentially changed local replacement sources.
- Existing tests `internal/tooling/prepare_test.go:75–127` cover marker fields and complete/native artifact inventory, not a changed copied decoder or module file.

**Consequence:** a reused dependency can compile into or be archived beside a release while carrying provenance for bytes it no longer contains. This is not an attacker-authentication claim: the operator/build directory is trusted. It is a concrete stale/mutated-cache acceptance gap behind the documented actual-prepared-source checks.

**Smallest complete correction:** preserve and verify a content inventory of relevant materialized dependency sources/module metadata (and existing native objects) before reuse and release, or regenerate an owned fresh tree from the verified base plus authored overlay. Reject extra/missing/modified source inputs; preserve unrelated existing work instead of repairing the cache in place. The same verified source identity should feed HS-03's build/distribution binding.

**Regression:** in an isolated prepared fixture, mutate an existing Go body, add a Go source file, remove an addition and alter `go.mod` without changing markers/native objects; each must fail reuse/release. An untouched prepared tree must still reuse successfully. Retain the existing incomplete-inventory/native-byte tests.

**Mapping / dependency:** **NEW extension** adjacent to resolved `docs/internals/backlog/resolved/issue-20261014-192004-715-reject-incomplete-generated-native-preparation-art.md`. That narrow fix correctly checks its complete three-artifact inventory and is not regressed. Pair with HS-03 or add a linked cache-integrity follow-up; no duplicate broad provenance project is needed.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

---
<!-- id: issue-20261103-235250-240-verify-materialized-hotspot-dependency-sources-bef  file: issue-20261103-235250-240-verify-materialized-hotspot-dependency-sources-bef.md -->
