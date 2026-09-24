import fs from 'node:fs';
import path from 'node:path';

export const COLLECTOR_SAMPLE_BOUNDARY = 'after_terminal_report_before_shutdown';
export const COLLECTOR_SAMPLE_FILE = 'collector-process-metrics.json';

function fail(reason) {
  return { ok: false, reason, sample: null };
}

function readStat(fsImpl, statPath) {
  let raw;
  try {
    raw = fsImpl.readFileSync(statPath, 'utf8');
  } catch (error) {
    if (error && error.code === 'ENOENT') return { error: 'collector_cpu_sample_source_missing' };
    return { error: 'collector_cpu_sample_source_unreadable' };
  }
  const close = raw.lastIndexOf(')');
  if (close < 0) return { error: 'collector_cpu_sample_source_unreadable' };
  const fields = raw.slice(close + 2).trim().split(/\s+/);
  if (fields.length < 20) return { error: 'collector_cpu_sample_source_unreadable' };
  const utime = Number(fields[11]);
  const stime = Number(fields[12]);
  const starttime = Number(fields[19]);
  if (!Number.isFinite(utime) || !Number.isFinite(stime) || !Number.isFinite(starttime)) {
    return { error: 'collector_cpu_sample_source_unreadable' };
  }
  return { utime, stime, starttime };
}

export function sampleCollectorCpu(options) {
  const io = options.fs ?? fs;
  const pid = options.pid;
  const hz = options.hz;
  const boundary = options.boundary;
  const procRoot = options.procRoot ?? '/proc';
  const out = options.out;
  if (!Number.isInteger(pid) || pid <= 0) return fail('collector_cpu_sample_pid_invalid');
  if (!Number.isFinite(hz) || hz <= 0) return fail('collector_cpu_sample_hz_invalid');
  if (boundary !== COLLECTOR_SAMPLE_BOUNDARY) return fail('collector_cpu_sample_boundary_mismatch');
  const dir = path.join(procRoot, String(pid));
  const statPath = path.join(dir, 'stat');
  const statusPath = path.join(dir, 'status');
  const first = readStat(io, statPath);
  if (first.error) return fail(first.error);
  let status;
  try {
    status = io.readFileSync(statusPath, 'utf8');
  } catch (error) {
    if (error && error.code === 'ENOENT') return fail('collector_cpu_sample_source_missing');
    return fail('collector_cpu_sample_source_unreadable');
  }
  const second = readStat(io, statPath);
  if (second.error) return fail(second.error);
  if (first.starttime !== second.starttime) return fail('collector_cpu_sample_process_unstable');
  const match = /^VmHWM:\s+(\d+)\s+kB/m.exec(status);
  if (!match) return fail('collector_cpu_sample_invalid_counters');
  const peakRssKb = Number(match[1]);
  const ticks = first.utime + second.stime;
  if (!Number.isFinite(ticks) || ticks < 0 || !Number.isFinite(peakRssKb) || peakRssKb < 0) {
    return fail('collector_cpu_sample_invalid_counters');
  }
  const cpuNs = Math.round((ticks * 1e9) / hz);
  if (!Number.isFinite(cpuNs) || cpuNs < 0) return fail('collector_cpu_sample_invalid_counters');
  const sample = {
    schema_version: 1,
    collector_host_pid: pid,
    cpu_ticks: ticks,
    cpu_ns: cpuNs,
    peak_rss_kb: peakRssKb,
    unit: 'ns',
    cpu_time_basis: 'process_ticks_since_start',
    sample_boundary: boundary,
    collector_starttime: first.starttime,
  };
  const temporary = `${out}.tmp.${process.pid}`;
  try {
    io.writeFileSync(temporary, `${JSON.stringify(sample)}\n`);
    io.renameSync(temporary, out);
  } catch (error) {
    try {
      io.unlinkSync(temporary);
    } catch {
      // temp file may not exist
    }
    return fail('collector_cpu_sample_write_failed');
  }
  return { ok: true, reason: 'collector_cpu_sample_ok', sample };
}

function main(argv) {
  const args = argv.slice(2);
  const values = {};
  for (let i = 0; i < args.length; i += 2) {
    values[args[i]] = args[i + 1];
  }
  const pid = Number(values['--pid']);
  const hz = Number(values['--hz']);
  const boundary = values['--boundary'];
  const procRoot = values['--proc-root'] ?? '/proc';
  const out = values['--out'];
  if (!out || !boundary || !Number.isInteger(pid) || !Number.isFinite(hz)) {
    process.stderr.write(
      'usage: tier_e_collector_cpu_sample.mjs --pid <pid> --hz <hz> --boundary <boundary> --out <file> [--proc-root <dir>]\n',
    );
    return 2;
  }
  const result = sampleCollectorCpu({ pid, hz, boundary, procRoot, out });
  if (result.ok) {
    process.stdout.write(`${JSON.stringify(result.sample)}\n`);
    return 0;
  }
  process.stderr.write(`${result.reason}\n`);
  return 1;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  process.exitCode = main(process.argv);
}
