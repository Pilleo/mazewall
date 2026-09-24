import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const SCRIPT = fileURLToPath(new URL('../summarize_tier_e_long_matched_controls.mjs', import.meta.url));

const ARMS = [
  'baseline',
  'dictionary-full-stream-v1',
  'dictionary-full-stream-v2',
  'dictionary-full-stream-hybrid',
  'dictionary-unique-edge-v1',
  'dictionary-unique-edge-v2',
  'dictionary-unique-edge-hybrid',
];

const COMMON = {
  declared_operations: 75000,
  source_digest: '95815206cb57cbe198d71cd2a41b6befa02d2d16cd81691055c5258f178963e5',
  image_id: 'sha256:00f840c947b3d56a45cc1d94d4d2e9e1a79d5ef45057932e577c9a0bcc8a1afa',
  native_so_sha256: '7d6459afeaa340b3a53510709946c6333d9d6d4e662a299d8ce0ec265af9ed5d',
  daemon_jars_sha256: '330f65a443549f8de80ee4b1adc3c550e9582316992a9f33038c551148d74625',
  status: 0,
  policy_eligibility: false,
};

// Hand-derived literal CPU table (ns): baseline target 1000; v1 combined 1500;
// v2 combined 1550; hybrid combined 2000. All 15 forks identical by construction.
const LITERAL_CPU = {
  baseline: { target: 1000, collector: null },
  v1: { target: 1400, collector: 100 },
  v2: { target: 1450, collector: 100 },
  hybrid: { target: 1400, collector: 600 },
};

function makeRow(fork, arm) {
  const variant = arm === 'baseline' ? 'baseline' : arm.split('-').at(-1);
  const emission = arm === 'baseline' ? null : arm.slice('dictionary-'.length, arm.lastIndexOf('-'));
  const cpu = LITERAL_CPU[variant];
  const profiled = arm !== 'baseline';
  return {
    fork,
    arm,
    emission,
    variant,
    seed: 23063 + fork - 1,
    ...COMMON,
    coverage_verdict: profiled ? 'PASS' : 'NOT_APPLICABLE',
    covered_paths: profiled ? 72 : null,
    terminal_integrity: profiled ? 'COMPLETE' : null,
    target_cpu_ns: cpu.target,
    target_sample_boundary: 'after_workload_before_exit',
    collector_cpu_ns: profiled ? cpu.collector : null,
    collector_sample_boundary: profiled ? 'after_terminal_report_before_shutdown' : null,
  };
}

function makeRows(forks = 15) {
  const rows = [];
  for (let fork = 1; fork <= forks; fork++) {
    for (const arm of ARMS) rows.push(makeRow(fork, arm));
  }
  return rows;
}

function runEstimator(rows, name) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tier-e-long-'));
  try {
    const input = path.join(dir, 'fork-results.jsonl');
    fs.writeFileSync(input, rows.map(row => JSON.stringify(row)).join('\n') + '\n');
    const output = path.join(dir, 'decision.json');
    const result = spawnSync(process.execPath, [SCRIPT, input, output], { encoding: 'utf8' });
    const decision = fs.existsSync(output) ? JSON.parse(fs.readFileSync(output, 'utf8')) : null;
    return { status: result.status, stderr: result.stderr, stdout: result.stdout, decision, name };
  } finally {
    fs.rmSync(dir, { recursive: true });
  }
}

