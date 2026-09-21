const VARIANTS = ['dictionary_full_stream', 'dictionary_unique_edge', 'inline_full_stream', 'inline_unique_edge'];

export function parseCoverageJsonl(text) {
  const lines = text.split('\n');
  if (lines.at(-1) === '') lines.pop();
  const rows = [];
  lines.forEach((line, index) => {
    try {
      rows.push(JSON.parse(line));
    } catch (error) {
      if (index !== lines.length - 1 || text.endsWith('\n')) throw error;
      rows.push({kind: 'failure', reason: 'interrupted_jsonl_tail', line: index + 1, derived_by_summary: true});
      rows.push({kind: 'terminal', success: false, reason: 'interrupted_jsonl_tail', derived_by_summary: true});
    }
  });
  return rows;
}

function measurements(output) {
  return Object.fromEntries(['workload_total_ns', 'elapsed_ns', 'cpu_ns', 'peak_rss_bytes',
    'missing_operations', 'matched_operations', 'expected_operations', 'wrong_operations',
    'unresolved_operations', 'acknowledged_operations', 'suppressed_operations', 'lost', 'dropped']
    .map(key => [key, output?.[key] ?? null]));
}

function pairedResources(outputs, coverage, forks) {
  return Object.fromEntries(['cpu_ns', 'peak_rss_bytes', 'elapsed_ns'].map(metric => {
    const pairs = forks.flatMap(fork => {
      const full = outputs.find(row => row.variant === 'dictionary_full_stream' && row.fork === fork);
      const unique = outputs.find(row => row.variant === 'dictionary_unique_edge' && row.fork === fork);
      if (![full, unique].every(row => row?.whole_session_evidence === 'COMPLETE' &&
        Number.isFinite(row[metric]) && row[metric] > 0 &&
        coverage.some(item => item.variant === row.variant && item.fork === fork && item.report.coverage_verdict === 'PASS'))) return [];
      return [{fork, full_stream: full[metric], unique_edge: unique[metric],
        change_percent: (unique[metric] / full[metric] - 1) * 100}];
    });
    const average = values => values.reduce((a, b) => a + b, 0) / values.length;
    let interval = null;
    if (pairs.length >= 5) {
      let state = 0x54494552;
      const bootstrap = Array.from({length: 10000}, () => average(pairs.map(() => {
        state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
        return pairs[Math.floor(state / 4294967296 * pairs.length)].change_percent;
      }))).sort((a, b) => a - b);
      interval = [bootstrap[249], bootstrap[9749]];
    }
    return [metric, {pairs, mean_change_percent: pairs.length ? average(pairs.map(row => row.change_percent)) : null,
      confidence_interval_95_percent: interval,
      method: 'paired unique/full percent change; deterministic 10000-resample percentile bootstrap with at least five complete pairs'}];
  }));
}

/** Missing output is unavailable, never a successful zero-valued measurement. */
export function buildCoverageSummary(rows) {
  const metadata = rows.find(row => row.kind === 'metadata');
  const coverage = rows.filter(row => row.kind === 'coverage');
  const outputs = rows.filter(row => row.kind === 'fork-output');
  const forkCount = metadata?.forks ?? Math.max(0, ...rows.map(row => row.fork ?? 0));
  const forks = Array.from({length: forkCount}, (_, index) => index + 1);
  return {
    policy_eligibility: false,
    run_completed: rows.findLast(row => row.kind === 'terminal')?.success === true,
    terminal_records: rows.filter(row => row.kind === 'terminal'),
    failures: rows.filter(row => row.kind === 'failure' || row.kind === 'fork-failure'),
    source_revision: metadata?.source_revision ?? null,
    metadata,
    measurement_contract: {
      corpus: '72 declared Java call paths to openat; not all application behavior',
      workload_time: 'paced at 1250 operations/s; not an unpaced application slowdown estimate',
      cpu: 'benchmark child process only: workload JVM for baseline, collector JVM for Tier E; excludes Tier E workload child CPU',
      rss: 'benchmark child process high-water RSS; not the shared retained-state budget or combined process-tree RSS',
      native_memory: 'phases.native_retained_bytes and peak_retained_bytes are native accounting, not collector heap',
      discovery_time: 'post-retention collector/report observation since gate; terminal fallback is an upper bound, not syscall entry time',
      closure: 'runner completion does not imply a passing coverage or whole-session verdict',
    },
    baseline: forks.map(fork => {
      const output = outputs.find(row => row.variant === 'baseline' && row.fork === fork);
      return {fork, coverage_verdict: 'NOT_APPLICABLE',
        workload_correctness: output?.workload_correctness ?? 'UNVERIFIED', ...measurements(output)};
    }),
    paired_dictionary_resources: pairedResources(outputs, coverage, forks),
    variants: VARIANTS.map(variant => {
      const results = forks.map(fork => {
        const row = coverage.find(item => item.variant === variant && item.fork === fork);
        const output = outputs.find(item => item.variant === variant && item.fork === fork);
        const publicationFailures = [...new Set([...(output?.output ?? '')
          .matchAll(/definition_publication_failure=([a-z_]+)/g)].map(match => match[1]))];
        if (/definition publication exhausted retained-state budget/.test(output?.output ?? '')) {
          publicationFailures.push('retained_state_budget_exhausted');
        }
        const complete = output?.whole_session_evidence === 'COMPLETE';
        const affinity = /tier-e-coverage-affinity: workload=(\S+) collector=(\S+) launched_pinned=true/
          .exec(output?.output ?? '');
        return {
          fork, seed: row?.seed ?? fork,
          ...(row?.report ?? {coverage_verdict: 'UNVERIFIED', expected: Array.from({length: 72}, (_, i) => i),
            recovered: [], missing: Array.from({length: 72}, (_, i) => i), discoveries: []}),
          event_workload_correctness: output?.workload_correctness ?? 'UNVERIFIED',
          whole_session_evidence: output?.whole_session_evidence ?? 'UNVERIFIED',
          ...measurements(output), phases: output?.phases ?? null,
          publication_failures: publicationFailures,
          effective_affinity: affinity ? {workload: affinity[1], collector: affinity[2], workload_launched_pinned: true} : null,
          dimensions: {
            capture_domain_validity: output?.operation_oracle_available ? 'DECLARED_OPENAT_ONLY' : 'UNVERIFIED',
            transport_completeness: complete ? 'COMPLETE' : output ? 'INCOMPLETE' : 'UNVERIFIED',
            attribution_coverage: row?.report.coverage_verdict ?? 'UNVERIFIED',
            dictionary_integrity: complete ? 'COMPLETE' : publicationFailures.length ? 'INCOMPLETE' : 'UNVERIFIED',
            lifecycle_validity: complete ? 'COMPLETE' : 'UNVERIFIED',
            runtime_overhead: 'NOT_GATED',
          },
        };
      });
      const recovered = [...new Set(results.flatMap(row => row.recovered))].sort((a, b) => a - b);
      return {variant, forks: results, cumulative_recovered: recovered, cumulative_recall: recovered.length / 72,
        all_forks_recover_all_paths: results.length > 0 && results.every(row => row.coverage_verdict === 'PASS')};
    }),
    performance_gate: 'not_measured_against_comparable_baseline',
  };
}
