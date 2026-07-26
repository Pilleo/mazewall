---
title: "ArchUnit: Strict Isolation of FFM (`java.lang.foreign`) Boundaries"
severity: "RESOLVED"
status: "resolved"
---

# ✅ [RESOLVED]: ArchUnit: Strict Isolation of FFM (`java.lang.foreign`) Boundaries

**Status:** RESOLVED (June 2026)
**Target:** Entire project structure
**Context:** FFM calls must go through `NativeEngine` to allow mockability and fault injection, but nothing stops a developer from importing `java.lang.foreign.*` directly in a policy builder or integration test.
**Fix:** Implemented ArchUnit rules `rawMemorySegmentAccessMustBeEncapsulated` and `memorySegmentReinterpretIsBanned` asserting restricted access to FFM classes.
