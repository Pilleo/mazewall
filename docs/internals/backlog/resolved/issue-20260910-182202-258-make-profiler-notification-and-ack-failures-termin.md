---
title: "Make profiler notification and ACK failures terminal"
severity: "HIGH"
status: "resolved"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/src/main/kotlin/io/mazewall/profiler/engine/ProfilerSessionHandler.kt"
target_symbols:
  - "ProfilerSessionHandler"
open_questions: false
---

# 🟡 [Severity: HIGH]: Make profiler notification and ACK failures terminal

**Context:**
Review hardening Task 5: structural notification failure, dropped architecture metadata, and ignored ACK write failure can leave protocol state inconsistent.

**Needed:**
1. Add terminal notification and ACK regression tests, typed outcomes, bounded diagnostics, and run profiler verification.

**Resolution:**
The pollable handler offers a terminal denial for malformed notification/preparation failures and exhaustively consumes kernel reply results. Failed and gone replies cannot lead to publication or another ACK/reply. The shared heap notification retains audit architecture. A pure typed ACK decoder rejects malformed bytes, bounded legacy handshake retries terminate, and listener ACK errors or short writes close the endpoint before any next frame. Numeric session diagnostics are bounded and path/message contents are redacted. Observation noise classification no longer resolves arbitrary filesystem paths on the reactor.

**Evidence:**
- RED: `ProfilerDaemonTest.reactor structural failure offers one terminal denial without publishing` threw the injected structural exception; `ProfilerTraceListenerTest.failed ACK closes endpoint before reading another event` processed another buffered event after failed ACK.
- GREEN: `./gradlew --no-daemon :profiler:test :profiler:detektMain` and `:profiler:integrationTestFreshJvm --tests '*ProfilerIntegrationTest'`; 12 fresh-JVM integration cases passed without skips.
- Additional regressions cover malformed parser output, noise reply `ENOENT`/hard failure, zero/oversized ACK writes, kernel architecture identity, typed ACK decoding, and illegal post-terminal input.
- `:profiler:generateClassDiagrams` updates `docs/diagrams/profiler_class_diagram.puml` and `.svg`; the profiler design documents the terminal protocol contract.
- Independent-review follow-up: ACK/close failure cannot replace capture/queue failure, including fatal errors (`ProfilerTraceListenerTest.processing failure remains primary when ACK and close also fail`). Remote-memory preparation runs in bounded, independently terminable helper processes; `ProfilerPreparationPoolTest` covers cancellation/reaping, bounded admission, reuse and stale closure, while `ProfilerPreparationProtocolTest` validates identity/architecture and bounded heap evidence. `ProfilerDaemonTest.stalled preparation expires in reactor and late evidence cannot publish or reply` drives the actual shared reactor through the 30-second deadline and late completion.
- Follow-up GREEN: `./gradlew --no-daemon :profiler:test :profiler:detektMain :profiler:integrationTestFreshJvm --tests '*ProfilerIntegrationTest' :profiler:generateClassDiagrams` passed (566 host tests and 12 fresh-JVM tests, zero failures/skips; 1m47s, 60 tasks, 11 executed).
- Unreaped-capacity follow-up: a scripted helper that cannot terminate reproduced replacement beyond the configured pool limit. `ProfilerPreparationPoolTest.unreaped child fails pool without admitting replacement helpers` now verifies queued/new requests fail without replacement; incomplete reaping atomically fails the pool while retaining ownership. The same combined gate passed with 567 host tests and 12 fresh-JVM tests, zero failures/skips (2m30s, 60 tasks: 13 executed, 1 formatter result from cache, 46 up-to-date). Diagram generation confirmed no structural diagram change.

---
<!-- id: issue-20260910-182202-258-make-profiler-notification-and-ack-failures-termin  file: issue-20260910-182202-258-make-profiler-notification-and-ack-failures-termin.md -->
