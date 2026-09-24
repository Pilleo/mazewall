# Tier E same-guest long matched controls (2026-09-24/25)

Diagnostic evidence for the Tier E long matched v1/v2/hybrid coverage controls on
**one clean rootful guest**, with a profiler-only CPU denominator. This directory
records measurements only: it does **not** change any gate or policy. The prior
operator decision `REJECT_DEFAULT_PROMOTION` stands, Tier E remains opt-in
experimental, and every artifact carries `policy_eligibility=false`.

## Provenance

- Guest: `mazewall-pidtest`, checkout `/home/ubuntu/mazewall-long-matched`,
  kernel `7.0.0-31-generic`, 4 vCPU, Docker `29.1.3` (overlayfs),
  guest `/proc/sys/kernel/pid_max=4194304` (unchanged before and after), host
  PID setting untouched (4194304).
- Measurement runner commit: `978bb68d` (matrix, row builder).
  Estimator code commit: `0958f74c` (emission-mode separation fix, see
  Known issues). Guest tree clean (`input_dirty_count=0`) before and after.
- Command (started `2026-09-24T21:59:25+02:00`, terminal written
  `2026-09-24T23:57:59+02:00`, 105 arms, ~67.7 s/arm):

  ```
  TIER_E_SPLIT_COVERAGE_LONG_MATCHED=1 TIER_E_SPLIT_COVERAGE_FORKS=15 \
  TIER_E_SPLIT_COVERAGE_OPERATIONS=75000 TIER_E_SPLIT_REUSE_BUILD=1 \
  TIER_E_SPLIT_WORKLOAD_CPU=0 TIER_E_SPLIT_COLLECTOR_CPU=1 \
  bash scripts/run_tier_e_split_coverage_matrix.sh \
    /home/ubuntu/mazewall-long-matched/build/decision-75000-15fork
  ```

- CPU pinning: target container cpuset `0`, collector container cpuset `1`
  (verified live with `docker inspect .HostConfig.CpusetCpus` during the run;
  `host-environment-before/after.json` were captured by a separate shell
  without the pin variables and therefore record `none/none`).
- Frozen identities (single set across all 105 rows, `run-manifest.json`):
  - `source_digest`: `3f1e88d7cceeb122d740f9898d1f1d1bab813b4b62a795b7bcc3af5962a8c8c4`
  - `image_id`: `sha256:00f840c947b3d56a45cc1d94d4d2e9e1a79d5ef45057932e577c9a0bcc8a1afa`
  - `native_so_sha256`: `7d6459afeaa340b3a53510709946c6333d9d6d4e662a299d8ce0ec265af9ed5d`
  - `daemon_jars_sha256`: `b05a493f3c3c89df03dc238f154617e39b5d78ea14ecb6f9d0ad0ee4b412be04`
- Raw input: `fork-results.jsonl`, sha256
  `8079df002b729468658370562c6ccc33e4c2d92653bdbea94f4cd868c525d896`
  (identical on guest and host; unchanged by the estimator fix).

## Result

`decision.json` (estimator output, `metrics_status=COMPLETE`, 105 rows,
15 complete forks, no incomplete entries):

| mode | metric | v1 | v2 | hybrid |
| --- | --- | --- | --- | --- |
| full-stream | combined CPU ratio (median / upper 95) | 14.8718 / 15.6075 | 14.4167 / 15.8095 | 14.5440 / 15.8580 |
| full-stream | incremental CPU (median) | 75.74 s | 74.64 s | 75.40 s |
| unique-edge | combined CPU ratio (median / upper 95) | 14.5301 / 15.7717 | 14.4179 / 15.1170 | 14.3834 / 15.2943 |
| unique-edge | incremental CPU (median) | 74.18 s | 73.91 s | 74.34 s |
| full-stream | hybrid/v1 incremental ratio | | 1.0137 (upper 95: 1.0327) | |
| unique-edge | hybrid/v1 incremental ratio | | 0.9894 (upper 95: 1.0212) | |

