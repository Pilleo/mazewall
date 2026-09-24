import fs from 'node:fs';
import path from 'node:path';

export const TARGET_CPU_SAMPLE_FILE = 'target-cpu.json';
export const TARGET_CPU_SAMPLE_VERSION = 1;
export const TARGET_CPU_SAMPLE_BOUNDARY = 'after_workload_before_exit';
export const TARGET_CPU_SAMPLE_UNIT = 'ns';

const REQUIRED_FIELDS = [
  'schema_version',
  'version',
  'pid',
  'host_pid',
  'starttime',
  'sample_boundary',
  'unit',
  'target_cpu_ns',
];

function invalid(reason, sample = null) {
  return { valid: false, reason, sample, target_cpu_ns: null };
}

export function validateTargetCpuSample(value, options = {}) {
  const expectedHostPid = options.expectedHostPid ?? null;
  const expectedStarttime = options.expectedStarttime ?? null;
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return invalid('target_cpu_sample_malformed');
  }
  for (const field of REQUIRED_FIELDS) {
    if (!Object.hasOwn(value, field)) {
      return invalid('target_cpu_sample_invalid', value);
    }
  }
  if (value.schema_version !== 1) {
    return invalid('target_cpu_sample_invalid', value);
  }
  if (!Number.isInteger(value.pid) || value.pid <= 0) {
    return invalid('target_cpu_sample_invalid', value);
  }
  if (!Number.isInteger(value.host_pid) || value.host_pid <= 0) {
    return invalid('target_cpu_sample_invalid', value);
  }
  if (!Number.isInteger(value.starttime) || value.starttime <= 0) {
    return invalid('target_cpu_sample_invalid', value);
  }
  if (value.sample_boundary !== TARGET_CPU_SAMPLE_BOUNDARY) {
    return invalid('target_cpu_sample_boundary_mismatch', value);
  }
  if (value.unit !== TARGET_CPU_SAMPLE_UNIT) {
    return invalid('target_cpu_sample_unit_mismatch', value);
  }
  if (value.version !== TARGET_CPU_SAMPLE_VERSION) {
    return invalid('target_cpu_sample_version_mismatch', value);
  }
  if (typeof value.target_cpu_ns !== 'number' || !Number.isFinite(value.target_cpu_ns) || value.target_cpu_ns < 0) {
    return invalid('target_cpu_sample_invalid', value);
  }
  if (expectedHostPid !== null && value.host_pid !== expectedHostPid) {
    return invalid('target_cpu_sample_host_pid_mismatch', value);
  }
  if (expectedStarttime !== null && value.starttime !== expectedStarttime) {
    return invalid('target_cpu_sample_starttime_mismatch', value);
  }
  return { valid: true, reason: 'target_cpu_sample_ok', sample: value, target_cpu_ns: value.target_cpu_ns };
}

export function readTargetCpuSample(dir, options = {}) {
  const file = path.join(dir, TARGET_CPU_SAMPLE_FILE);
  let raw;
  try {
    raw = fs.readFileSync(file, 'utf8');
  } catch (error) {
    if (error && error.code === 'ENOENT') {
      return { available: false, valid: false, reason: 'missing_target_cpu_sample', sample: null, target_cpu_ns: null };
    }
    return { available: true, valid: false, reason: 'target_cpu_sample_malformed', sample: null, target_cpu_ns: null };
  }
  let parsed;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return { available: true, valid: false, reason: 'target_cpu_sample_malformed', sample: null, target_cpu_ns: null };
  }
  const validation = validateTargetCpuSample(parsed, options);
  return { available: true, ...validation };
}

function main(argv) {
  const args = argv.slice(2);
  let dir = null;
  let expectedHostPid = null;
  let expectedStarttime = null;
  for (let i = 0; i < args.length; i += 1) {
    if (args[i] === '--validate') {
      dir = args[i + 1] ?? null;
      i += 1;
    } else if (args[i] === '--host-pid') {
      expectedHostPid = Number(args[i + 1]);
      i += 1;
    } else if (args[i] === '--starttime') {
      expectedStarttime = Number(args[i + 1]);
      i += 1;
    }
  }
  if (!dir) {
    process.stderr.write(
      'usage: tier_e_target_cpu_sample.mjs --validate <dir> [--host-pid <pid>] [--starttime <starttime>]\n',
    );
    return 2;
  }
  const options = {};
  if (Number.isInteger(expectedHostPid) && expectedHostPid > 0) options.expectedHostPid = expectedHostPid;
  if (Number.isInteger(expectedStarttime) && expectedStarttime > 0) options.expectedStarttime = expectedStarttime;
  const result = readTargetCpuSample(dir, options);
  if (result.valid) {
    process.stdout.write(
      `${JSON.stringify({
        reason: result.reason,
        pid: result.sample.pid,
        host_pid: result.sample.host_pid,
        starttime: result.sample.starttime,
        sample_boundary: result.sample.sample_boundary,
        unit: result.sample.unit,
        target_cpu_ns: result.target_cpu_ns,
      })}\n`,
    );
    return 0;
  }
  process.stderr.write(`${result.reason}\n`);
  return 1;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  process.exitCode = main(process.argv);
}
