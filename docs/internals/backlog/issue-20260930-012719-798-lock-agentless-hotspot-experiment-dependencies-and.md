---
title: "Lock agentless HotSpot experiment dependencies and preflight"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "experiment"
target_modules: []
target_files:
  - "docs/plans/2026-09-29-agentless-hotspot-kubescape-plan.md"
  - "experiments/hotspot-agentless/go.mod"
  - "experiments/hotspot-agentless/go.sum"
  - "experiments/hotspot-agentless/dependencies.lock.json"
  - "experiments/hotspot-agentless/Makefile"
  - "experiments/hotspot-agentless/internal/preflight/check.go"
  - "experiments/hotspot-agentless/internal/preflight/check_test.go"
  - "experiments/hotspot-agentless/internal/preflight/observe_linux.go"
  - "experiments/hotspot-agentless/internal/preflight/observe_other.go"
  - "experiments/hotspot-agentless/cmd/mw-hotspot-demo/main.go"
  - "experiments/hotspot-agentless/scripts/preflight.sh"
  - "experiments/hotspot-agentless/scripts/test-preflight.sh"
  - "experiments/hotspot-agentless/tests/fixtures/PreflightTarget.java"
  - "experiments/hotspot-agentless/README.md"
  - "experiments/hotspot-agentless/results/README.md"
  - "experiments/hotspot-agentless/patches/README.md"
  - "experiments/hotspot-agentless/.gitignore"
target_symbols: []
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Lock agentless HotSpot experiment dependencies and preflight

**Context:**
Implement Task 0 of the approved standalone Gadget/OTel experiment on fast-master; prove reproducible dependencies and explicit runtime refusal before event capture.

**Needed:**
1. Add the isolated Go module and tested preflight, pin build inputs, and record build and runtime evidence.

**Verification evidence (2026-09-30):**
- `make verify`: PASS (race tests, Go build, module graph, checksum validation).
- `make container-build DOCKER_HOST_ARG='--host unix:///var/run/docker.sock'`: PASS.
- `COLLECTOR_DOCKER_HOST=unix:///var/run/docker.sock make preflight-test`: PASS;
  ordinary-user pinned Temurin accepted, root target and unprivileged collector refused.
- Kernel BTF, `__x64_sys_openat` wrapper ABI and temporary BPF load verified.
- This is dependency/preflight evidence only. Live stack capture/recovery gates
  require separate work packages. Keep open until delivery review.

---
<!-- id: issue-20260930-012719-798-lock-agentless-hotspot-experiment-dependencies-and  file: issue-20260930-012719-798-lock-agentless-hotspot-experiment-dependencies-and.md -->
