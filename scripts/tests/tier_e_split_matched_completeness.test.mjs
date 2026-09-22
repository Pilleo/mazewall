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
