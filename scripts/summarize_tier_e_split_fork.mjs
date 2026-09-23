#!/usr/bin/env node
// Per-fork result parser for the split-topology matched benchmark.
// Single implementation shared by the runner; completeness rules live in
// tier_e_split_fork_completeness.mjs so unit tests cover the actual gate.
import fs from 'node:fs';
import { forkCompleteness } from './tier_e_split_fork_completeness.mjs';

const num = value => (value == null || value === '' ? null : Number(value));

function parseBenchmarkLine(log) {
  const line = log.split('\n').find(l => l.includes('tier-e-benchmark:'));
  if (!line) return null;
  const field = name => {
    const match = line.match(new RegExp(`${name}=(\\d+)`));
    return match ? Number(match[1]) : null;
  };
  return {
    serialization_ns: field('serialization_ns'),
    publication_bytes: field('publication_bytes'),
    publication_latency_ns: field('publication_latency_ns'),
    publication_cpu_ns: field('publication_cpu_ns'),
    peak_retained_bytes: field('peak_retained_bytes'),
    native_retained_bytes: field('native_retained_bytes'),
    native_retention_exhaustions: field('native_retention_exhaustions'),
    publication_count: field('publication_count'),
    coalesced_publications: field('coalesced_publications'),
    failed_publications: field('failed_publications'),
  };
}

function parseCollectorMetrics(log) {
  const line = log.split('\n').find(l => l.includes('tier-e-collector-metrics:'));
  if (!line) return null;
  const field = name => {
    const match = line.match(new RegExp(`${name}=(\\d+)`));
    return match ? Number(match[1]) : null;
  };
  return {
    polls: field('polls'),
    active_polls: field('active_polls'),
    idle_polls: field('idle_polls'),
    receipt_samples: field('receipt_samples'),
    receipt_min_ns: field('receipt_min_ns'),
    receipt_mean_ns: field('receipt_mean_ns'),
    receipt_max_ns: field('receipt_max_ns'),
    receipt_p50_upper_ns: field('receipt_p50_upper_ns'),
    receipt_p95_upper_ns: field('receipt_p95_upper_ns'),
    receipt_p99_upper_ns: field('receipt_p99_upper_ns'),
    receipt_p99_fine_upper_ns: field('receipt_p99_fine_upper_ns'),
  };
}

export function parseForkRow({ fork, mode, status, dir, expectedBatches = 10, expectedOperations = null }) {
  const targetLog = fs.existsSync(`${dir}/target.log`) ? fs.readFileSync(`${dir}/target.log`, 'utf8') : '';
  const collectorLog = fs.existsSync(`${dir}/collector.log`) ? fs.readFileSync(`${dir}/collector.log`, 'utf8') : '';
  const batches = [...targetLog.matchAll(/(?:tier-e-shared-openat-oracle|user-notif-oracle)-batch: phase=measured[^\n]*operations=(\d+)[^\n]*workloadNs=(\d+)/g)];
  const target = /split-benchmark-target: mode=\S+ elapsedNs=(\d+) cpuNs=(\d+) peakRssKb=(-?\d+)/.exec(targetLog);
  const userNotif = /user-notif-oracle: [^\n]*expected_operations=(\d+) matched_operations=(\d+) duplicate_operations=(\d+) wrong_operations=(\d+) unclassified_operations=(\d+)/.exec(targetLog);
  const readJson = name => {
    const path = `${dir}/${name}`;
    return fs.existsSync(path) ? JSON.parse(fs.readFileSync(path, 'utf8')) : null;
  };
  const terminal = readJson('terminal-report.json');
  const collector = readJson('collector-process-metrics.json');
  const verdict = readJson('operation-verdict.json');
  const observations = readJson('benchmark-operations.json');
  const oracle = readJson('benchmark-oracle.json');
  const instrumentedEnv = fs.existsSync(`${dir}/instrumented.env`)
    ? fs.readFileSync(`${dir}/instrumented.env`, 'utf8')
    : '';
  const instrumented = /^instrumented=1$/m.test(instrumentedEnv);
  const expected = expectedOperations ?? oracle?.expected_operations ?? null;
  const native = parseBenchmarkLine(targetLog);
  const collectorMetrics = parseCollectorMetrics(collectorLog);
  const row = {
    fork: Number(fork),
    mode,
    status: Number(status),
    instrumented,
    measured_batch_count: batches.length,
    measured_operations: batches.reduce((n, m) => n + Number(m[1]), 0),
    expected_operations: expected,
    workload_ns: batches.map(m => Number(m[2])),
    total_elapsed_ns: target ? Number(target[1]) : null,
    target_cpu_ns: target ? Number(target[2]) : null,
    target_peak_rss_kb: target ? Number(target[3]) : null,
    collector_cpu_ns: collector?.cpu_ns ?? null,
    collector_peak_rss_kb: collector?.peak_rss_kb ?? null,
    collector_metrics_available: collector != null,
    native_phases: native,
    native_phases_available: native != null,
    collector_receipt: collectorMetrics,
    collector_receipt_available: collectorMetrics != null && (collectorMetrics.receipt_samples ?? 0) > 0,
    user_notif_oracle: userNotif
      ? {
        expected: Number(userNotif[1]),
        matched: Number(userNotif[2]),
        duplicates: Number(userNotif[3]),
        wrong: Number(userNotif[4]),
        unclassified: Number(userNotif[5]),
      }
      : null,
    terminal_integrity: terminal?.evidence_integrity ?? null,
    operation_coverage_verdict: verdict?.operation_coverage_verdict ?? null,
    event_history_verdict: verdict?.event_history_verdict ?? null,
    representative_only: observations?.representative_only ?? false,
    unresolved_observations: observations?.unresolved_observations ?? null,
    recovered_operation_ids: verdict?.recovered_operation_ids ?? null,
    timed_out: fs.existsSync(`${dir}/timeout.env`),
    policy_eligibility: false,
  };
  const { complete, reason } = forkCompleteness(row, { batches: expectedBatches, operations: expected });
  row.fork_complete = complete;
  row.incomplete_reason = reason;
  return row;
}

if (process.argv[1] && process.argv[1].endsWith('summarize_tier_e_split_fork.mjs')) {
  const [fork, mode, status, dir, out] = process.argv.slice(2);
  const row = parseForkRow({
    fork,
    mode,
    status,
    dir,
    expectedBatches: Number(process.env.TIER_E_SPLIT_EXPECTED_BATCHES ?? 10),
    expectedOperations: process.env.TIER_E_SPLIT_EXPECTED_OPERATIONS ? Number(process.env.TIER_E_SPLIT_EXPECTED_OPERATIONS) : null,
  });
  fs.appendFileSync(out, `${JSON.stringify(row)}\n`);
  if (!row.fork_complete) process.exitCode = 1;
}
