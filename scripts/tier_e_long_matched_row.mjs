#!/usr/bin/env node
// Normalizes one long-matched arm artifact directory into the fork-results row
// consumed by summarize_tier_e_long_matched_controls.mjs. Missing or malformed
// artifacts surface as observed nulls; they are never invented or defaulted.
import fs from 'node:fs';
import path from 'node:path';

function readJson(dir, name) {
  const file = path.join(dir, name);
  if (!fs.existsSync(file)) return { value: null, error: null };
  try {
    return { value: JSON.parse(fs.readFileSync(file, 'utf8')), error: null };
  } catch (error) {
    return { value: null, error: `${name}: ${error.message}` };
  }
}

function deriveArm(arm) {
  if (arm === 'baseline') return { emission: null, variant: 'baseline' };
  const withoutPrefix = arm.startsWith('dictionary-') ? arm.slice('dictionary-'.length) : arm;
  const cut = withoutPrefix.lastIndexOf('-');
  return { emission: withoutPrefix.slice(0, cut), variant: withoutPrefix.slice(cut + 1) };
}

export function buildLongMatchedRow(meta, dir) {
  const { emission, variant } = deriveArm(meta.arm);
  const baseline = meta.arm === 'baseline';
  const oracle = readJson(dir, 'coverage-oracle.json');
  const targetCpu = readJson(dir, 'target-cpu.json');
  const collectorCpu = readJson(dir, 'collector-process-metrics.json');
  const coverage = readJson(dir, 'coverage-verdict.json');
  const terminal = readJson(dir, 'terminal-report.json');
  const summary = readJson(dir, 'summary.json');
  const artifactErrors = [oracle, targetCpu, collectorCpu, coverage, terminal, summary]
    .map(entry => entry.error)
    .filter(Boolean);

  let coverageVerdict = coverage.value?.coverage_verdict ?? null;
  let coveredPaths = coverage.value?.recovered_edges ?? null;
  if (baseline) {
    if (summary.value?.mode === 'baseline') {
      coverageVerdict = 'NOT_APPLICABLE';
      coveredPaths = null;
    } else {
      coveredPaths = null;
    }
  }
  const policyEligibility = terminal.value?.policy_eligibility
    ?? coverage.value?.policy_eligibility
    ?? summary.value?.policy_eligibility
    ?? false;

  return {
    fork: Number(meta.fork),
    arm: meta.arm,
    emission,
    variant,
    seed: oracle.value?.seed ?? meta.seed ?? null,
    declared_operations: oracle.value?.declared_operations ?? meta.declaredOperations ?? null,
    source_digest: meta.sourceDigest ?? null,
    image_id: meta.imageId ?? null,
    native_so_sha256: meta.nativeSoSha256 ?? null,
    daemon_jars_sha256: meta.daemonJarsSha256 ?? null,
    status: Number(meta.status),
    timed_out: meta.timedOut === true,
    policy_eligibility: policyEligibility,
    coverage_verdict: coverageVerdict,
    covered_paths: coveredPaths,
    terminal_integrity: terminal.value?.evidence_integrity ?? null,
    target_cpu_ns: targetCpu.value?.target_cpu_ns ?? null,
    target_sample_boundary: targetCpu.value?.sample_boundary ?? null,
    collector_cpu_ns: collectorCpu.value?.cpu_ns ?? null,
    collector_sample_boundary: collectorCpu.value?.sample_boundary ?? null,
    artifact_errors: artifactErrors,
  };
}

function main(argv) {
  let metaJson = null;
  let dir = null;
  for (let i = 0; i < argv.length; i += 2) {
    if (argv[i] === '--meta') metaJson = argv[i + 1];
    else if (argv[i] === '--dir') dir = argv[i + 1];
  }
  if (!metaJson || !dir) {
    process.stderr.write('usage: tier_e_long_matched_row.mjs --meta <json> --dir <artifact-dir>\n');
    return 2;
  }
  const meta = JSON.parse(metaJson);
  const row = buildLongMatchedRow(meta, dir);
  process.stdout.write(`${JSON.stringify(row)}\n`);
  return 0;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  process.exitCode = main(process.argv.slice(1));
}
