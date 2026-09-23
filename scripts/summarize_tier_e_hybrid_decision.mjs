#!/usr/bin/env node
import fs from 'node:fs';

const [workloadPath, phasesPath, output, faultPath] = process.argv.slice(2);
if (!workloadPath || !phasesPath || !output) {
  throw new Error('usage: summarize_tier_e_hybrid_decision.mjs workload-results.jsonl instrumented-results.jsonl output.json');
}
const rows = path => fs.readFileSync(path, 'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
const workload = rows(workloadPath), phases = rows(phasesPath);
const faults = faultPath ? rows(faultPath) : [];
const median = values => {
  const ordered = [...values].sort((a, b) => a - b);
  const mid = Math.floor(ordered.length / 2);
  return ordered.length % 2 ? ordered[mid] : (ordered[mid - 1] + ordered[mid]) / 2;
};
let state = 0x4d5a2026;
const random = () => {
  state ^= state << 13; state ^= state >>> 17; state ^= state << 5;
  return (state >>> 0) / 0x100000000;
};
const upper95 = values => {
  if (values.length !== 15 || values.some(value => !Number.isFinite(value))) return null;
  const draws = Array.from({length: 10000}, () => median(Array.from({length: values.length}, () => values[Math.floor(random() * values.length)])));
  draws.sort((a, b) => a - b);
  return draws[Math.floor(0.975 * (draws.length - 1))];
};
const paired = (data, baseline, variant, metric) => {
  const base = new Map(data.filter(row => row.mode === baseline && row.fork_complete).map(row => [row.fork, row]));
  return data.filter(row => row.mode === variant && row.fork_complete && base.has(row.fork))
    .map(row => ({fork: row.fork, baseline: metric(base.get(row.fork)), candidate: metric(row)}))
    .sort((a, b) => a.fork - b.fork);
};
const forkMedian = row => row.workload_ns?.length === 10 ? median(row.workload_ns) : null;
const cpu = row => row.target_cpu_ns != null && row.collector_cpu_ns != null ? row.target_cpu_ns + row.collector_cpu_ns : null;
const receipt = row => row.collector_receipt?.receipt_p99_fine_upper_ns ?? null;
const modes = {};
for (const emission of ['full-stream', 'unique-edge']) {
  const prefix = `dictionary-${emission}`;
  const base = `${prefix}-v1`, v2 = `${prefix}-v2`, hybrid = `${prefix}-hybrid`;
  const workloadPairs = paired(workload, base, hybrid, forkMedian);
  const cpuPairs = paired(workload, base, hybrid, cpu);
  const markerPairs = paired(workload, base, v2, forkMedian);
  const mapPairs = paired(workload, v2, hybrid, forkMedian);
  const receiptPairs = paired(phases, base, hybrid, receipt);
  const ratios = pairs => pairs.map(({baseline, candidate}) => baseline > 0 && candidate != null ? candidate / baseline : NaN);
  const receiptAllowance = receiptPairs.map(({baseline, candidate}) => baseline > 0 && candidate != null
    ? (candidate - baseline) / Math.max(100000, baseline * 0.10) : NaN);
  const workloadUpper = upper95(ratios(workloadPairs));
  const cpuUpper = upper95(ratios(cpuPairs));
  const receiptUpper = upper95(receiptAllowance);
  modes[emission] = {
    paired_unit: 'fork median of 10 measured batches',
    pairs: {workload: workloadPairs.length, cpu: cpuPairs.length, receipt_p99: receiptPairs.length},
    v2_over_v1_workload_ratio_median: markerPairs.length ? median(ratios(markerPairs)) : null,
    hybrid_over_v2_workload_ratio_median: mapPairs.length ? median(ratios(mapPairs)) : null,
    hybrid_over_v1_workload_ratio_upper_95: workloadUpper,
    hybrid_over_v1_combined_cpu_ratio_upper_95: cpuUpper,
    receipt_p99_allowance_fraction_upper_95: receiptUpper,
    workload_gate: workloadUpper == null ? 'INCONCLUSIVE' : workloadUpper <= 1.25 ? 'PASS' : 'FAIL',
    combined_cpu_gate: cpuUpper == null ? 'INCONCLUSIVE' : cpuUpper <= 1.25 ? 'PASS' : 'FAIL',
    receipt_p99_gate: receiptUpper == null ? 'INCONCLUSIVE' : receiptUpper <= 1 ? 'PASS' : 'FAIL',
  };
}
const faultComplete = faults.length === 30 && faults.every(row => row.complete &&
  row.confirmed_ring_loss_recoveries >= 24 && row.discovery_samples > 0 &&
  row.discovery_p99_upper_ns <= 500000000);
const resourceFailed = Object.values(modes).some(mode =>
  [mode.workload_gate, mode.combined_cpu_gate, mode.receipt_p99_gate].includes('FAIL'));
const result = {schema_version: 1, bootstrap_resamples: 10000, bootstrap_seed: '0x4d5a2026',
  paired_unit: 'fork', modes,
  recovery_p99_dependency_relative_gate: faultPath ? (faultComplete ? 'PASS' : 'FAIL') : 'UNVERIFIED',
  fault_forks: faults.length,
  publication_reopening_gate: 'UNVERIFIED',
  adoption_decision: resourceFailed ? 'REJECT_DEFAULT_PROMOTION' : 'INCONCLUSIVE', policy_eligibility: false};
fs.writeFileSync(output, JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify(result));
