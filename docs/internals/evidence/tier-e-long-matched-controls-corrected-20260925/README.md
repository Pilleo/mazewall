# Tier E corrected same-guest long matched controls (2026-09-25)

This is a **new diagnostic measurement**, not a replacement for the immutable
[first long-control archive](../tier-e-long-matched-controls/README.md). It uses
the corrected collector sampler (`scripts/tier_e_collector_cpu_sample.mjs`,
commit `8f724607`), which takes both `/proc/<pid>/stat` CPU counters from the
same second snapshot after confirming the collector's starttime has not changed.
The estimator also rejects cross-fork or missing identities, unexpected arms,
artifact errors and timeouts (commit `813f9702`). The independently implemented
acceptance verifier is now shipped at
`scripts/verify_tier_e_long_matched_controls.py` (commit `10195e1a`).
The first archive's README cites its original untracked `.omo/` verifier;
use this tracked script for either archived run. The first archive, decision,
README, and checksum ledger are left untouched.

## Provenance

- Disposable rootful guest: `mazewall-pidtest`, kernel `7.0.0-31-generic`,
  checkout `/home/ubuntu/mazewall-long-matched` at clean commit
  `10195e1a49b5bac7d1d86f37c36b410f1affaf25` throughout both runs.
  Guest and normal host `pid_max=4194304`; no PID-churn workload rerun.
- Source digest `d859a0aab57f8243be1988d2273fef27e84baa743b68802156758d0ffa5ec25d`,
  `input_dirty_count=0` before/after both runs. `source-state-after.json` adds
  `"unchanged":true` to the otherwise identical source state.
- Uniform image `sha256:00f840c947b3d56a45cc1d94d4d2e9e1a79d5ef45057932e577c9a0bcc8a1afa`,
  native SO `7d6459afeaa340b3a53510709946c6333d9d6d4e662a299d8ce0ec265af9ed5d`,
  JAR aggregate `b05a493f3c3c89df03dc238f154617e39b5d78ea14ecb6f9d0ad0ee4b412be04`.
- Pilot: 5 forks × 7 arms, 35/35 complete. Decision: 15 forks × 7 arms,
  105/105 complete (15 baseline, 90 profiled); all 90 profiled arms passed
  the exact 72-path/75,000-operation oracle. Target and collector containers
  were pinned to guest CPUs 0 and 1 respectively. Both terminal artifacts
  report unchanged source/binaries and `policy_eligibility=false`.
- Both runs used the following guest command, with `FORKS` and `OUTPUT`
  selected as `5`/`build/remediation-pilot-75000-5fork` and
  `15`/`build/remediation-decision-75000-15fork` respectively:

  ```bash
  TIER_E_SPLIT_COVERAGE_LONG_MATCHED=1 TIER_E_SPLIT_COVERAGE_FORKS=$FORKS \
  TIER_E_SPLIT_COVERAGE_OPERATIONS=75000 TIER_E_SPLIT_REUSE_BUILD=1 \
  TIER_E_SPLIT_WORKLOAD_CPU=0 TIER_E_SPLIT_COLLECTOR_CPU=1 \
  bash scripts/run_tier_e_split_coverage_matrix.sh "$OUTPUT"
  ```

## Paired diagnostic result

`decision.json` is `COMPLETE` with 15 matched fork pairs per variant and
emission mode, no incomplete entries, and `policy_eligibility=false`.

| Mode | Combined CPU ratio median (v1 / v2 / hybrid) | Incremental CPU median seconds (v1 / v2 / hybrid) | Hybrid/v1 incremental median (upper 95%) |
| --- | --- | --- | --- |
| full-stream | 13.1872 / 12.7869 / 13.2876 | 77.17 / 76.18 / 77.35 | 1.0023 (1.0419) |
| unique-edge | 13.0016 / 12.7707 / 13.4119 | 75.07 / 73.85 / 75.61 | 0.9989 (1.0342) |

The combined ratio remains collector-dominated: collector CPU is counted
from process start through post-terminal reporting, whereas baseline counts
only the target workload. Hybrid/v1 incremental CPU remains at parity within
noise in both modes. Differences from the first guest run cannot be assigned
solely to the sampler fix: these are separate runs with different source
revisions and conditions, not fork-by-fork matched old/new sampler readings.
Neither run is directly comparable to the historical short host calibrated
gate; the failed combined-CPU 1.25 and receipt-p99 gates are **not replaced**.
The operator's `REJECT_DEFAULT_PROMOTION` decision stands, hybrid stays opt-in
experimental, and `policy_eligibility=false` throughout.

## Reproduction and integrity

`tier-e-long-matched-corrected.tar.zst` contains both complete guest run trees,
including per-arm artifacts, terminal reports, matrix logs, estimator output,
and the full-directory independent acceptance verdict. The archive has 1916
file members (1434 decision + 482 pilot); `member-sha256-index.txt` was
created from one extraction and validated on a separate extraction, 1916/1916
files. `SHA256SUMS` covers every other file in this evidence directory.

```bash
cd docs/internals/evidence/tier-e-long-matched-controls-corrected-20260925
sha256sum -c SHA256SUMS
mkdir -p /tmp/tier-e-corrected-check
zstd -d -c tier-e-long-matched-corrected.tar.zst | tar -xf - -C /tmp/tier-e-corrected-check
(cd /tmp/tier-e-corrected-check && sha256sum -c "$OLDPWD/member-sha256-index.txt")
python3 ../../../../scripts/verify_tier_e_long_matched_controls.py \
  /tmp/tier-e-corrected-check/remediation-decision-75000-15fork
node ../../../../scripts/summarize_tier_e_long_matched_controls.mjs \
  fork-results.jsonl /tmp/tier-e-corrected-recomputed-decision.json
cmp decision.json /tmp/tier-e-corrected-recomputed-decision.json
```

The tracked Python verifier independently reports `PASS` (saved as
`acceptance-verdict.json`). Recomputing all 14 medians with Python's
`statistics.median` matches `decision.json` exactly
(`commands/recompute-output.txt`). The host estimator reproduced the guest
decision byte-for-byte; its command stdout and the independent verifier's
stdout are retained under `commands/`.
