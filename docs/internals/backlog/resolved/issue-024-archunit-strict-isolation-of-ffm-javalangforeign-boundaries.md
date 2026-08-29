---
title: "ArchUnit: Strict Isolation of FFM (`java.lang.foreign`) Boundaries"
severity: "RESOLVED"
status: "resolved"
priority: medium
paperclip_issue_id: d5bf23ee-ca50-4979-9398-1b97fc93b557
paperclip_identifier: MAZ-222
---

# ✅ [RESOLVED]: ArchUnit: Strict Isolation of FFM (`java.lang.foreign`) Boundaries

**Status:** RESOLVED (June 2026)
**Target:** Entire project structure
**Context:** FFM calls must go through `NativeEngine` to allow mockability and fault injection, but nothing stops a developer from importing `java.lang.foreign.*` directly in a policy builder or integration test.
**Fix:** Implemented ArchUnit rules `rawMemorySegmentAccessMustBeEncapsulated` and `memorySegmentReinterpretIsBanned` asserting restricted access to FFM classes.