Interpretation limits:

- These are **diagnostic** observations for this coverage workload. They do not
  replace the historical combined CPU 1.25 gate or the receipt-p99 gate
  (`existing_gates_note` in `decision.json`), and they are not a promotion
  criterion.
- Denominators are profiler-only but **collector-dominated**: the combined CPU
  is `target_cpu_ns + collector_cpu_ns` where collector CPU uses the historical
  `process_ticks_since_start` basis and idles/busy-spins for the whole arm wall
  time (~62-72 s), while the baseline arm only accounts for the target's
  ~5-6 s. Ratios near 14-15x are a property of this workload accounting, not a
  regression against earlier benchmark gates.
- Hybrid vs v1 incremental CPU is at parity within noise in both emission modes
  (upper 95% bound <= 1.033); no mode shows a hybrid cost advantage or
  penalty that clears noise.

## Verification performed

- Independent Python recomputation of every median directly from the raw
  JSONL matched `decision.json` exactly (`commands/recompute-output.txt`, rc 0).
- Independent Python acceptance verifier (`.omo/evidence/tier-e-evidence-and-matched-controls/task-9-verifier.py`)
  passed on the guest run with exact counts: 105 arm-result records, 90
  profiled terminal reports, 15 baseline summaries, 90 profiled summaries,
  `input_dirty_count=0` (`commands/verifier-guest-pass.json`).
- Failure replay QA (both exit 1 with `metrics_status=INCONCLUSIVE`):
  - one-row image identity mutation (`commands/drift-replay.jsonl`) →
    `source_identity_mismatch` (`commands/drift-stdout.txt`);
  - a zero v1 incremental denominator (`commands/zero-v1-replay.jsonl`) →
    `nonpositive_v1_denominator`, hybrid/v1 pairs 14, `upper_95=null`
    (`commands/zero-v1-stdout.txt`).
- Truncated JSONL (104 rows) rejected by the verifier with
  `row_count: expected 105, observed 104` (`commands/verifier-guest-truncation.json`).
- Archive members: `tier-e-long-matched-raw.tar.zst` (sha256
  `581ecf0b2ffc30ece0b2c709df9078ca328246c1c25799a22da427b144ea9c55`)
  extracted into a second scratch directory and validated 1948/1948 files
  against `member-sha256-index.txt` (see `archive-manifest.json`).

## Archive contents and validation

`tier-e-long-matched-raw.tar.zst` contains the complete decision run
(`decision-75000-15fork/`, 1434 files), the bounded arm-timeout failure
diagnostic (`diag-1fork/`, 33 files, all seven arms `INCOMPLETE`/exit 124 with
retained logs), and the five-fork correctness-gate pilot
(`pilot-75000-5fork/`, 481 files).

To validate:

```bash
zstd -d tier-e-long-matched-raw.tar.zst -c | tar -x -C /tmp/scratch
cd /tmp/scratch && sha256sum -c <path>/member-sha256-index.txt
```

`SHA256SUMS` covers every tracked file in this directory (README, decision,
raw JSONL, member index, archive, source/input identities, command captures)
and is checked with `sha256sum -c SHA256SUMS` from this directory.

## Known issues

- The first estimator run (commit `978bb68d`) keyed combined CPU only by
  variant, so both emission modes reported the same (unique-edge) rows; the
  exact-equality of the two modes' medians exposed it. Fixed in `0958f74c`
  with a regression test that differentiates emission CPU; `decision.json` was
  regenerated from the **unchanged** raw JSONL (sha256 above). The superseded
  derived output was discarded, not archived.
- Baseline arm directories contain a `collector.log` written by the fixture
  exit trap; it holds `docker logs` "No such container" text because baseline
  never starts a collector container. No baseline directory has a
  `collector-process-metrics.json` (0 matches in `member-sha256-index.txt`).
