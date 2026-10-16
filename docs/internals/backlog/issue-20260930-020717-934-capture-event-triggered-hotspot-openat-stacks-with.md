---
title: "Capture event-triggered HotSpot openat stacks with Gadget"
severity: "MEDIUM"
status: "open"
priority: high
dependencies:
  - "issue-20260930-012719-798-lock-agentless-hotspot-experiment-dependencies-and"
component: "experiment"
target_modules: []
target_files:
  - "experiments/hotspot-agentless/internal/capture/ig.go"
  - "experiments/hotspot-agentless/internal/capture/config_test.go"
  - "experiments/hotspot-agentless/internal/capture/store.go"
  - "experiments/hotspot-agentless/internal/capture/store_test.go"
  - "experiments/hotspot-agentless/cmd/mw-hotspot-demo/main.go"
  - "experiments/hotspot-agentless/cmd/mw-gadget-build/main.go"
  - "experiments/hotspot-agentless/gadgets/openat-stack/program.bpf.c"
  - "experiments/hotspot-agentless/gadgets/openat-stack/gadget.yaml"
  - "experiments/hotspot-agentless/fixtures/java/InlineOpenFixture.java"
  - "experiments/hotspot-agentless/tests/integration/capture_test.go"
  - "experiments/hotspot-agentless/scripts/run-demo.sh"
  - "experiments/hotspot-agentless/Makefile"
  - "experiments/hotspot-agentless/go.mod"
  - "experiments/hotspot-agentless/go.sum"
  - "experiments/hotspot-agentless/README.md"
  - "experiments/hotspot-agentless/results/README.md"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Capture event-triggered HotSpot openat stacks with Gadget

**Context:**
Implement the approved first live capture gate using Gadget's existing lifecycle and OTel helper; keep unknown cold-start observations visible.

**Needed:**
1. Build the pinned openat gadget, validate ordinary-user Java application frames on genuine event references, and retain live evidence before structured enrichment.

**Verification evidence (2026-09-30):**
- `go test -race ./...`: PASS for dependency/preflight/configuration boundaries.
- `DOCKER_HOST=unix:///var/run/docker.sock COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock make demo`: PASS;
  uses ordinary-user pinned Temurin, genuine handler-entry event references,
  explicit syscall-register read status and expected host PID.
- First run: 400 marked events, 384 with application frames; unresolved cases retained.
- `./gradlew build`: PASS after adding this issue to the backlog index.
- Debug rendering is sufficient only for the initial capture gate. Structured
  results, lost-first-definition recovery, inline fidelity and capacity remain
  separate gates. Keep open until delivery review.

---
<!-- id: issue-20260930-020717-934-capture-event-triggered-hotspot-openat-stacks-with  file: issue-20260930-020717-934-capture-event-triggered-hotspot-openat-stacks-with.md -->
