---
title: "Include approved bridge license material in selected-module distributions"
severity: "MEDIUM"
status: "open"
priority: high
dependencies: []
component: "profiler"
target_modules: [profiler]
target_files:
  - "profiler/hotspot-agentless/internal/tooling/distribution.go"
target_symbols:
  - "releaseDistribution"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Include approved bridge license material in selected-module distributions

**Context:**
Original review context, before the delegated check:
PR543 review executed the unchanged selected-module notice check against the actual published v0.1.0 and candidate bridge directories; both lack license material and block strict distribution. Root README declares Apache 2.0 but no root LICENSE exists. Owner confirmation remains required; immutable versions cannot be edited.

**Needed:**
Required at filing, with the later authority ruling recorded below:
1. Obtain owner confirmation before adding legal material; test the real selected SDK notice plus a missing-notice copy without weakening the fail-closed distribution check.

**Documented authority / local evidence:**
The operator's “please check yourself” delegated verification of owner Pilleo's
existing project-wide Apache grant, not fresh verbal/title certification. Actual
owner declaration `c1e83ee34b78464ba85d10d84bc260361e912d47` and current line
`5af41a04639f299cbd8609ed2644b9215ca3ca1b` were checked; no SDK override was found.
The local ownership question is answered by that recorded check (`open_questions:
false`), not a legal guarantee or publication approval. Local provenance is
`license-research/result.json` in `.superpowers/sdd/2026-12-16-shared-hotspot-forward/`.

SDK `7a714bfe6ca514679c9997f4e734f6398f2f1f7d` adds only canonical
`hotspot-bridge/LICENSE`:11358bytes, SHA256
`cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30`, unchanged
official Apache2.0 text/Appendix. No holder, new terms or root LICENSE was invented.
Collector `1f89ea42829efff873b11a80590542e6f593e19e` retains both owners' identical
private helper/tests: actual selected-module missing-license RED→6/6GREEN,0skips
per separate548-module workspace; removing discovered notices from the real copied
SDK still rejects. A3 is SpecPASS/QualityAPPROVED; isolated31-file SDK607race PASS
records/0failures/0skips and final combined configured source gate also pass.

**Remaining gate:**
Stay open/unpublished; v0.1.0 remains immutable and still lacks material. Separate
exact-source/control-plane publication approval, actual remotely resolved new tag/
sums, strict released-graph distribution and native qualification are still required.
See [current release checkpoint](../../plans/2026-10-19-hotspot-bridge-release.md#current-shared-review-checkpoint)
for actual receipts and configuration/cache/ENOSYS limits. No title guarantee follows.

---
<!-- id: issue-20261019-163139-921-include-approved-bridge-license-material-in-select  file: issue-20261019-163139-921-include-approved-bridge-license-material-in-select.md -->
