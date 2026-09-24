import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { buildLongMatchedRow } from '../tier_e_long_matched_row.mjs';

const META = {
  fork: 1,
  arm: 'dictionary-full-stream-hybrid',
  status: 0,
  timedOut: false,
  seed: 23063,
  declaredOperations: 75000,
  sourceDigest: 'abc123',
  imageId: 'sha256:image',
  nativeSoSha256: 'native-sha',
  daemonJarsSha256: 'jars-sha',
};

function tempDir(files = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tier-e-row-'));
  for (const [name, content] of Object.entries(files)) {
    fs.writeFileSync(path.join(dir, name), typeof content === 'string' ? content : `${JSON.stringify(content)}\n`);
  }
  return dir;
}

test('profiled arm row exposes the full estimator contract from real artifacts', () => {
  const dir = tempDir({
    'coverage-oracle.json': { schema_version: 1, seed: 23063, declared_operations: 75000, paths: [] },
    'target-cpu.json': {
      schema_version: 1, version: 1, pid: 1, host_pid: 4321, starttime: 999,
      sample_boundary: 'after_workload_before_exit', unit: 'ns', target_cpu_ns: 5930000000,
    },
    'collector-process-metrics.json': {
      schema_version: 1, collector_host_pid: 8765, cpu_ticks: 161, cpu_ns: 1610000000,
      peak_rss_kb: 110320, unit: 'ns', cpu_time_basis: 'process_ticks_since_start',
      sample_boundary: 'after_terminal_report_before_shutdown', collector_starttime: 998,
    },
    'coverage-verdict.json': {
      coverage_verdict: 'PASS', expected_edges: 72, recovered_edges: 72, wrong_edges: [],
      policy_eligibility: false,
    },
    'terminal-report.json': { evidence_integrity: 'COMPLETE', policy_eligibility: false },
  });
  try {
    const row = buildLongMatchedRow(META, dir);
    assert.equal(row.fork, 1);
    assert.equal(row.arm, 'dictionary-full-stream-hybrid');
    assert.equal(row.emission, 'full-stream');
    assert.equal(row.variant, 'hybrid');
    assert.equal(row.seed, 23063);
    assert.equal(row.declared_operations, 75000);
    assert.equal(row.source_digest, 'abc123');
    assert.equal(row.image_id, 'sha256:image');
    assert.equal(row.native_so_sha256, 'native-sha');
    assert.equal(row.daemon_jars_sha256, 'jars-sha');
    assert.equal(row.status, 0);
    assert.equal(row.timed_out, false);
    assert.equal(row.policy_eligibility, false);
    assert.equal(row.coverage_verdict, 'PASS');
    assert.equal(row.covered_paths, 72);
    assert.equal(row.terminal_integrity, 'COMPLETE');
    assert.equal(row.target_cpu_ns, 5930000000);
    assert.equal(row.target_sample_boundary, 'after_workload_before_exit');
    assert.equal(row.collector_cpu_ns, 1610000000);
    assert.equal(row.collector_sample_boundary, 'after_terminal_report_before_shutdown');
  } finally {
    fs.rmSync(dir, { recursive: true });
  }
});

test('baseline arm row has no collector and no terminal evidence', () => {
  const dir = tempDir({
    'coverage-oracle.json': { schema_version: 1, seed: 23063, declared_operations: 75000, paths: [] },
    'target-cpu.json': {
      schema_version: 1, version: 1, pid: 1, host_pid: 5555, starttime: 111,
      sample_boundary: 'after_workload_before_exit', unit: 'ns', target_cpu_ns: 5930000000,
    },
    'summary.json': { schema_version: 1, mode: 'baseline', target_exit: 0, policy_eligibility: false },
  });
  try {
    const row = buildLongMatchedRow({ ...META, arm: 'baseline' }, dir);
    assert.equal(row.emission, null);
    assert.equal(row.variant, 'baseline');
    assert.equal(row.coverage_verdict, 'NOT_APPLICABLE');
    assert.equal(row.covered_paths, null);
    assert.equal(row.terminal_integrity, null);
    assert.equal(row.collector_cpu_ns, null);
    assert.equal(row.collector_sample_boundary, null);
    assert.equal(row.target_cpu_ns, 5930000000);
    assert.equal(row.policy_eligibility, false);
  } finally {
    fs.rmSync(dir, { recursive: true });
  }
});

test('timed-out arm keeps observed nulls and carries the exit status honestly', () => {
  const dir = tempDir({});
  try {
    const row = buildLongMatchedRow(
      { ...META, arm: 'dictionary-unique-edge-v1', status: 124, timedOut: true },
      dir,
    );
    assert.equal(row.emission, 'unique-edge');
    assert.equal(row.variant, 'v1');
    assert.equal(row.status, 124);
    assert.equal(row.timed_out, true);
    assert.equal(row.coverage_verdict, null);
    assert.equal(row.covered_paths, null);
    assert.equal(row.terminal_integrity, null);
    assert.equal(row.target_cpu_ns, null);
    assert.equal(row.collector_cpu_ns, null);
    assert.equal(row.seed, 23063);
    assert.equal(row.declared_operations, 75000);
    assert.equal(row.policy_eligibility, false);
  } finally {
    fs.rmSync(dir, { recursive: true });
  }
});
