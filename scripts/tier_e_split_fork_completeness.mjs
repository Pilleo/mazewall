#!/usr/bin/env node
// Strict completeness for split-topology matched forks.
// A zero child exit alone cannot mark a benchmark complete.

export function forkCompleteness(row) {
  if (row.status !== 0) return { complete: false, reason: 'child_failed' };
  if (row.measured_batch_count !== 10) return { complete: false, reason: 'missing_batches' };
  if (row.measured_operations !== 2000) return { complete: false, reason: 'missing_operations' };
  if (row.target_cpu_ns == null || row.target_peak_rss_kb == null) {
    return { complete: false, reason: 'missing_target_metrics' };
  }
  const tierE = row.mode.startsWith('dictionary') || row.mode.startsWith('inline');
  if (tierE) {
    if (row.terminal_integrity !== 'COMPLETE') return { complete: false, reason: 'terminal_incomplete' };
    if (row.collector_cpu_ns == null || row.collector_peak_rss_kb == null) {
      return { complete: false, reason: 'missing_collector_metrics' };
    }
    if (row.mode.endsWith('full-stream')) {
      if (!row.operation_verdict || row.operation_verdict.complete !== true) {
        return { complete: false, reason: 'operation_verdict_incomplete' };
      }
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
