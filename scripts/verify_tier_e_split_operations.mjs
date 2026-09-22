#!/usr/bin/env node
import fs from 'node:fs';

const [oraclePath, observationsPath, terminalPath, outputPath] = process.argv.slice(2);
if (!outputPath) throw new Error('usage: verify_tier_e_split_operations.mjs oracle observations terminal output');
const read = path => JSON.parse(fs.readFileSync(path, 'utf8'));
const oracle = read(oraclePath);
const observations = read(observationsPath);
const terminal = read(terminalPath);
const declared = new Set(oracle.directory_fds);
const seen = new Map();
for (const fd of observations.exact_directory_fds) seen.set(fd, (seen.get(fd) ?? 0) + 1);
const missing = [...declared].filter(fd => !seen.has(fd));
const duplicates = [...declared].filter(fd => seen.get(fd) > 1).map(fd => ({fd, count: seen.get(fd)}));
const background = [...seen].filter(([fd]) => !declared.has(fd)).map(([fd, count]) => ({fd, count}));
const valid = oracle.expected_operations === oracle.directory_fds.length &&
  declared.size === oracle.directory_fds.length &&
  observations.registration_id === terminal.registration_id &&
  observations.session_epoch === terminal.session_epoch &&
  observations.policy_eligibility === false && terminal.policy_eligibility === false;
const coverageVerdict = !valid ? 'UNVERIFIED' : missing.length ? 'INCOMPLETE' : 'PASS';
const eventVerdict = !valid ? 'UNVERIFIED' :
  observations.representative_only ? 'NOT_APPLICABLE' :
  missing.length || duplicates.length || background.length || observations.unresolved_observations ? 'INCOMPLETE' : 'PASS';
const result = {
  schema_version: 1,
  expected_operations: oracle.expected_operations,
  recovered_operation_ids: declared.size - missing.length,
  missing_operation_ids: missing,
  duplicate_declared_ids: duplicates,
  background_ids: background,
  unresolved_observations: observations.unresolved_observations,
  operation_coverage_verdict: coverageVerdict,
  event_history_verdict: eventVerdict,
  terminal_evidence_integrity: terminal.evidence_integrity,
  policy_eligibility: false,
};
fs.writeFileSync(outputPath, `${JSON.stringify(result)}\n`);
console.log(JSON.stringify(result));
if (coverageVerdict !== 'PASS' || terminal.evidence_integrity !== 'COMPLETE') process.exitCode = 1;
