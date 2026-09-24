#!/usr/bin/env node
// Strict paired parser and profiler-only CPU estimator for the seven-arm
// long matched controls. Diagnostic only: it never replaces the existing
// combined-CPU 1.25 or receipt-p99 gates, never marks anything
// policy-eligible, and cannot produce a 95% bound from fewer than 15 pairs.
import fs from 'node:fs';

const EXPECTED_FORKS = 15;
const EXPECTED_SEED_BASE = 23063;
const EXPECTED_OPERATIONS = 75000;
const TARGET_BOUNDARY = 'after_workload_before_exit';
const COLLECTOR_BOUNDARY = 'after_terminal_report_before_shutdown';
const EMISSIONS = ['full-stream', 'unique-edge'];
const VARIANTS = ['v1', 'v2', 'hybrid'];
const PROFILED_ARMS = EMISSIONS.flatMap(emission => VARIANTS.map(variant => `dictionary-${emission}-${variant}`));
const EXPECTED_ARMS = ['baseline', ...PROFILED_ARMS];
const IDENTITY_FIELDS = ['source_digest', 'image_id', 'native_so_sha256', 'daemon_jars_sha256'];

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
  if (values.length !== EXPECTED_FORKS || values.some(value => !Number.isFinite(value))) return null;
  const draws = Array.from({ length: 10000 }, () =>
    median(Array.from({ length: values.length }, () => values[Math.floor(random() * values.length)])));
  draws.sort((a, b) => a - b);
  return draws[Math.floor(0.975 * (draws.length - 1))];
};

const cpuValue = value => {
  if (value === null || value === undefined || value === '') return { error: 'cpu_missing' };
  const numeric = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(numeric)) return { error: 'cpu_not_finite' };
  if (numeric < 0) return { error: 'negative_cpu' };
  return { value: numeric };
};

function armCpuIssues(row, incomplete, fork) {
  const profiled = row.arm !== 'baseline';
  const target = cpuValue(row.target_cpu_ns);
  if (target.error) incomplete.push({ fork, arm: row.arm, reason: target.error });
  if (row.target_sample_boundary !== TARGET_BOUNDARY) {
    incomplete.push({ fork, arm: row.arm, reason: 'boundary_changed' });
  }
  if (profiled) {
    const collector = cpuValue(row.collector_cpu_ns);
    if (collector.error) incomplete.push({ fork, arm: row.arm, reason: collector.error });
    if (row.collector_sample_boundary !== COLLECTOR_BOUNDARY) {
      incomplete.push({ fork, arm: row.arm, reason: 'boundary_changed' });
    }
    if (row.coverage_verdict !== 'PASS' || row.covered_paths !== 72) {
      incomplete.push({ fork, arm: row.arm, reason: 'coverage_failed' });
    }
    if (row.terminal_integrity !== 'COMPLETE') {
      incomplete.push({ fork, arm: row.arm, reason: 'terminal_incomplete' });
    }
  } else {
    if (row.collector_cpu_ns !== null || row.collector_sample_boundary !== null || row.terminal_integrity !== null) {
      incomplete.push({ fork, arm: row.arm, reason: 'baseline_collector_present' });
    }
    if (row.coverage_verdict !== 'NOT_APPLICABLE') {
      incomplete.push({ fork, arm: row.arm, reason: 'coverage_failed' });
    }
  }
  return target;
}

