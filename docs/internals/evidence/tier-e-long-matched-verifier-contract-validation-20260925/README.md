# Tier E verifier contract validation (2026-09-25)

This directory records a fresh, **read-only** revalidation of the two retained
15-fork decision archives with the strict independent acceptance verifier at
commit `b23744eab3f236e3583150837a327e4da7490986` (SHA-256 in
`verifier-sha256.txt`). The measurement archives and decisions were not edited;
the prior diagnostic/opt-in policy and `REJECT_DEFAULT_PROMOTION` remain in
force, with `policy_eligibility=false`.

## Changes validated

- Malformed `fork`/`arm` values are rejected before grouping and sorting;
  CPU values exceeding the JavaScript safe-integer range and absent/non-list
  `artifact_errors` metadata are incomplete evidence (commit `f79c083a`).
- Every oracle path is a distinct ID from 0 through 71 with the workload's
  exact category, `pathNN` method, `openat` syscall number 257, and declared
  75,000-operation frequency (3000 common, 124 infrequent, 1 singleton);
  path order is irrelevant (commit `77fb3c25`).
- Every complete terminal requires the five evidence dimensions to be exactly
  `true` and `failed_dimensions` to be an empty list, even when the summary
  repeats the same terminal JSON (commit `b23744ea`).

## Results

| Check | First archive | Corrected CPU archive |
| --- | --- | --- |
| Existing `SHA256SUMS` | 25/25 OK, exit 0 | 19/19 OK, exit 0 |
| Member index, fresh extraction | 1948/1948 OK, exit 0 | 1916/1916 OK, exit 0 |
| Full-directory verifier | PASS, 105 rows, 105 arm results, 90 profiled terminal reports, 15 baseline and 90 profiled summaries; exit 0 | PASS, same counts; exit 0 |
| JSONL-only verifier | PASS, exit 0 | PASS, exit 0 |
| Estimator | COMPLETE, exit 0; byte-identical decision | COMPLETE, exit 0; byte-identical decision |

The archived five-fork pilots are not inputs to the decision verifier. The
eight original review probes are listed with exact pytest node IDs and passing
results in `commands/review-eight-probes.log`. Each test asserts exit 1,
parseable `INCOMPLETE`, and a relevant error: null or duplicate oracle paths,
contradictory terminal evidence, nonempty `failed_dimensions`, array `fork`,
null `arm`, a 401-digit CPU integer, and missing `artifact_errors`. All 69
Python tests passed (`commands/pytest-all.log`). The tracked test module also
exercises the other schema and artifact mutations individually.

## Reproduction from repository root

```bash
O=docs/internals/evidence/tier-e-long-matched-controls
C=docs/internals/evidence/tier-e-long-matched-controls-corrected-20260925
(cd "$O" && sha256sum -c SHA256SUMS)
(cd "$C" && sha256sum -c SHA256SUMS)
mkdir -p /tmp/tier-e-contract-original /tmp/tier-e-contract-corrected
zstd -d -c "$O/tier-e-long-matched-raw.tar.zst" | tar -xf - -C /tmp/tier-e-contract-original
zstd -d -c "$C/tier-e-long-matched-corrected.tar.zst" | tar -xf - -C /tmp/tier-e-contract-corrected
(cd /tmp/tier-e-contract-original && sha256sum -c "$OLDPWD/$O/member-sha256-index.txt")
(cd /tmp/tier-e-contract-corrected && sha256sum -c "$OLDPWD/$C/member-sha256-index.txt")
python3 scripts/verify_tier_e_long_matched_controls.py /tmp/tier-e-contract-original/decision-75000-15fork
python3 scripts/verify_tier_e_long_matched_controls.py /tmp/tier-e-contract-corrected/remediation-decision-75000-15fork
python3 -m pytest scripts/tests/test_verify_tier_e_long_matched_controls.py -q
```

`commands/original-jsonl-only.json` and `commands/corrected-jsonl-only.json`
are separately labeled JSONL-only; neither claims to check raw artifacts.
`SHA256SUMS` covers every other file in this directory.
