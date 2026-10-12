---
title: "Require operator-owned Portal launch profiles and independent readiness"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "portal-runtime"
target_modules: ["platform", "portals/runtime", "portals/worker"]
target_files:
  - "portals/runtime/src/main/kotlin/io/mazewall/portal/ProcessBroker.kt"
target_symbols:
  - "ProcessBroker"
open_questions: false
---

# 🟡 [Severity: HIGH]: Require operator-owned Portal launch profiles and independent readiness

**Context:**
Task 2: required startup does not bind explicit selected environment and descriptor expectations

**Needed:**
1. Capture immutable launch authority and check actual child evidence before permit and guest entry

**Task 2 implementation (uncommitted review candidate):**
- Version-2 immutable declared noncredential marker/resource expectations and trusted operator preparation at existing child launch owners; original entries retain inherited deployment behavior.
- Missing required names, unsupported versions, malformed/mismatched actual inputs and old records reject.
- Format-2 authorization/readiness bind checked launch identity and actual required cgroup membership.
- Broker rejects an ordinary filesystem posing as cgroup authority before permit (a failing native control exposed this gap).
- Native canary is opened and independently observed before rejection; actual guest constructor is exercised by the positive control.
- Actual child reaping, output-pump termination, acquired socket/endpoint retirement and retained pre-native retry owners are checked.
- Focused evidence and exact commands live in the controller's newworkspace/task-2-report.md.
- Remains open until scoped review/controller checkpoint. Real delegated-cgroup positive proof and production operator launch approval are separate prerequisites.

**Fix round 1:**
- Removed library-owned whole-environment replacement and arbitrary name/value attestation.
- Closed marker selectors, explicit operator preparation, captured command/pipe validation and real legacy/explicit public-entry guest controls.
- Added malformed/canonical/bounds parser table and maximum supported valid record.
- Protocol constants and cohesive startup/cgroup/launch ownership remove added warning debt without inventory/threshold/suppression changes.

---
<!-- id: issue-20261011-165557-976-require-operator-owned-portal-launch-profiles-and-  file: issue-20261011-165557-976-require-operator-owned-portal-launch-profiles-and-.md -->
