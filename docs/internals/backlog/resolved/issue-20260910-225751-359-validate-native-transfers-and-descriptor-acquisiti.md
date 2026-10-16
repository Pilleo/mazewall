---
title: "Validate native transfers and descriptor acquisition evidence"
severity: "MEDIUM"
status: "resolved"
priority: high
dependencies: []
component: "core"
target_modules: ["platform"]
target_files:
  - "platform/src/main/kotlin/io/mazewall/core/FileDescriptor.kt"
target_symbols:
  - "FileDescriptor"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Validate native transfers and descriptor acquisition evidence

**Context:**
RH-01 through RH-04: native count boundaries, Java stream ranges and unique descriptor ownership require recorded regression evidence.

**Needed:**
1. Record implementing commits and executable regression evidence in the canonical hardening manifest.

**Resolution:** RH-01–RH-04 are implemented by commits 3036c58, f79eabfb, 4d914ad2. Regression owners: NativeTransferValidationTest, RawManagedBoundaryValidationTest and DescriptorIdentitySafetyTest.

**Verification:** `./gradlew :platform:test` passed in the native-boundary package; focused overflow, array-range, receipt and concurrent-close regressions were recorded RED then GREEN.

The [canonical finding manifest](../../designs/core/post-review-hardening-evidence.md#liveness-and-boundary-review-rh) maps individual methods and gates. [Final integration evidence](../../designs/core/review-hardening-verification.md) distinguishes fresh execution from cached outputs and does not imply aggregate acceptance from this scoped resolution.

---
<!-- id: issue-20260910-225751-359-validate-native-transfers-and-descriptor-acquisiti  file: issue-20260910-225751-359-validate-native-transfers-and-descriptor-acquisiti.md -->
