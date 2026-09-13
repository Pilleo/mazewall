---
title: "Define transactional sink acknowledgement and ambiguous delivery recovery"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules:
  - "profiler"
target_files: 
  - "profiler/src/main/kotlin/io/mazewall/profiler/attribution/DictionaryBatchSink.kt"
target_symbols: 
  - "DictionaryBatchSink"
open_questions: false
---

# 🟡 [Severity: HIGH]: Define transactional sink acknowledgement and ambiguous delivery recovery

**Context:**
The architecture review distinguishes local rollback from receiver acceptance and durable persistence; ambiguous partial delivery must be terminal unless the transport is explicitly idempotent.

**Needed:**
1. Specify Committed and RetryableFailure acknowledgement boundaries, batch identity, payload validation, and terminal handling for ambiguous delivery.

## Acceptance criteria

1. `Committed` means the documented receiver acknowledgement boundary has been crossed; local buffering alone is insufficient.
2. `RetryableFailure` is returned only when replaying the identical batch is safe and idempotent.
3. Partial or ambiguous delivery is terminal unless the transport proves session-qualified idempotence and rejects the same identity with different bytes.
4. Queue contents, watermark, and encoded bytes remain unchanged until commit.

## Required verification

1. Add sink tests for accepted delivery, partial write, lost acknowledgement, reconnect, and ambiguous delivery.
2. Retain raw payloads and receiver acknowledgements in a machine-readable artifact.
3. Report `transport_completeness`, `dictionary_integrity`, and `lifecycle_validity` independently; ambiguous delivery is fail-closed.

Verification command: `./gradlew :profiler:test --tests io.mazewall.profiler.attribution.DictionaryBatchPublisherTest`.

---
<!-- id: issue-20260913-024413-414-define-transactional-sink-acknowledgement-and-ambi  file: issue-20260913-024413-414-define-transactional-sink-acknowledgement-and-ambi.md -->
