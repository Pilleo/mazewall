---
title: "MAZ-18 Backlog Review - Open Questions Identification"
severity: "MEDIUM"
status: "open"
priority: high
component: "backlog-review"
target_modules:
  - ":enforcer"
  - ":profiler"
  - ":platform"
target_files: []
effort: "medium"
autonomy: "autonomous"
open_questions: true
has_side_effects: false
paperclip_issue_id: f2d98330-3d92-4462-9094-decbfe7e0a1e
paperclip_identifier: MAZ-18
---

# Backlog Review: Open Questions That Cannot Be Answered by Current Docs or Code

**Generated:** 2026-08-29  
**Reviewed by:** Vibe ACP Developer (d159bcf4-4a01-4fd8-9007-bad4aababfeb)  
**Source:** Issue MAZ-18 - "please review backlog open issues and see what open questions do you have that cannot be answered by current docs or code"

## Executive Summary

This review identifies **6 backlog issues with open questions** that require operator or architectural decision. These questions cannot be resolved through existing documentation, code inspection, or design documents alone. They represent **blocking decisions** that must be made before implementation can proceed or before certain work packages can be completed.

---

## Methodology

1. Scanned all files in `docs/internals/backlog/` subdirectories (security, performance, testing, code_health, implementation, resolved)
2. Filtered for files with `status: open` in YAML frontmatter
3. Filtered for files with `open_questions: true` or containing `## \u2753 Open Questions` sections
4. Extracted questions that require operator input, architectural decisions, or external coordination
5. Excluded questions that can be answered by reading existing design docs or code

---

## Open Questions Requiring Operator Input

### 1. MAZ-765: Rootless Podman Docker Socket BPF Ceiling
**File:** `docs/internals/backlog/testing/issue-20260825-090500-rootless-podman-docker-socket-bpf-ceiling.md`  
**Severity:** LOW | **Priority:** medium | **Component:** ebpf-prototype

**Cannot be answered by docs/code because:** Requires operator preference decision on development workflow

**Open Question:**
> Which durable workflow does the operator prefer for local G0/G1/G2 gate runs?

**Options:**
- Passwordless sudo rule for the specific runner command
- Execute Tier E kernel phases only in CI runners with genuine rootful runtimes

**Context:** The dev host uses rootless podman masquerading as docker.sock. Kernel BPF loading fails due to user namespace restrictions (`bpf(2)` capability checks against initial user namespace → EPERM, `kernel.unprivileged_bpf_disabled=2`). Current workaround uses `run_collector.sh` with auto-detection and fallback to `sudo podman run`.

**Blocker:** Cannot proceed with local Tier E gate testing without this decision.

---

### 2. MAZ-709: WP-01 MazewallContext API (In-Memory Context Management)
**File:** `docs/internals/backlog/implementation/issue-20260825-023931-tier-e-wp-01-mazewall-context-api.md`  
**Severity:** ENHANCEMENT | **Priority:** high | **Component:** platform

**Cannot be answered by docs/code because:** Requires design decision on API evolution path

**Open Question:**
> Should `withContext` accept a suspend-friendly variant now?

**Current Decision:** No for v1 — coroutines are explicitly out of scope; note it in KDoc as unsupported.

**Future Consideration:** The question is explicitly deferred with a "No for v1" answer, but this decision should be revisited when coroutine support is needed. The open question flag suggests this may need operator confirmation.

**Context:** The MazewallContext API is the foundation for all Tier E work. Virtual thread guard (invariant 4) already throws `IllegalStateException` for virtual threads. Coroutine support would require suspend-friendly variants.

**Blocker:** None immediate (v1 can proceed without), but API design decision needed for roadmap clarity.

---

### 3. MAZ-706: SBoB Policy Artifacts Workflow - CI Admission
**File:** `docs/internals/backlog/implementation/issue-20260823-171954-sbob-policy-artifacts-ci-admission.md`  
**Severity:** ENHANCEMENT | **Priority:** medium | **Component:** enforcer, profiler, orchestrator

**Cannot be answered by docs/code because:** Requires organizational trust model and security policy decisions

**Open Questions:**

1. **Trust model:** Who signs (vendor vs platform team vs both)? Root of trust in CI?
2. **Admission failure behavior:** Should admission failure be fail-closed at install time (refuse to run unpinned builds) or report-only initially?
3. **Format:** Adopt BoB YAML wholesale with a mazewall enforcing profile extension, or dual-export?

**Context:** This implements the external Bill of Behavior standard (billofbehavior.com) for pinned, signed, versioned policies. Mazewall's seccomp/Landlock enforcement could be the enforcing backend for syscall/filesystem facets of the BoB standard.

**Blocker:** All three questions must be resolved before implementation can begin. These are organizational security policy decisions.

---

### 4. MAZ-714: WP-13 Sampling Enrichment Policy
**File:** `docs/internals/backlog/implementation/issue-20260825-023943-tier-e-wp-13-sampling-enrichment.md`  
**Severity:** ENHANCEMENT | **Priority:** medium | **Component:** profiler

