---
title: "Preserve canonical issue identities in any retained standalone ingestion"
severity: "MEDIUM"
status: "open"
priority: medium
dependencies: ["issue-20261103-235259-123-retire-removed-orchestrator-entrypoints-before-con"]
component: "tooling"
target_modules: []
target_files:
  - "scripts/paperclip_backlog_sync.kts"
target_symbols:
  - "parseIssue"
open_questions: false
---

# 🟡 [Severity: MEDIUM]: Preserve canonical issue identities in any retained standalone ingestion

**Context:**
Repository-wide review, 2026-11-04: [TOOL-03](../reviews/2026-11-04-repository-review-findings.md#tool-03). The source review and reproductions below are not release qualification or implementation approval. Historical resolved issues remain archived; this is their newly scoped residual where applicable.

**Category / confidence / boundary:** source-confirmed identity defect with static corpus reproduction; high; optional Paperclip ingest, conditional on retaining that route.

**Evidence:**

- `scripts/paperclip_backlog_sync.kts:115–124` uses only `issue-<date>-<time>` when no frontmatter `id` exists. It discards both the numeric discriminator and slug used by current canonical issue filenames/dependency strings.
- Those IDs key sync ownership at `449–456,533–534` and dependency resolution at `463–474`. An unrecognized dependency is explicitly treated as resolved/stale and non-blocking at `472`.
- Concrete current input: `docs/internals/backlog/issue-20261103-090026-517-qualify-actor-style-stateful-portal-bindings-on-th.md:6–9` depends on the full `issue-20261103-090026-112-add-explicit-portal-warm-up-through-existing-group`, whose valid open/high-priority file is present. The script derives `issue-20261103-090026` for both files and cannot match that full dependency key.
- Read-only inventory applying the exact filename fallback found **23 same-ID groups / 60 active files**. It also found **38 existing active full-stem dependency edges** that do not match the script's derived IDs. These are static filename/frontmatter observations, not a live sync result or proof that every file passes all other parser rules.

**Trigger / consequence:** ingest current same-second issue siblings, or a current full-stem dependency. Dependency gates silently disappear; if one colliding sibling already carries a sync ID, another can be classified as already synced, and force-maintenance can address the wrong board record. All-unsynced siblings can instead create distinct board issues but overwrite the shared map entry. No board mutation was performed to demonstrate these downstream effects.

**Smallest complete correction:** if this route remains supported, use the canonical ADK identity/resolution contract, preserve unique full identities, validate uniqueness before effects, and distinguish an actually resolved dependency from a malformed/unresolved reference. Do not rebuild a second general frontmatter parser just to retain a retired launcher.

**Regression:** disposable fixture with the actual current filename form, two same-second siblings, one pre-synced sibling and full-stem dependencies; assert distinct IDs, correct blocker ownership, no accidental skip/PATCH, and idempotent rerun. Malformed/ambiguous identities must fail before HTTP/writeback.

**Tracking / dependencies:** **NEW concrete manifestation** of known parser drift in resolved `issue-20260823-181010-extract-backlog-parser-from-paperclip-sync-script.md:23–36` (its “no second inlined parser” completion condition is false in the current tree). Also relates to resolved `issue-20260823-181011-implement-paperclip-issue-ingest-api.md:28–35`. TOOL-02 determines whether this code should remain operational. This check covers the simple identity slice; the full parser/control-plane authority logic is **not deeply reviewed** and is escalated below.

**Needed:**
1. Implement only the bounded correction described above, first reproducing its trigger with the stated negative and positive controls. Preserve all ownership, failure and compatibility invariants; run owning focused checks and the project completion gate before closure. Keep native, hosted and published-artifact qualification separate from source-only or isolated evidence.

## Validated-ingest snapshot follow-up — 2026-11-05

[Recursive review F-02](../reviews/2026-11-05-state-complexity-review.md)
adds two manifestations in the retained script. Its independent whole-file regex
at `scripts/paperclip_backlog_sync.kts:449–456` misses quoted frontmatter UUIDs.
A read-only exact-regex inventory found 12 missed active bindings, including
`security/issue-067-unprivileged-pivot-root-empty-tmpfs.md:13`. This is static
matching evidence, not twelve eligible creates or observed duplicate board cards.

The `mapNotNull` parse pipeline at `:81–90,140–143` also erases malformed active
blockers before absence is treated as resolved at `:463–476`. Correct canonical
IDs alone do not repair either state ambiguity.

**If this ingestion route is retained:** admit one complete immutable parsed
snapshot with canonical IDs and frontmatter-only validated
`Unbound | Bound(uuid)` bindings before planning effects. A rejected/unreadable
snapshot member or unknown reference is not a resolved dependency. Keep filesystem,
HTTP/writeback and locking outside pure dependency/create planning; reuse the ADK
identity contract, not another general YAML framework.

Acceptance: quoted/unquoted UUIDs preserve the same existing binding and produce
no duplicate POST on rerun; body-only markers cannot bind. Exercise A→B with B
active-bound, active-unbound, explicitly resolved, malformed, unreadable and absent.
Invalid/unknown blockers reject before any HTTP/writeback; explicit resolution
and legitimate unsynced creation remain legal. No live board action ran here.

Keep the existing retirement dependency: removing this route may eliminate this
work. This review does not authorize restoring the deleted backend or activating
ingestion. Existing unsuccessful-write/exit-status ownership remains separate.

---
<!-- id: issue-20261103-235259-662-preserve-canonical-issue-identities-in-any-retaine  file: issue-20261103-235259-662-preserve-canonical-issue-identities-in-any-retaine.md -->
