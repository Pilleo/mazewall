import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { sampleCollectorCpu, COLLECTOR_SAMPLE_BOUNDARY } from '../tier_e_collector_cpu_sample.mjs';

const SCRIPT = fileURLToPath(new URL('../tier_e_collector_cpu_sample.mjs', import.meta.url));

const STAT = '1234 (tier-e-daemon) S 1 1000 1000 0 -1 4194560 100 0 0 0 40 12 0 0 20 0 8 0 987654 123456789 1000 18446744073709551615 1 2 0 0 0 0 0 0 0 0 0 0 17 0 0 0 0 0 0';
const STATUS = 'Name:\ttier-e-daemon\nVmHWM:\t245760 kB\n';

function writeProc(root, pid, { stat = STAT, status = STATUS } = {}) {
  const dir = path.join(root, String(pid));
  fs.mkdirSync(dir, { recursive: true });
  if (stat !== null) fs.writeFileSync(path.join(dir, 'stat'), stat);
  if (status !== null) fs.writeFileSync(path.join(dir, 'status'), status);
  return dir;
}

function tempRoot() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'tier-e-collector-'));
}

function opts(root, overrides = {}) {
  return {
    pid: 4321,
    hz: 100,
    procRoot: root,
    boundary: COLLECTOR_SAMPLE_BOUNDARY,
    out: path.join(root, 'collector-process-metrics.json'),
    ...overrides,
  };
}

test('valid collector sample converts ticks to nanoseconds and writes parser-compatible fields', () => {
  const root = tempRoot();
  try {
    writeProc(root, 4321);
    const result = sampleCollectorCpu(opts(root));
    assert.equal(result.ok, true);
    assert.equal(result.reason, 'collector_cpu_sample_ok');
    const written = JSON.parse(fs.readFileSync(path.join(root, 'collector-process-metrics.json'), 'utf8'));
    assert.equal(written.collector_host_pid, 4321);
    assert.equal(written.cpu_ticks, 52);
    assert.equal(written.cpu_ns, 520000000);
    assert.equal(written.peak_rss_kb, 245760);
    assert.equal(written.sample_boundary, 'after_terminal_report_before_shutdown');
    assert.equal(written.cpu_time_basis, 'process_ticks_since_start');
    assert.equal(written.unit, 'ns');
  } finally {
    fs.rmSync(root, { recursive: true });
  }
});

test('collector CPU counters come from the same second stat snapshot', () => {
  const root = tempRoot();
  try {
    writeProc(root, 4321);
    let statReads = 0;
    const second = STAT.replace(' 40 12 0 0 20 ', ' 70 19 0 0 20 ');
    const io = {
      ...fs,
      readFileSync(file, encoding) {
        if (String(file).endsWith('/stat')) return ++statReads === 1 ? STAT : second;
        return fs.readFileSync(file, encoding);
      },
    };
    const result = sampleCollectorCpu(opts(root, { fs: io }));
    assert.equal(result.ok, true);
    assert.equal(statReads, 2);
    assert.equal(result.sample.cpu_ticks, 89);
    assert.equal(result.sample.cpu_ns, 890000000);
  } finally {
    fs.rmSync(root, { recursive: true });
  }
});

test('missing /proc/<pid>/stat source fails with collector_cpu_sample_source_missing', () => {
  const root = tempRoot();
  try {
    writeProc(root, 4321, { stat: null });
    const result = sampleCollectorCpu(opts(root));
    assert.equal(result.ok, false);
    assert.equal(result.reason, 'collector_cpu_sample_source_missing');
    assert.equal(fs.existsSync(path.join(root, 'collector-process-metrics.json')), false);
  } finally {
    fs.rmSync(root, { recursive: true });
  }
});

test('unreadable /proc/<pid>/stat source fails with collector_cpu_sample_source_unreadable', () => {
  const root = tempRoot();
  try {
    const dir = writeProc(root, 4321, { stat: null });
    fs.mkdirSync(path.join(dir, 'stat'));
    const result = sampleCollectorCpu(opts(root));
    assert.equal(result.ok, false);
    assert.equal(result.reason, 'collector_cpu_sample_source_unreadable');
  } finally {
    fs.rmSync(root, { recursive: true });
  }
});