function validateFork(fork, rows, incomplete) {
  const byArm = new Map();
  for (const row of rows) {
    if (byArm.has(row.arm)) {
      incomplete.push({ fork, arm: row.arm, reason: 'duplicate_arm' });
      return false;
    }
    byArm.set(row.arm, row);
  }
  let valid = true;
  for (const row of rows) {
    if (!EXPECTED_ARMS.includes(row.arm)) {
      incomplete.push({ fork, arm: row.arm, reason: 'unexpected_arm' });
      valid = false;
    }
  }
  for (const arm of EXPECTED_ARMS) {
    if (!byArm.has(arm)) {
      incomplete.push({ fork, arm, reason: 'missing_arm' });
      valid = false;
    }
  }
  if (!valid) return false;

  const arms = EXPECTED_ARMS.map(arm => byArm.get(arm));
  const expectedSeed = EXPECTED_SEED_BASE + fork - 1;
  if (arms.some(row => Number(row.seed) !== expectedSeed)) {
    incomplete.push({ fork, reason: 'seed_drift' });
    valid = false;
  }
  for (const field of IDENTITY_FIELDS) {
    if (arms.some(row => typeof row[field] !== 'string' || !row[field].trim())) {
      incomplete.push({ fork, reason: 'source_identity_missing', field });
      valid = false;
      continue;
    }
    const values = new Set(arms.map(row => row[field]));
    if (values.size !== 1) {
      incomplete.push({ fork, reason: 'source_identity_mismatch', field });
      valid = false;
      break;
    }
  }
  if (arms.some(row => Number(row.declared_operations) !== EXPECTED_OPERATIONS)) {
    incomplete.push({ fork, reason: 'operations_mismatch' });
    valid = false;
  }
  if (arms.some(row => Number(row.status) !== 0)) {
    incomplete.push({ fork, reason: 'status_failed' });
    valid = false;
  }
  for (const row of arms) {
    if (row.timed_out !== false) {
      incomplete.push({ fork, arm: row.arm, reason: 'timed_out' });
      valid = false;
    }
    if (!Array.isArray(row.artifact_errors) || row.artifact_errors.length > 0) {
      incomplete.push({ fork, arm: row.arm, reason: 'artifact_errors' });
      valid = false;
    }
  }
  if (arms.some(row => row.policy_eligibility !== false)) {
    incomplete.push({ fork, reason: 'policy_eligibility_violated' });
    valid = false;
  }

  const cpuByArm = new Map();
  for (const row of arms) {
    const before = incomplete.length;
    cpuByArm.set(row.arm, armCpuIssues(row, incomplete, fork));
    if (incomplete.length !== before) valid = false;
  }
  const baselineCpu = cpuByArm.get('baseline');
  if (baselineCpu?.value !== undefined && baselineCpu.value <= 0) {
    incomplete.push({ fork, arm: 'baseline', reason: 'nonpositive_baseline_target' });
    valid = false;
  }
  if (!valid) return false;

  const combined = {};
  for (const emission of EMISSIONS) {
    combined[emission] = {};
    for (const variant of VARIANTS) {
      const row = byArm.get(`dictionary-${emission}-${variant}`);
      combined[emission][variant] = cpuByArm.get(row.arm).value + cpuValue(row.collector_cpu_ns).value;
    }
  }
  return { baseline: baselineCpu.value, combined };
}

function metric(values, explicitReasons = []) {
  const reasons = [...explicitReasons];
  if (values.length < EXPECTED_FORKS && !reasons.includes('nonpositive_v1_denominator')) {
    reasons.push('insufficient_pairs');
  }
  if (values.length === 0 && reasons.length === 0) reasons.push('no_pairs');
  const status = reasons.length === 0 && values.length === EXPECTED_FORKS ? 'COMPLETE' : 'INCONCLUSIVE';
  return {
    pairs: values.length,
    median: values.length ? median(values) : null,
    upper_95: status === 'COMPLETE' ? upper95(values) : null,
    status,
    reasons,
  };
}

