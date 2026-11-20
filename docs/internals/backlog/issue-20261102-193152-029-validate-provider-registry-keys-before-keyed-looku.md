---
title: "Validate provider registry keys before keyed lookups"
severity: "HIGH"
status: "open"
priority: high
dependencies: []
component: "core"
target_modules: []
target_files:
  - ".superpowers/sdd/2026-10-27-hotspot-archive-requalification/remaining-images-3/recovery-bootstrap-correctness-1/bootstrap_entry.py"
target_symbols:
  - "_checkpoint_provider_state"
open_questions: false
---

# 🟡 [Severity: HIGH]: Validate provider registry keys before keyed lookups

**Context:**
P1: original provider dictionaries can contain in-place colliding foreign keys whose equality executes during ledger, parent or role-count lookup before key validation. Matching values can mask a foreign association. Evidence: .superpowers/sdd/2026-11-02-profiler-delivery-critical-path/checkpoint-provider-review.md. No live operation/provider qualification is claimed.

**Needed:**
1. Reproduce parent, ledger and role-key collision traps in original dictionaries; validate raw finite keys and issued identities before keyed operations and prove zero trap execution/no input debit or open, then scoped re-review.

## Repository review addendum — 2026-11-04 (RET-01)

[Residual finding](../reviews/2026-11-04-repository-review-findings.md#ret-01).
The original parent/ledger/role-key guards are source-fixed and retain historical
credit. Connected provider attribute and original Member registries still perform
keyed access before authenticating their raw associations: in the target entry,
`_checkpoint_provider_state:2137–2146` uses `attrs.get`, and
`_checkpoint_member_entry:2060–2075` looks up an opaque token in the original map.
An in-place colliding foreign key can execute equality while preserving accepted
value identity. Disposable Python semantics reproduced that behavior; the recovery
modules were not imported or executed.

Extend this issue with provider-attribute and Member-registry mutations, validating
finite raw string/opaque associations before lookup or attribute traversal. Require
zero trap calls, no debit/open and unchanged original owner/Member/counters beyond
the injected mutation, through both validation and acquisition. Preserve genuine
role-count decreases and attempted-once custody. This is an expansion, not a duplicate
ticket or reversal of the original fix; the retained recovery route stays paused.

---
<!-- id: issue-20261102-193152-029-validate-provider-registry-keys-before-keyed-looku  file: issue-20261102-193152-029-validate-provider-registry-keys-before-keyed-looku.md -->
