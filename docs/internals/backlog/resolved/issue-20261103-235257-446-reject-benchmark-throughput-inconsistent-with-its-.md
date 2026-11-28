---
title: "Reject benchmark throughput inconsistent with its enclosed sample clocks"
severity: "MEDIUM"
status: "resolved"
priority: medium
dependencies: []
component: "benchmarks"
target_modules: []
target_files:
  - "src/consumerVerification/kotlin/io/mazewall/verification/BoundaryEvidence.kt"
target_symbols:
  - "BoundaryEvidence"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Reject benchmark throughput inconsistent with its enclosed sample clocks

**Context:**
Repository-wide review, 2026-11-04: [TEST-03](../../reviews/2026-11-04-repository-review-findings.md#test-03). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**Category:** reproduced bounded evidence-validation defect. **Confidence:** high for source; reproduced against existing compiled classes, not rebuilt in this lane. **Release boundary:** optional benchmark evidence only; not core containment or a production performance claim. **Status:** NEW.

**Trigger/evidence:** Keep all sample clocks/digests/effects unchanged and replace each ordinary `throughput` batch duration with positive `1`. `src/consumerVerification/kotlin/io/mazewall/verification/BoundaryEvidence.kt:189–197` only requires a positive duration and matching completion count, then divides by it. It never compares that duration with the enclosed ordinary sample timestamps.

The producer starts its batch timer before every ordinary request and stops after collecting/verifying/emitting all of them (`benchmarks/integration-boundaries/src/main/kotlin/io/mazewall/benchmark/Boundaries.kt:269,287–318`). Thus batch duration must at least enclose the observed ordinary sample span. A 1ns batch enclosing samples from 100ns to 340ns cannot be produced by that code.

**Reproduction:** `15-BoundaryValidatorProbe.java` obtains the existing self-contained host fixture, first gives its batches a plausible 1000ns duration, validates that baseline, then changes only those durations to 1ns. The actual compiled validator accepts all four modes and emits `harnessCompletionsPerSecond=3.0E9`. The actual `BoundaryEvidenceCorruptions.verify` still prints **`RAW_SELFCHECK PASS 80 independent-corruptions`** on the invalid dataset. This is not merely failure to reject fabricated provenance: it accepts inconsistent fields within the validator's stated observation scope.

**Smallest complete fix:** Check that each ordinary batch duration encloses its observed sample span, and enforce the serial ordinary ordering the harness actually promises. Add a positive-but-impossibly-short duration corruption. Adjust the synthetic baseline fixture as needed: its current 100ns batch is already shorter than its 240ns ordinary sample span (`IntegrationBoundaryEvidenceTest.kt:139–161`). No performance threshold or benchmark framework is needed.

**Regression/qualification:** Valid retained raw datasets should still validate. Duration 1ns, overlapping/reversed serial ordinary samples, and a batch shorter than its enclosed span must reject for an attributable measurement reason. Preserve the observation/provenance distinction and do not relabel historical library/measurement identities.

**Mapping/dependencies:** No matching active/resolved throughput-coherence issue found. The resolved health-selection control issue `.../resolved/issue-20261013-005143-547-add-negative-controls-for-benchmark-prepared-healt.md` is separate and genuinely addressed. Coordinate this small validator repair with, but do not duplicate or block on, the future P01 comparison (`issue-20261103-085927-123...`) or representative adoption (`issue-20261028-230731-805...`).

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

**Resolution**

The validator now checks ordinary requests in numeric ID order and requires the batch duration to enclose their host-observed span. The JDK 25 consumer harness rejects positive truncated durations, overlapping or reversed request clocks, and all 84 independent corruptions while retaining the valid baseline.

---
<!-- id: issue-20261103-235257-446-reject-benchmark-throughput-inconsistent-with-its-  file: issue-20261103-235257-446-reject-benchmark-throughput-inconsistent-with-its-.md -->
