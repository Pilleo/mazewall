#!/usr/bin/env node
// Strict completeness for split-topology matched forks.
// A zero child exit alone cannot mark a benchmark complete.
// Expected counts come from the run manifest/oracle; the legacy 10x200 default
// keeps older unit fixtures meaningful.
export function forkCompleteness(row, expected = {}) {
  const expectedBatches = expected.batches ?? 10;
  const expectedOperations = expected.operations ?? 2000;
  if (row.status !== 0) return { complete: false, reason: 'child_failed' };
  if (row.timed_out) return { complete: false, reason: 'timeout' };
  if (row.measured_batch_count !== expectedBatches) return { complete: false, reason: 'missing_batches' };
  if (expectedOperations != null && row.measured_operations !== expectedOperations) {
    return { complete: false, reason: 'missing_operations' };
  }
  if (row.target_cpu_ns == null || row.target_peak_rss_kb == null) {
    return { complete: false, reason: 'missing_target_metrics' };
  }
  const tierE = row.mode.startsWith('dictionary') || row.mode.startsWith('inline');
  if (tierE) {
    if (row.terminal_integrity !== 'COMPLETE') return { complete: false, reason: 'terminal_incomplete' };
    if (row.collector_cpu_ns == null || row.collector_peak_rss_kb == null) {
      return { complete: false, reason: 'missing_collector_metrics' };
    }
    if (row.instrumented && row.native_phases_available === false) {
      return { complete: false, reason: 'missing_native_phases' };
    }
    if (row.instrumented && row.collector_receipt_available === false) {
      return { complete: false, reason: 'missing_collector_receipt' };
    }
    const coverage = row.operation_coverage_verdict ?? (row.operation_verdict?.complete === true ? 'PASS' : null);
    if (row.mode.replace(/-(v1|v2|hybrid)$/, '').endsWith('full-stream')) {
      if (coverage !== 'PASS') return { complete: false, reason: 'operation_verdict_incomplete' };
    } else if (row.representative_only === true) {
      if (row.event_history_verdict !== 'NOT_APPLICABLE' || row.unresolved_observations !== 0 ||
          !(row.recovered_operation_ids > 0)) return { complete: false, reason: 'representative_verdict_incomplete' };
    } else if (coverage !== 'PASS') {
      return { complete: false, reason: 'operation_verdict_incomplete' };
    }
  }
  if (row.mode.startsWith('user-notif')) {
    const o = row.user_notif_oracle;
    if (!o || o.expected !== o.matched || o.wrong !== 0 || o.duplicates !== 0 || o.unclassified !== 0) {
      return { complete: false, reason: 'user_notif_mismatch' };
    }
  }
  return { complete: true, reason: 'complete' };
}
