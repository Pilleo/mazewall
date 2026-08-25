---
title: "Tier E R3: golden-file protocol conformance suite"
severity: "LOW"
status: "open"
priority: medium
component: "tier-e"
target_modules:
  - "tier-e-proto"
target_files:
  - "tier-e-proto/src/test/kotlin/io/mazewall/tierE/daemon/"
effort: "small"
autonomy: "supervised"
open_questions: false
dependencies:
  - "issue-20260825-023934-tier-e-wp-04-lifecycle-trust.md"
---

# 🟢 [Severity: LOW]: R3 — Golden-File Protocol Conformance Suite

**Context:** The WP-04 dual-implementation grind showed the wire contract lived only in
prose: HELLO desync, `MARKER_*` prefix divergence and terminal-state semantics were found
by containers, not by CI. The protocol has since stabilized (strict request→reply,
terminal DEAD sessions, MARKER_-prefixed hygiene failures). Freeze it before WP-08 adds
enrichment fields.

**Needed:**

1. Record transcripts (command→reply sequences, including failure paths: BUSY,
   ALREADY_BOUND, MARKER_BUILD_ID_UNREADABLE, NOT_MAPPED_IN_TARGET, STATE) from a live
   daemon into committed JSON fixtures under `tier-e-proto/src/test/resources/protocol/`.
2. Unit test replays each transcript through `ControlProtocol` + `SessionEngine` (fake
   shim) asserting byte-identical replies.
3. Any wire-visible change requires regenerating fixtures IN THE SAME PR with rationale —
   review diff becomes the protocol-change review.
4. When Kubescape enrichment fields arrive (WP-15), they extend fixtures here first.

**Acceptance:** fixture replay test wired into `:tier-e-proto:check`; docs pointer from
design doc §4 wire contract.
