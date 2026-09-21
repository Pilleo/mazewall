import test from 'node:test';
import assert from 'node:assert/strict';
import { buildCoverageSummary, parseCoverageJsonl } from '../tier_e_coverage_summary.mjs';

const variant = 'dictionary_full_stream';
const paths = Array.from({length: 72}, (_, i) => i);
const metadata = {kind: 'metadata', forks: 1, workload_cpu: '2', collector_cpu: '4'};
const coverage = {kind: 'coverage', variant, fork: 1, seed: 1,
  report: {coverage_verdict: 'PASS', recovered: paths, expected: paths, missing: [], discoveries: []}};
const output = {kind: 'fork-output', variant, fork: 1, workload_correctness: 'FAILED',
  whole_session_evidence: 'INCOMPLETE', lost: 3, missing_operations: 3,
  operation_oracle_available: true, output: '', phases: {}};

test('coverage pass never upgrades incomplete event history', () => {
  const result = buildCoverageSummary([metadata, output, coverage, {kind: 'terminal', success: true}]);
  const arm = result.variants.find(row => row.variant === variant);
  assert.equal(arm.forks[0].coverage_verdict, 'PASS');
  assert.equal(arm.forks[0].whole_session_evidence, 'INCOMPLETE');
  assert.equal(arm.forks[0].dimensions.transport_completeness, 'INCOMPLETE');
  assert.equal(result.policy_eligibility, false);
});

test('interruption and child timeout retain every scheduled arm as unverified', () => {
  const failure = {kind: 'failure', variant, fork: 1, message: 'child timeout'};
  const result = buildCoverageSummary([metadata, failure]);
  assert.equal(result.run_completed, false);
  assert.deepEqual(result.failures, [failure]);
  assert.equal(result.variants.length, 4);
  for (const arm of result.variants) {
    assert.equal(arm.forks[0].coverage_verdict, 'UNVERIFIED');
    assert.equal(arm.all_forks_recover_all_paths, false);
    assert.equal(arm.cumulative_recall, 0);
  }
});

test('publication size rejection is explicit even when legacy failure counter is zero', () => {
  const result = buildCoverageSummary([metadata, {...output,
    output: 'mazewall-stack-agent: definition_publication_failure=encoded_snapshot_exceeds_retained_limit',
    phases: {failed_publications: 0, native_retention_exhaustions: 0}}]);
  const arm = result.variants.find(row => row.variant === variant).forks[0];
  assert.deepEqual(arm.publication_failures, ['encoded_snapshot_exceeds_retained_limit']);
  assert.equal(arm.dimensions.dictionary_integrity, 'INCOMPLETE');
  assert.equal(arm.coverage_verdict, 'UNVERIFIED');
});

test('cumulative recovery does not hide incomplete individual forks', () => {
  const result = buildCoverageSummary([{...metadata, forks: 2},
    {...coverage, report: {...coverage.report, coverage_verdict: 'INCOMPLETE', recovered: paths.slice(0, 36)}},
    {...coverage, fork: 2, seed: 2, report: {...coverage.report, coverage_verdict: 'INCOMPLETE', recovered: paths.slice(36)}}]);
  const arm = result.variants.find(row => row.variant === variant);
  assert.equal(arm.cumulative_recall, 1);
  assert.equal(arm.all_forks_recover_all_paths, false);
});

test('baseline has no invented stack coverage and absent metrics remain unavailable', () => {
  const result = buildCoverageSummary([metadata, {kind: 'fork-output', variant: 'baseline', fork: 1,
    workload_correctness: 'PASS', whole_session_evidence: 'NOT_APPLICABLE', cpu_ns: 7}]);
  assert.equal(result.baseline[0].coverage_verdict, 'NOT_APPLICABLE');
  assert.equal(result.baseline[0].cpu_ns, 7);
  assert.equal(result.baseline[0].peak_rss_bytes, null);
});

test('a torn final JSONL write retains the completed prefix and fails the terminal summary', () => {
  const rows = parseCoverageJsonl(JSON.stringify(metadata) + '\n{"kind":"fork-output"');
  assert.deepEqual(rows[0], metadata);
  const result = buildCoverageSummary(rows);
  assert.equal(result.run_completed, false);
  assert.equal(result.failures[0].reason, 'interrupted_jsonl_tail');
  assert.throws(() => parseCoverageJsonl('{broken}\n' + JSON.stringify(metadata) + '\n'));
});

test('a later failure terminal overrides an earlier successful terminal', () => {
  assert.equal(buildCoverageSummary([metadata, {kind: 'terminal', success: true},
    {kind: 'terminal', success: false}]).run_completed, false);
});

test('paired resource summaries exclude incomplete forks and preserve pair direction', () => {
  const rows = [metadata];
  for (const [name, cpu] of [['dictionary_full_stream', 100], ['dictionary_unique_edge', 80]]) {
    rows.push({...coverage, variant: name}, {...output, variant: name,
      whole_session_evidence: 'COMPLETE', workload_correctness: 'PASS', cpu_ns: cpu});
  }
  const paired = buildCoverageSummary(rows).paired_dictionary_resources.cpu_ns;
  assert.equal(paired.pairs.length, 1);
  assert.ok(Math.abs(paired.mean_change_percent + 20) < 0.001);
  assert.equal(paired.confidence_interval_95_percent, null);
  rows.find(row => row.kind === 'fork-output' && row.variant === 'dictionary_unique_edge').whole_session_evidence = 'INCOMPLETE';
  assert.equal(buildCoverageSummary(rows).paired_dictionary_resources.cpu_ns.pairs.length, 0);
});