test('15-fork synthetic fixture returns positive overhead ratios and exact 15 pair counts', () => {
  const run = runEstimator(makeRows(15));
  assert.equal(run.status, 0, run.stderr);
  const { decision } = run;
  assert.equal(decision.bootstrap_seed, '0x4d5a2026');
  assert.equal(decision.bootstrap_resamples, 10000);
  assert.equal(decision.paired_unit, 'fork');
  assert.equal(decision.policy_eligibility, false);
  assert.equal(decision.prior_adoption_decision, 'REJECT_DEFAULT_PROMOTION');
  assert.equal(decision.metrics_status, 'COMPLETE');
  assert.deepEqual(decision.incomplete, []);
  for (const emission of ['full-stream', 'unique-edge']) {
    const mode = decision.modes[emission];
    assert.deepEqual(mode.pairs, { baseline: 15, v1: 15, v2: 15, hybrid: 15 });
    assert.equal(mode.combined_cpu_ratio.v1.median, 1.5);
    assert.equal(mode.combined_cpu_ratio.v1.upper_95, 1.5);
    assert.equal(mode.combined_cpu_ratio.v1.status, 'COMPLETE');
    assert.equal(mode.combined_cpu_ratio.v2.median, 1.55);
    assert.equal(mode.combined_cpu_ratio.hybrid.median, 2);
    assert.equal(mode.combined_cpu_ratio.hybrid.upper_95, 2);
    assert.equal(mode.incremental_cpu_ns.v1.median, 500);
    assert.equal(mode.incremental_cpu_ns.v1.upper_95, 500);
    assert.equal(mode.incremental_cpu_ns.v2.median, 550);
    assert.equal(mode.incremental_cpu_ns.hybrid.median, 1000);
    assert.equal(mode.incremental_cpu_ns.hybrid.upper_95, 1000);
    assert.equal(mode.incremental_cpu_ns.v1.status, 'COMPLETE');
    assert.equal(mode.hybrid_over_v1_incremental_ratio.median, 2);
    assert.equal(mode.hybrid_over_v1_incremental_ratio.upper_95, 2);
    assert.equal(mode.hybrid_over_v1_incremental_ratio.status, 'COMPLETE');
  }
});

test('missing baseline row and zero v1 incremental denominator yield two explicit incomplete cases and nonzero exit', () => {
  const rows = makeRows(15).filter(row => !(row.fork === 3 && row.arm === 'baseline'));
  for (const row of rows) {
    if (row.fork === 5 && row.arm.endsWith('v1')) {
      row.target_cpu_ns = 900;
      row.collector_cpu_ns = 100;
    }
  }
  const run = runEstimator(rows);
  assert.notEqual(run.status, 0);
  const reasons = run.decision.incomplete.map(entry => `${entry.fork}:${entry.reason}`);
  assert.ok(reasons.includes('3:missing_arm'), JSON.stringify(run.decision.incomplete));
  assert.ok(reasons.includes('5:nonpositive_v1_denominator'), JSON.stringify(run.decision.incomplete));
  assert.equal(run.decision.metrics_status, 'INCONCLUSIVE');
  assert.equal(run.decision.modes['full-stream'].hybrid_over_v1_incremental_ratio.status, 'INCONCLUSIVE');
  assert.ok(run.decision.modes['full-stream'].hybrid_over_v1_incremental_ratio.upper_95 === null);
});

test('a fully missing fork is reported as missing_fork with nonzero exit', () => {
  const rows = makeRows(15).filter(row => row.fork !== 15);
  const run = runEstimator(rows);
  assert.notEqual(run.status, 0);
  assert.ok(run.decision.incomplete.some(entry => entry.fork === 15 && entry.reason === 'missing_fork'));
  assert.equal(run.decision.metrics_status, 'INCONCLUSIVE');
});

test('seed drift is rejected with a distinct reason and nonzero exit', () => {
  const rows = makeRows(15);
  rows.find(row => row.fork === 7 && row.arm === 'baseline').seed = 999;
  const run = runEstimator(rows);
  assert.notEqual(run.status, 0);
  assert.ok(run.decision.incomplete.some(entry => entry.fork === 7 && entry.reason === 'seed_drift'));
  assert.equal(run.decision.metrics_status, 'INCONCLUSIVE');
});

test('baseline source/image identity mismatch is rejected with a distinct reason', () => {
  const rows = makeRows(15);
  rows.find(row => row.fork === 4 && row.arm === 'baseline').image_id = 'sha256:deadbeef';
  const run = runEstimator(rows);
  assert.notEqual(run.status, 0);
  assert.ok(run.decision.incomplete.some(entry => entry.fork === 4 && entry.reason === 'source_identity_mismatch'));
  assert.equal(run.decision.metrics_status, 'INCONCLUSIVE');
});

test('changed CPU sample boundary is rejected with a distinct reason', () => {
  const rows = makeRows(15);
  rows.find(row => row.fork === 6 && row.arm.endsWith('hybrid')).collector_sample_boundary = 'after_workload_before_exit';
  const run = runEstimator(rows);
  assert.notEqual(run.status, 0);
  assert.ok(run.decision.incomplete.some(entry => entry.fork === 6 && entry.reason === 'boundary_changed'));
  assert.equal(run.decision.metrics_status, 'INCONCLUSIVE');
});