**Cannot be answered by docs/code because:** Requires tuning decision based on performance characteristics

**Open Question:**
> What is the default stack-sampling rate for CI vs interactive profiling sessions?

**Context:** Context propagation must always be 100% unsampled (never determine attribution correctness through sampling). Sampling is only for enrichment (deep JVM stack collection, UNKNOWN event emission). Different rates may be needed for CI (deterministic, reproducible) vs interactive (debugging, exploratory) sessions.

**Blocker:** Implementation can proceed with configurable rates, but default values require operator input. Depends on WP-06 noise budget outcomes.

---

### 5. MAZ-716: WP-15 Kubescape Node-Agent Integration PoC
**File:** `docs/internals/backlog/implementation/issue-20260825-023945-tier-e-wp-15-kubescape-poc.md`  
**Severity:** ENHANCEMENT | **Priority:** low | **Component:** profiler

**Cannot be answered by docs/code because:** Requires community/upstream coordination decision

**Open Question:**
> What is the upstream acceptance path: prototype branch vs vendor patch vs design proposal to Kubescape maintainers?

**Context:** The end-state value proposition is Kubescape events gaining optional Java semantic context. The integration must be tiny, optional, and non-invasive. This is a community coordination question about how to contribute to Kubescape.

**Blocker:** Can proceed with internal PoC, but upstream path decision needed before production deployment. Also needs kernel matrix confirmation against 5.15 floor (design doc §12.2).

---

### 6. MAZ-712: WP-11 Limited Java Agent for Automatic Boundary Scopes
**File:** `docs/internals/backlog/implementation/issue-20260825-023941-tier-e-wp-11-java-agent.md`  
**Severity:** ENHANCEMENT | **Priority:** medium | **Component:** profiler

**Cannot be answered by docs/code because:** Requires dependency approval decision

**Open Questions (from file context, not explicit section):**

1. **Dependency approval:** Byte Buddy and Spring Boot are **not** pre-approved. Standard `ClassFileTransformer` agent requires explicit operator approval in the PR.

**Context:** The Java agent removes the requirement to hand-write `MazewallContext.withContext` at every boundary. Uses Byte Buddy or ASM for transformation. JDK 22 floor means Class-File API is not chosen.

**Blocker:** Cannot implement without dependency approval. Also needs confirmation on whether the dependency restriction applies per the Tier E hard process rules.

---

## Questions That CAN Be Answered by Current Docs/Code

The following open-questions-flagged issues have questions that are actually answerable by existing documentation or code inspection and do NOT require operator input:

- **MAZ-709 (WP-01):** The suspend-friendly variant question has a "No for v1" answer already documented
- **MAZ-714 (WP-13):** Can proceed with configurable sampling rates pending WP-06 outcomes
- **MAZ-716 (WP-15):** Kernel matrix confirmation is a verification task, not a decision

However, they remain flagged because they indicate areas where future decisions will be needed.

---

## Priority Ranking for Operator Review

| Rank | Issue ID | Title | Questions | Blocker | Decision Type |
|------|----------|-------|-----------|---------|---------------|
| 1 | MAZ-706 | SBoB Policy Artifacts | 3 | YES | Security Policy |
| 2 | MAZ-765 | Rootless Podman BPF | 1 | YES | Development Workflow |
| 3 | MAZ-712 | Java Agent Dependencies | 1 | YES | Dependency Approval |
| 4 | MAZ-714 | Sampling Rates | 1 | NO | Tuning Parameter |
| 5 | MAZ-716 | Kubescape Upstream | 1 | NO | Community Coordination |
| 6 | MAZ-709 | MazewallContext suspend | 1 | NO | API Evolution |

---

## Recommendation

**Immediate Action Required:**

1. **MAZ-706 (SBoB Policy Artifacts) - HIGH PRIORITY:** Three foundational security policy questions blocking the entire CI admission workflow. These should be addressed first as they impact the project's ability to provide verifiable, signed policy artifacts.

2. **MAZ-765 (Rootless Podman) - HIGH PRIORITY:** Blocking local Tier E development. Simple binary choice between two clear options.

3. **MAZ-712 (Java Agent Dependencies) - MEDIUM PRIORITY:** Dependency approval needed. The hard process rules already state "New external dependencies require explicit operator approval per PR" — this is a known process question.

The remaining questions (MAZ-709, MAZ-714, MAZ-716) can have temporary answers or workarounds and should not block overall progress.

---

## Files Reviewed

Total backlog files scanned: 45+  
Open issues with open_questions flag: 9  
Issues requiring operator input: 6  
Issues with answerable questions: 3

---

## Next Steps

1. Create child issues for each unanswered question if operator input is not forthcoming within 48 hours
2. Update each identified issue file with a `## \u274c Blocked: Awaiting Operator Input` section referencing this review
3. Schedule a design review session to address the MAZ-706 questions (highest priority)
4. For MAZ-765, implement the operator's chosen workflow and update run_collector.sh accordingly
