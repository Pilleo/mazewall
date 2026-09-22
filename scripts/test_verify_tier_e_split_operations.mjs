#!/usr/bin/env node
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const root = fs.mkdtempSync(path.join(os.tmpdir(), 'tier-e-operation-verifier-'));
const verifier = path.join(import.meta.dirname, 'verify_tier_e_split_operations.mjs');
const oracle = {expected_operations: 3, directory_fds: [14, 15, 16]};
const terminal = {registration_id: 'test', session_epoch: 4, evidence_integrity: 'COMPLETE', policy_eligibility: false};
const manifest = {registration_id: 'test', session_epoch: 4, representative_only: false,
  exact_directory_fds: [14, 15, 16], unresolved_observations: 0, policy_eligibility: false};

function scenario(overrides, expectedCoverage, expectedEvent, expectedExit) {
  const input = {...manifest, ...overrides};
  for (const [name, value] of Object.entries({oracle, input, terminal})) {
    fs.writeFileSync(path.join(root, `${name}.json`), JSON.stringify(value));
  }
  const output = path.join(root, 'verdict.json');
  const run = spawnSync(process.execPath, [verifier, path.join(root, 'oracle.json'),
    path.join(root, 'input.json'), path.join(root, 'terminal.json'), output], {encoding: 'utf8'});
  const verdict = JSON.parse(fs.readFileSync(output));
  assert.equal(run.status, expectedExit);
  assert.equal(verdict.operation_coverage_verdict, expectedCoverage);
  assert.equal(verdict.event_history_verdict, expectedEvent);
  assert.equal(verdict.policy_eligibility, false);
}

try {
  scenario({}, 'PASS', 'PASS', 0);
  scenario({exact_directory_fds: [14, 15, 15, 16]}, 'PASS', 'INCOMPLETE', 0);
  scenario({exact_directory_fds: [14, 15]}, 'INCOMPLETE', 'INCOMPLETE', 1);
  scenario({session_epoch: 5}, 'UNVERIFIED', 'UNVERIFIED', 1);
  scenario({representative_only: true, exact_directory_fds: [14, 15, 16]}, 'PASS', 'NOT_APPLICABLE', 0);
  console.log('Tier E split operation verifier scenarios passed');
} finally {
  fs.rmSync(root, {recursive: true, force: true});
}
