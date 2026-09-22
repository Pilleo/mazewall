#!/usr/bin/env node
import { readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';

const [oraclePath, coveragePath, terminalPath, outputPath] = process.argv.slice(2);
if (!oraclePath || !coveragePath || !terminalPath || !outputPath) {
  throw new Error('usage: verify_tier_e_split_coverage.mjs <oracle.json> <coverage.json> <terminal.json> <verdict.json>');
}
let oracle;
let coverage;
let terminal;
let oracleBytes;
let terminalBytes;
try {
  oracleBytes = readFileSync(oraclePath);
  terminalBytes = readFileSync(terminalPath);
  oracle = JSON.parse(oracleBytes);
  coverage = JSON.parse(readFileSync(coveragePath, 'utf8'));
  terminal = JSON.parse(terminalBytes);
} catch (error) {
  writeFileSync(outputPath, JSON.stringify({ coverage_verdict: 'UNVERIFIED', policy_eligibility: false, error: String(error) }) + '\n');
  process.exitCode = 1;
  process.exit();
}
const sha256 = (bytes) => createHash('sha256').update(bytes).digest('hex');
const admissionErrors = [];
if (coverage.expected_oracle_sha256 !== sha256(oracleBytes)) admissionErrors.push('oracle_checksum');
if (coverage.terminal_report_sha256 !== sha256(terminalBytes)) admissionErrors.push('terminal_checksum');
if (coverage.registration_id !== terminal.registration_id || coverage.session_epoch !== terminal.session_epoch) admissionErrors.push('session_identity');
if (coverage.evidence_integrity !== terminal.evidence_integrity) admissionErrors.push('evidence_integrity');
for (const dimension of ['capture_domain_validity', 'transport_completeness', 'attribution_coverage', 'dictionary_integrity', 'lifecycle_validity']) {
  if (coverage[dimension] !== terminal[dimension]) admissionErrors.push(dimension);
}
if (coverage.policy_eligibility !== false || terminal.policy_eligibility !== false) admissionErrors.push('policy_eligibility');
if (oracle.declared_operations !== oracle.paths.reduce((count, path) => count + path.frequency, 0)) admissionErrors.push('oracle_frequency');
if (new Set(oracle.paths.map((path) => path.path)).size !== oracle.paths.length) admissionErrors.push('oracle_duplicate_path');
const expected = new Map(oracle.paths.map((row) => [row.path, row]));
const recovered = new Map();
const wrong = [];
for (const edge of coverage.edges) {
  if (!expected.has(edge.workload_path) || edge.syscall_number !== expected.get(edge.workload_path).syscall_number) {
    wrong.push(edge);
  } else if (recovered.has(edge.workload_path)) {
    wrong.push(edge);
  } else {
    recovered.set(edge.workload_path, edge);
  }
}
const missing = [...expected.keys()].filter((path) => !recovered.has(path));
const recall = (from, to) => {
  const expectedCount = [...expected.keys()].filter((path) => path >= from && path <= to).length;
  const recoveredCount = [...recovered.keys()].filter((path) => path >= from && path <= to).length;
  return { expected: expectedCount, recovered: recoveredCount, recall: recoveredCount / expectedCount };
};
const verdict = admissionErrors.length ? 'UNVERIFIED' : wrong.length ? 'FAILED' : missing.length ? 'INCOMPLETE' : 'PASS';
writeFileSync(outputPath, JSON.stringify({
  coverage_verdict: verdict,
  admission_errors: admissionErrors,
  expected_edges: expected.size,
  recovered_edges: recovered.size,
  missing_paths: missing,
  wrong_edges: wrong,
  recall: { common: recall(0, 23), infrequent: recall(24, 47), singleton: recall(48, 71) },
  policy_eligibility: false,
}) + '\n');
if (verdict !== 'PASS') process.exitCode = 1;
