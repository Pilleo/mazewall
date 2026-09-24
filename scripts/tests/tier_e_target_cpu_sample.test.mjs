import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { readTargetCpuSample, validateTargetCpuSample } from '../tier_e_target_cpu_sample.mjs';

const VALID = {
  schema_version: 1,
  version: 1,
  pid: 1,
  host_pid: 3514,
  starttime: 133817,
  sample_boundary: 'after_workload_before_exit',
  unit: 'ns',
  target_cpu_ns: 123456789,
};

function writeDir(files) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tier-e-cpu-'));
  for (const [name, content] of Object.entries(files)) {
    fs.writeFileSync(path.join(dir, name), content);
  }
  return dir;
}

test('valid sample passes and exposes pid, boundary, and cpu', () => {
  const result = validateTargetCpuSample(VALID, { expectedHostPid: 3514 });
  assert.equal(result.valid, true);
  assert.equal(result.reason, 'target_cpu_sample_ok');
  assert.equal(result.sample.target_cpu_ns, 123456789);
  assert.equal(result.sample.pid, 1);
  assert.equal(result.sample.sample_boundary, 'after_workload_before_exit');
  assert.equal(result.sample.unit, 'ns');
  assert.equal(result.target_cpu_ns, 123456789);
});

test('missing target-cpu.json reports missing_target_cpu_sample', () => {
  const dir = writeDir({});
  try {
    const result = readTargetCpuSample(dir);
    assert.equal(result.available, false);
    assert.equal(result.valid, false);
    assert.equal(result.reason, 'missing_target_cpu_sample');
    assert.equal(result.target_cpu_ns, null);
  } finally {
    fs.rmSync(dir, { recursive: true });
  }
});

test('malformed JSON reports target_cpu_sample_malformed instead of crashing', () => {
  const dir = writeDir({ 'target-cpu.json': '{"schema_version":1,,,' });
  try {
    const result = readTargetCpuSample(dir);
    assert.equal(result.available, true);
    assert.equal(result.valid, false);
    assert.equal(result.reason, 'target_cpu_sample_malformed');
  } finally {
    fs.rmSync(dir, { recursive: true });
  }
});

test('non-object payload is malformed', () => {
  const result = validateTargetCpuSample([1, 2, 3]);
  assert.equal(result.valid, false);
  assert.equal(result.reason, 'target_cpu_sample_malformed');
});

test('missing required field is invalid', () => {
  const { host_pid: _dropped, ...withoutHost } = VALID;
  const result = validateTargetCpuSample(withoutHost);
  assert.equal(result.valid, false);
  assert.equal(result.reason, 'target_cpu_sample_invalid');
});

test('non-numeric cpu value is invalid', () => {
  const result = validateTargetCpuSample({ ...VALID, target_cpu_ns: '123' });
  assert.equal(result.valid, false);
  assert.equal(result.reason, 'target_cpu_sample_invalid');
});

test('negative cpu value is invalid', () => {
  const result = validateTargetCpuSample({ ...VALID, target_cpu_ns: -1 });
  assert.equal(result.valid, false);
  assert.equal(result.reason, 'target_cpu_sample_invalid');
});

test('non-integer pid is invalid', () => {
  const result = validateTargetCpuSample({ ...VALID, pid: 0.5 });
  assert.equal(result.valid, false);
  assert.equal(result.reason, 'target_cpu_sample_invalid');
});

test('non-positive pid is invalid', () => {
  const result = validateTargetCpuSample({ ...VALID, pid: 0 });
  assert.equal(result.valid, false);
  assert.equal(result.reason, 'target_cpu_sample_invalid');
});

test('changed sample boundary is rejected', () => {
  const result = validateTargetCpuSample({ ...VALID, sample_boundary: 'after_collector_report' });
  assert.equal(result.valid, false);
  assert.equal(result.reason, 'target_cpu_sample_boundary_mismatch');
});

test('wrong unit is rejected', () => {
  const result = validateTargetCpuSample({ ...VALID, unit: 'ms' });
  assert.equal(result.valid, false);
  assert.equal(result.reason, 'target_cpu_sample_unit_mismatch');
});

test('wrong sample version is rejected', () => {
  const result = validateTargetCpuSample({ ...VALID, version: 2 });
  assert.equal(result.valid, false);
  assert.equal(result.reason, 'target_cpu_sample_version_mismatch');
});

test('host pid binding mismatch is rejected', () => {
  const result = validateTargetCpuSample(VALID, { expectedHostPid: 9999 });
  assert.equal(result.valid, false);
  assert.equal(result.reason, 'target_cpu_sample_host_pid_mismatch');
});

test('registration starttime mismatch is rejected', () => {
  const result = validateTargetCpuSample(VALID, { expectedStarttime: 134936 });
  assert.equal(result.valid, false);
  assert.equal(result.reason, 'target_cpu_sample_starttime_mismatch');
});

test('nonpositive starttime is invalid', () => {
  const result = validateTargetCpuSample({ ...VALID, starttime: 0 });
  assert.equal(result.valid, false);
  assert.equal(result.reason, 'target_cpu_sample_invalid');
});

test('read passes expectedHostPid through to validation', () => {
  const dir = writeDir({ 'target-cpu.json': JSON.stringify(VALID) });
  try {
    const ok = readTargetCpuSample(dir, { expectedHostPid: 3514, expectedStarttime: 133817 });
    assert.equal(ok.valid, true);
    assert.equal(ok.reason, 'target_cpu_sample_ok');
    const bad = readTargetCpuSample(dir, { expectedHostPid: 7 });
    assert.equal(bad.valid, false);
    assert.equal(bad.reason, 'target_cpu_sample_host_pid_mismatch');
    const badStart = readTargetCpuSample(dir, { expectedHostPid: 3514, expectedStarttime: 42 });
    assert.equal(badStart.valid, false);
    assert.equal(badStart.reason, 'target_cpu_sample_starttime_mismatch');
  } finally {
    fs.rmSync(dir, { recursive: true });
  }
});