test('collector disappearing between reads fails with collector_cpu_sample_source_missing', () => {
  const root = tempRoot();
  try {
    writeProc(root, 4321);
    let reads = 0;
    const fakeFs = {
      readFileSync(file, encoding) {
        reads += 1;
        if (String(file).endsWith('/stat') && reads > 1) {
          const err = new Error('no such file');
          err.code = 'ENOENT';
          throw err;
        }
        return fs.readFileSync(file, encoding);
      },
      existsSync: p => fs.existsSync(p),
      writeFileSync: (p, d) => fs.writeFileSync(p, d),
      renameSync: (a, b) => fs.renameSync(a, b),
      mkdirSync: (p, o) => fs.mkdirSync(p, o),
      unlinkSync: p => fs.unlinkSync(p),
    };
    const result = sampleCollectorCpu(opts(root, { fs: fakeFs }));
    assert.equal(result.ok, false);
    assert.equal(result.reason, 'collector_cpu_sample_source_missing');
  } finally {
    fs.rmSync(root, { recursive: true });
  }
});

test('unstable starttime fails with collector_cpu_sample_process_unstable', () => {
  const root = tempRoot();
  try {
    writeProc(root, 4321);
    const base = fs.readFileSync(path.join(root, '4321', 'stat'), 'utf8');
    const drifted = base.replace('987654', '987655');
    let statReads = 0;
    const fakeFs = {
      readFileSync(file, encoding) {
        if (String(file).endsWith('/stat')) {
          statReads += 1;
          return statReads === 1 ? base : drifted;
        }
        return fs.readFileSync(file, encoding);
      },
      existsSync: p => fs.existsSync(p),
      writeFileSync: (p, d) => fs.writeFileSync(p, d),
      renameSync: (a, b) => fs.renameSync(a, b),
      mkdirSync: (p, o) => fs.mkdirSync(p, o),
      unlinkSync: p => fs.unlinkSync(p),
    };
    const result = sampleCollectorCpu(opts(root, { fs: fakeFs }));
    assert.equal(result.ok, false);
    assert.equal(result.reason, 'collector_cpu_sample_process_unstable');
  } finally {
    fs.rmSync(root, { recursive: true });
  }
});

test('negative counters fail with collector_cpu_sample_invalid_counters', () => {
  const root = tempRoot();
  try {
    writeProc(root, 4321, { stat: STAT.replace(' 40 12 0 0 20 ', ' -40 12 0 0 20 ') });
    const result = sampleCollectorCpu(opts(root));
    assert.equal(result.ok, false);
    assert.equal(result.reason, 'collector_cpu_sample_invalid_counters');
  } finally {
    fs.rmSync(root, { recursive: true });
  }
});

test('negative peak RSS fails with collector_cpu_sample_invalid_counters', () => {
  const root = tempRoot();
  try {
    writeProc(root, 4321, { status: 'Name:\ttier-e-daemon\nVmHWM:\t-1 kB\n' });
    const result = sampleCollectorCpu(opts(root));
    assert.equal(result.ok, false);
    assert.equal(result.reason, 'collector_cpu_sample_invalid_counters');
  } finally {
    fs.rmSync(root, { recursive: true });
  }
});

test('changed sample boundary is rejected', () => {
  const root = tempRoot();
  try {
    writeProc(root, 4321);
    const result = sampleCollectorCpu(opts(root, { boundary: 'after_workload_before_exit' }));
    assert.equal(result.ok, false);
    assert.equal(result.reason, 'collector_cpu_sample_boundary_mismatch');
  } finally {
    fs.rmSync(root, { recursive: true });
  }
});

test('nonpositive hz is rejected', () => {
  const root = tempRoot();
  try {
    writeProc(root, 4321);
    const result = sampleCollectorCpu(opts(root, { hz: 0 }));
    assert.equal(result.ok, false);
    assert.equal(result.reason, 'collector_cpu_sample_hz_invalid');
  } finally {
    fs.rmSync(root, { recursive: true });
  }
});

test('nonpositive pid is rejected', () => {
  const root = tempRoot();
  try {
    writeProc(root, 4321);
    const result = sampleCollectorCpu(opts(root, { pid: 0 }));
    assert.equal(result.ok, false);
    assert.equal(result.reason, 'collector_cpu_sample_pid_invalid');
  } finally {
    fs.rmSync(root, { recursive: true });
  }
});

test('CLI exits nonzero with an explanatory reason when the source is removed', () => {
  const root = tempRoot();
  try {
    writeProc(root, 4321, { stat: null });
    const run = spawnSync(process.execPath, [
      SCRIPT, '--pid', '4321', '--hz', '100', '--proc-root', root,
      '--boundary', COLLECTOR_SAMPLE_BOUNDARY, '--out', path.join(root, 'm.json'),
    ], { encoding: 'utf8' });
    assert.equal(run.status, 1);
    assert.match(run.stderr, /collector_cpu_sample_source_missing/);
  } finally {
    fs.rmSync(root, { recursive: true });
  }
});
