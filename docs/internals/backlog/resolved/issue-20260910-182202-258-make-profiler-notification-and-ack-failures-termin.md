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

---
<!-- id: issue-20260910-182202-258-make-profiler-notification-and-ack-failures-termin  file: issue-20260910-182202-258-make-profiler-notification-and-ack-failures-termin.md -->
