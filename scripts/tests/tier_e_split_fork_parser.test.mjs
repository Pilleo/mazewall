import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { parseForkRow } from '../summarize_tier_e_split_fork.mjs';

function writeDir(files) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tier-e-fork-'));
  for (const [name, content] of Object.entries(files)) {
    fs.writeFileSync(path.join(dir, name), content);
  }
  return dir;
}

const batchLines = Array.from(
  { length: 10 },
  (_, i) => `tier-e-shared-openat-oracle-batch: phase=measured index=${i + 1} operations=200 checksum=1 verified=true workloadNs=${1000000 + i}`,
).join('\n');

test('parseForkRow extracts workload, target, native, receipt, and verdicts', () => {
  const dir = writeDir({
    'target.log': `${batchLines}\n` +
      'split-benchmark-target: mode=dictionary-full-stream elapsedNs=7000000 cpuNs=6000000 peakRssKb=79804\n' +
      'tier-e-benchmark: stack_transport=dictionary serialization_ns=100 publication_bytes=200 publication_latency_ns=300 publication_cpu_ns=40 peak_retained_bytes=500 native_retained_bytes=600 native_retention_exhaustions=0 publication_count=1 coalesced_publications=0 failed_publications=0\n',
    'collector.log': 'tier-e-collector-metrics: polls=10 active_polls=5 idle_polls=5 receipt_samples=100 receipt_min_ns=1 receipt_mean_ns=2 receipt_max_ns=3 receipt_p50_upper_ns=2 receipt_p95_upper_ns=3 receipt_p99_upper_ns=3\n',
    'terminal-report.json': JSON.stringify({ evidence_integrity: 'COMPLETE' }),
    'collector-process-metrics.json': JSON.stringify({ cpu_ns: 2000, peak_rss_kb: 300000 }),
    'operation-verdict.json': JSON.stringify({ operation_coverage_verdict: 'PASS', event_history_verdict: 'PASS' }),
    'benchmark-oracle.json': JSON.stringify({ expected_operations: 2000 }),
  });
  try {
    const row = parseForkRow({ fork: 0, mode: 'dictionary-full-stream', status: 0, dir });
    assert.equal(row.measured_batch_count, 10);
    assert.equal(row.measured_operations, 2000);
    assert.equal(row.target_cpu_ns, 6000000);
    assert.equal(row.native_phases_available, true);
    assert.equal(row.native_phases.publication_cpu_ns, 40);
    assert.equal(row.collector_receipt_available, true);
    assert.equal(row.collector_receipt.receipt_p99_upper_ns, 3);
    assert.equal(row.operation_coverage_verdict, 'PASS');
    assert.equal(row.fork_complete, true);
  } finally {
    fs.rmSync(dir, { recursive: true });
  }
});

test('parseForkRow marks missing native phases and receipt on instrumented rows', () => {
  const dir = writeDir({
    'target.log': `${batchLines}\nsplit-benchmark-target: mode=dictionary-full-stream elapsedNs=1 cpuNs=2 peakRssKb=3\n`,
    'collector.log': 'collector running\n',
    'terminal-report.json': JSON.stringify({ evidence_integrity: 'COMPLETE' }),
    'collector-process-metrics.json': JSON.stringify({ cpu_ns: 2, peak_rss_kb: 3 }),
    'operation-verdict.json': JSON.stringify({ operation_coverage_verdict: 'PASS', event_history_verdict: 'PASS' }),
    'benchmark-oracle.json': JSON.stringify({ expected_operations: 2000 }),
  });
  try {
    const row = parseForkRow({ fork: 0, mode: 'dictionary-full-stream', status: 0, dir });
    assert.equal(row.native_phases_available, false);
    assert.equal(row.collector_receipt_available, false);
    // Uninstrumented rows stay complete without phase metrics.
    assert.equal(row.fork_complete, true);
    assert.equal(row.incomplete_reason, 'complete');
  } finally {
    fs.rmSync(dir, { recursive: true });
  }
});

test('parseForkRow rejects malformed artifacts instead of crashing', () => {
  const dir = writeDir({ 'target.log': 'garbage\n' });
  try {
    const row = parseForkRow({ fork: 0, mode: 'baseline', status: 0, dir });
    assert.equal(row.fork_complete, false);
    assert.equal(row.incomplete_reason, 'missing_batches');
  } finally {
    fs.rmSync(dir, { recursive: true });
  }
});