export function summarizeLongMatched(rows) {
  state = 0x4d5a2026;
  const incomplete = [];
  for (const field of IDENTITY_FIELDS) {
    const values = new Set(rows.map(row => row[field]));
    if (values.size > 1) incomplete.push({ reason: 'source_identity_mismatch', field });
  }
  const byFork = new Map();
  for (const row of rows) {
    const fork = Number(row.fork);
    if (!Number.isInteger(fork) || fork < 1 || fork > EXPECTED_FORKS) {
      incomplete.push({ fork: row.fork, arm: row.arm, reason: 'unexpected_fork' });
      continue;
    }
    if (!byFork.has(fork)) byFork.set(fork, []);
    byFork.get(fork).push(row);
  }
  const cpuByFork = new Map();
  const validForks = [];
  for (let fork = 1; fork <= EXPECTED_FORKS; fork++) {
    const forkRows = byFork.get(fork);
    if (!forkRows) {
      incomplete.push({ fork, reason: 'missing_fork' });
      continue;
    }
    const cpu = validateFork(fork, forkRows, incomplete);
    if (cpu) {
      cpuByFork.set(fork, cpu);
      validForks.push(fork);
    }
  }

  const modes = {};
  for (const emission of EMISSIONS) {
    const combined = { v1: [], v2: [], hybrid: [] };
    const incremental = { v1: [], v2: [], hybrid: [] };
    const hybridOverV1 = [];
    const v1DenominatorReasons = [];
    for (const fork of validForks) {
      const { baseline, combined: forkCombined } = cpuByFork.get(fork);
      for (const variant of VARIANTS) {
        combined[variant].push(forkCombined[emission][variant] / baseline);
        incremental[variant].push(forkCombined[emission][variant] - baseline);
      }
      const v1Incremental = forkCombined[emission].v1 - baseline;
      const hybridIncremental = forkCombined[emission].hybrid - baseline;
      if (v1Incremental > 0) {
        hybridOverV1.push(hybridIncremental / v1Incremental);
      } else if (!v1DenominatorReasons.includes('nonpositive_v1_denominator')) {
        v1DenominatorReasons.push('nonpositive_v1_denominator');
        incomplete.push({ fork, arm: `dictionary-${emission}-v1`, reason: 'nonpositive_v1_denominator' });
      }
    }
    modes[emission] = {
      pairs: {
        baseline: validForks.length,
        v1: validForks.length,
        v2: validForks.length,
        hybrid: validForks.length,
      },
      complete_forks: [...validForks],
      combined_cpu_ratio: {
        v1: metric(combined.v1),
        v2: metric(combined.v2),
        hybrid: metric(combined.hybrid),
      },
      incremental_cpu_ns: {
        v1: metric(incremental.v1),
        v2: metric(incremental.v2),
        hybrid: metric(incremental.hybrid),
      },
      hybrid_over_v1_incremental_ratio: metric(hybridOverV1, v1DenominatorReasons),
    };
  }

  if (incomplete.length > 0) {
    for (const mode of Object.values(modes)) {
      for (const group of [mode.combined_cpu_ratio, mode.incremental_cpu_ns]) {
        for (const value of Object.values(group)) {
          value.status = 'INCONCLUSIVE';
          value.upper_95 = null;
          if (!value.reasons.includes('invalid_run')) value.reasons.push('invalid_run');
        }
      }
      const value = mode.hybrid_over_v1_incremental_ratio;
      value.status = 'INCONCLUSIVE';
      value.upper_95 = null;
      if (!value.reasons.includes('invalid_run')) value.reasons.push('invalid_run');
    }
  }

  const metricStatuses = [];
  for (const mode of Object.values(modes)) {
    metricStatuses.push(mode.combined_cpu_ratio.v1.status, mode.combined_cpu_ratio.v2.status,
      mode.combined_cpu_ratio.hybrid.status, mode.incremental_cpu_ns.v1.status,
      mode.incremental_cpu_ns.v2.status, mode.incremental_cpu_ns.hybrid.status,
      mode.hybrid_over_v1_incremental_ratio.status);
  }
  const metricsStatus = incomplete.length === 0 && metricStatuses.every(status => status === 'COMPLETE')
    ? 'COMPLETE'
    : 'INCONCLUSIVE';
  const distinctReasons = [...new Set(incomplete.map(entry => entry.reason))].sort();
  return {
    schema_version: 1,
    bootstrap_resamples: 10000,
    bootstrap_seed: '0x4d5a2026',
    paired_unit: 'fork',
    expected_forks: EXPECTED_FORKS,
    declared_operations: EXPECTED_OPERATIONS,
    rows: rows.length,
    complete_forks: validForks.length,
    incomplete,
    incomplete_reasons: distinctReasons,
    modes,
    metrics_status: metricsStatus,
    existing_gates_note: 'diagnostic only; does not replace the combined CPU 1.25 or receipt-p99 gates',
    prior_adoption_decision: 'REJECT_DEFAULT_PROMOTION',
    policy_eligibility: false,
  };
}

function main(argv) {
  const [input, output] = argv.slice(2);
  if (!input || !output) {
    process.stderr.write('usage: summarize_tier_e_long_matched_controls.mjs fork-results.jsonl decision.json\n');
    return 2;
  }
  const rows = fs.readFileSync(input, 'utf8').trim().split('\n').filter(Boolean).map(line => JSON.parse(line));
  const decision = summarizeLongMatched(rows);
  fs.writeFileSync(output, `${JSON.stringify(decision, null, 2)}\n`);
  process.stdout.write(`${JSON.stringify({ metrics_status: decision.metrics_status, incomplete: decision.incomplete })}\n`);
  return decision.metrics_status === 'COMPLETE' ? 0 : 1;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  process.exitCode = main(process.argv);
}
