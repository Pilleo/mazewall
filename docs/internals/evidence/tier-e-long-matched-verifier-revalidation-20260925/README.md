# Tier E long-matched verifier revalidation (2026-09-25)

Revalidation of both retained fifteen-fork measurement archives with the
hardened independent verifier (`scripts/verify_tier_e_long_matched_controls.py`,
commit `b718f6d1`, SHA-256 in `verifier-sha256.txt`) and the hardened
estimator (`scripts/summarize_tier_e_long_matched_controls.mjs`).
Nothing in either measurement archive was modified; both decisions recompute
byte-identically.

## Scope

- Original archive: `../tier-e-long-matched-controls/` (decision run
  `decision-75000-15fork`: 105 arms; pilot and timeout diagnostic excluded).
- Corrected archive:
  `../tier-e-long-matched-controls-corrected-20260925/` (decision run
  `remediation-decision-75000-15fork`: 105 arms).
- The five-fork pilots are not fifteen-fork decision inputs and are not
  accepted by the verifier.

The verifier reports a `verification_mode` field so a JSONL-only success
cannot be presented as proof that raw arm artifacts were checked.

## Results

| Check | Original | Corrected |
|---|---|---|
| Evidence `SHA256SUMS` | 25/25 OK, exit 0 | 19/19 OK, exit 0 |
| Archive member index on fresh extraction | 1948/1948 OK, exit 0 | 1916/1916 OK, exit 0 |
| Full-directory verification | `PASS`, 105 rows, 105/90/15/90 artifacts, exit 0 | `PASS`, same counts, exit 0 |
| JSONL-only verification | `PASS`, exit 0 | `PASS`, exit 0 |
| Estimator decision | `COMPLETE`, exit 0, byte-identical to retained `decision.json` | `COMPLETE`, exit 0, byte-identical |

Mutation probes (`commands/mutation-probes.json`, inputs and outputs under
`commands/probe-*`): every original review reproduction now fails closed —
estimator cross-fork drift, globally missing identity, unexpected eighth arm,
and artifact errors each exit 1 with `INCONCLUSIVE` and an explicit reason;
verifier NaN CPU, split source snapshots, and a missing row each exit 1 with
`INCOMPLETE`. Full-directory artifact mutations (missing/corrupt/empty
artifacts, mismatched arm/fork/seed/CPU/coverage/terminal, swapped files,
collector metrics in a baseline directory, renamed directories) are covered
by `scripts/tests/test_verify_tier_e_long_matched_controls.py`
(`test_full_directory_mutations_fail_closed`).

## Reproduction

```bash
D=docs/internals/evidence/tier-e-long-matched-verifier-revalidation-20260925
(cd docs/internals/evidence/tier-e-long-matched-controls && sha256sum -c SHA256SUMS)
(cd docs/internals/evidence/tier-e-long-matched-controls-corrected-20260925 && sha256sum -c SHA256SUMS)
mkdir -p /tmp/reval-orig /tmp/reval-corr
zstd -d -c docs/internals/evidence/tier-e-long-matched-controls/tier-e-long-matched-raw.tar.zst | tar -xf - -C /tmp/reval-orig
zstd -d -c docs/internals/evidence/tier-e-long-matched-controls-corrected-20260925/tier-e-long-matched-corrected.tar.zst | tar -xf - -C /tmp/reval-corr
(cd /tmp/reval-orig && sha256sum -c "$OLDPWD/docs/internals/evidence/tier-e-long-matched-controls/member-sha256-index.txt")
(cd /tmp/reval-corr && sha256sum -c "$OLDPWD/docs/internals/evidence/tier-e-long-matched-controls-corrected-20260925/member-sha256-index.txt")
python3 scripts/verify_tier_e_long_matched_controls.py /tmp/reval-orig/decision-75000-15fork
python3 scripts/verify_tier_e_long_matched_controls.py /tmp/reval-corr/remediation-decision-75000-15fork
python3 scripts/verify_tier_e_long_matched_controls.py --jsonl-only \
  docs/internals/evidence/tier-e-long-matched-controls/fork-results.jsonl \
  docs/internals/evidence/tier-e-long-matched-controls/run-manifest.json \
  docs/internals/evidence/tier-e-long-matched-controls/source-state-before.json \
  docs/internals/evidence/tier-e-long-matched-controls/source-state-after.json \
  docs/internals/evidence/tier-e-long-matched-controls/terminal.json
node scripts/summarize_tier_e_long_matched_controls.mjs \
  docs/internals/evidence/tier-e-long-matched-controls/fork-results.jsonl /tmp/recomputed-decision.json
cmp /tmp/recomputed-decision.json docs/internals/evidence/tier-e-long-matched-controls/decision.json
```

`SHA256SUMS` covers every file in this directory except itself.
`policy_eligibility=false` throughout; the operator's
`REJECT_DEFAULT_PROMOTION` decision stands.