test('failed coverage is rejected with a distinct reason', () => {
  const rows = makeRows(15);
  rows.find(row => row.fork === 8 && row.arm.endsWith('v2')).coverage_verdict = 'FAILED';
  const run = runEstimator(rows);
  assert.notEqual(run.status, 0);
  assert.ok(run.decision.incomplete.some(entry => entry.fork === 8 && entry.reason === 'coverage_failed'));
});

test('incomplete terminal evidence is rejected with a distinct reason', () => {
  const rows = makeRows(15);
  rows.find(row => row.fork === 9 && row.arm.endsWith('v1')).terminal_integrity = 'INCOMPLETE';
  const run = runEstimator(rows);
  assert.notEqual(run.status, 0);
  assert.ok(run.decision.incomplete.some(entry => entry.fork === 9 && entry.reason === 'terminal_incomplete'));
});

test('NaN CPU input is rejected with cpu_not_finite and nonzero exit', () => {
  const rows = makeRows(15);
  rows.find(row => row.fork === 10 && row.arm.endsWith('hybrid')).target_cpu_ns = 'NaN';
  const run = runEstimator(rows);
  assert.notEqual(run.status, 0);
  assert.ok(run.decision.incomplete.some(entry => entry.fork === 10 && entry.reason === 'cpu_not_finite'));
  assert.equal(run.decision.metrics_status, 'INCONCLUSIVE');
});

test('declared operation drift is rejected with operations_mismatch', () => {
  const rows = makeRows(15);
  rows.find(row => row.fork === 11 && row.arm === 'baseline').declared_operations = 72;
  const run = runEstimator(rows);
  assert.notEqual(run.status, 0);
  assert.ok(run.decision.incomplete.some(entry => entry.fork === 11 && entry.reason === 'operations_mismatch'));
});

test('a five-fork pilot cannot claim a decision-quality 95% bound', () => {
  const run = runEstimator(makeRows(5));
  assert.notEqual(run.status, 0);
  const mode = run.decision.modes['full-stream'];
  assert.equal(mode.combined_cpu_ratio.v1.upper_95, null);
  assert.equal(mode.combined_cpu_ratio.v1.status, 'INCONCLUSIVE');
  assert.equal(mode.combined_cpu_ratio.v1.reasons.includes('insufficient_pairs') ||
    run.decision.incomplete.some(entry => entry.reason === 'missing_fork'), true);
  assert.equal(run.decision.metrics_status, 'INCONCLUSIVE');
});

test('paired CPU metrics are separated per emission mode', () => {
  const rows = makeRows(15).map(row => {
    if (row.emission === 'unique-edge') {
      return {
        ...row,
        target_cpu_ns: row.target_cpu_ns + 500,
        collector_cpu_ns: row.collector_cpu_ns === null ? null : row.collector_cpu_ns + 50,
      };
    }
    return row;
  });
  const run = runEstimator(rows);
  assert.equal(run.status, 0, run.stderr);
  const fullStream = run.decision.modes['full-stream'];
  const uniqueEdge = run.decision.modes['unique-edge'];
  assert.equal(fullStream.combined_cpu_ratio.v1.median, 1.5);
  assert.equal(fullStream.incremental_cpu_ns.v1.median, 500);
  assert.equal(fullStream.combined_cpu_ratio.hybrid.median, 2);
  assert.equal(fullStream.incremental_cpu_ns.hybrid.median, 1000);
  assert.equal(fullStream.hybrid_over_v1_incremental_ratio.median, 2);
  assert.equal(uniqueEdge.combined_cpu_ratio.v1.median, 2.05);
  assert.equal(uniqueEdge.incremental_cpu_ns.v1.median, 1050);
  assert.equal(uniqueEdge.combined_cpu_ratio.hybrid.median, 2.55);
  assert.equal(uniqueEdge.incremental_cpu_ns.hybrid.median, 1550);
  assert.equal(uniqueEdge.hybrid_over_v1_incremental_ratio.median, 1550 / 1050);
  assert.notEqual(fullStream.combined_cpu_ratio.v1.median, uniqueEdge.combined_cpu_ratio.v1.median);
});
