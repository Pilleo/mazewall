import test from 'node:test';
import assert from 'node:assert/strict';
import { forkCompleteness } from '../tier_e_split_fork_completeness.mjs';

const base = {
  status: 0,
  measured_batch_count: 10,
  measured_operations: 2000,
  target_cpu_ns: 1000,
  target_peak_rss_kb: 80000,
  mode: 'dictionary-full-stream',
  terminal_integrity: 'COMPLETE',
  collector_cpu_ns: 2000,
  collector_peak_rss_kb: 300000,
  operation_verdict: { complete: true },
  user_notif_oracle: null,
};

test('complete Tier E fork passes', () => {
  assert.deepEqual(forkCompleteness(base), { complete: true, reason: 'complete' });
});

test('zero exit with missing batches is incomplete', () => {
  assert.equal(forkCompleteness({ ...base, measured_batch_count: 9 }).complete, false);
  assert.equal(forkCompleteness({ ...base, measured_batch_count: 9 }).reason, 'missing_batches');
});

test('missing collector metrics is incomplete', () => {
  const r = forkCompleteness({ ...base, collector_cpu_ns: null });
  assert.equal(r.complete, false);
  assert.equal(r.reason, 'missing_collector_metrics');
});

test('failed operation verdict is incomplete for full-stream', () => {
  const r = forkCompleteness({ ...base, operation_verdict: { complete: false } });
  assert.equal(r.complete, false);
});

test('baseline does not require terminal integrity', () => {
  const row = { ...base, mode: 'baseline', terminal_integrity: null, collector_cpu_ns: null, collector_peak_rss_kb: null, operation_verdict: null };
  assert.equal(forkCompleteness(row).complete, true);
});

test('timeout is incomplete even with zero exit', () => {
  const r = forkCompleteness({ ...base, status: 0, timed_out: true });
  assert.equal(r.complete, false);
  assert.equal(r.reason, 'timeout');
});

test('expected counts come from the manifest, not hard-coded values', () => {
  const row = { ...base, measured_batch_count: 8, measured_operations: 800 };
  assert.deepEqual(forkCompleteness(row, { batches: 8, operations: 800 }), { complete: true, reason: 'complete' });
  assert.equal(forkCompleteness(row).reason, 'missing_batches');
});

test('instrumented forks require native phases and collector receipt', () => {
  const row = {
    ...base,
    instrumented: true,
    native_phases_available: false,
    collector_receipt_available: true,
    operation_coverage_verdict: 'PASS',
    operation_verdict: null,
  };
  assert.equal(forkCompleteness(row).reason, 'missing_native_phases');
  assert.equal(
    forkCompleteness({ ...row, native_phases_available: true, collector_receipt_available: false }).reason,
    'missing_collector_receipt',
  );
  assert.deepEqual(
    forkCompleteness({ ...row, native_phases_available: true, collector_receipt_available: true }),
    { complete: true, reason: 'complete' },
  );
});

test('actual operation verdict schema gates full-stream and unique-edge', () => {
  const full = { ...base, operation_verdict: null, operation_coverage_verdict: 'PASS' };
  assert.deepEqual(forkCompleteness(full), { complete: true, reason: 'complete' });
  assert.equal(
    forkCompleteness({ ...full, operation_coverage_verdict: 'INCOMPLETE' }).reason,
    'operation_verdict_incomplete',
  );
  const unique = { ...full, mode: 'dictionary-unique-edge', operation_coverage_verdict: 'INCOMPLETE' };
  assert.equal(forkCompleteness(unique).reason, 'operation_verdict_incomplete');
});

test('unique-edge representative-only operation verdict is not full history', () => {
  const row = { ...base, mode: 'dictionary-unique-edge-v2', operation_coverage_verdict: 'INCOMPLETE',
    representative_only: true, event_history_verdict: 'NOT_APPLICABLE', unresolved_observations: 0,
    recovered_operation_ids: 1 };
  assert.deepEqual(forkCompleteness(row), { complete: true, reason: 'complete' });
  assert.equal(forkCompleteness({ ...row, unresolved_observations: 1 }).reason, 'representative_verdict_incomplete');
});
