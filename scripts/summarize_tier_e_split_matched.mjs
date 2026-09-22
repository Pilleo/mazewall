#!/usr/bin/env node
import fs from 'node:fs';

const [input, output] = process.argv.slice(2);
if (!output) throw new Error('usage: summarize_tier_e_split_matched.mjs fork-results.jsonl paired-summary.json');
const rows = fs.readFileSync(input, 'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
const median = values => {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted.length % 2 ? sorted[Math.floor(sorted.length / 2)] :
    (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;
};
const forkMedian = row => row.measured_batch_count === 10 && row.workload_ns.length === 10 ? median(row.workload_ns) : null;
const baselines = new Map(rows.filter(row => row.mode === 'baseline').map(row => [row.fork, forkMedian(row)]));
let seed = 0x4d5a2026;
const random = () => {
  seed ^= seed << 13;
  seed ^= seed >>> 17;
  seed ^= seed << 5;
  return (seed >>> 0) / 0x100000000;
};
const quantile = (sorted, p) => sorted[Math.floor(p * (sorted.length - 1))];
const modes = {};
for (const mode of [...new Set(rows.map(row => row.mode))]) {
  const group = rows.filter(row => row.mode === mode);
  const paired = group.filter(row => forkMedian(row) !== null && baselines.get(row.fork) > 0)
    .map(row => ({fork: row.fork, ratio: forkMedian(row) / baselines.get(row.fork)}));
  const draws = [];
  if (paired.length >= 2) {
    for (let i = 0; i < 10000; i++) {
      const sample = Array.from({length: paired.length}, () => paired[Math.floor(random() * paired.length)].ratio);
      draws.push(median(sample));
    }
    draws.sort((a, b) => a - b);
  }
  const timings = group.flatMap(row => row.workload_ns);
  modes[mode] = {
    fork_count: group.length,
    complete_batch_forks: group.filter(row => row.status === 0 && forkMedian(row) !== null).length,
    measured_batch_median_ns: timings.length ? median(timings) : null,
    paired_fork_median_ratio_to_baseline: paired.length ? median(paired.map(row => row.ratio)) : null,
    paired_bootstrap_95pct_ratio: draws.length ? [quantile(draws, 0.025), quantile(draws, 0.975)] : null,
    runtime_overhead_verdict: 'UNVERIFIED',
  };
}
const result = {schema_version: 1, calibration_target: 'unmet', bootstrap_resamples: 10000,
  paired_unit: 'fork median of ten measured batches', modes, policy_eligibility: false};
fs.writeFileSync(output, `${JSON.stringify(result, null, 2)}\n`);
console.log(JSON.stringify(result));
