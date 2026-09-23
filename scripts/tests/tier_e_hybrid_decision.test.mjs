import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

test('hybrid decision uses paired forks and rejects a receipt-p99 regression', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'tier-e-hybrid-decision-'));
  const workload = [], phases = [];
  for (let fork = 0; fork < 15; fork++) {
    for (const emission of ['full-stream', 'unique-edge']) {
      for (const variant of ['v1', 'v2', 'hybrid']) {
        const mode = `dictionary-${emission}-${variant}`;
        const common = {mode, fork, fork_complete: true};
        workload.push({...common, workload_ns: Array(10).fill(variant === 'hybrid' ? 110 : 100),
          target_cpu_ns: 1000, collector_cpu_ns: variant === 'hybrid' ? 1100 : 1000});
        phases.push({...common, collector_receipt: {receipt_p99_fine_upper_ns: variant === 'hybrid' && emission === 'unique-edge'
          ? 1250000 : 1000000}});
      }
    }
  }
  try {
    fs.writeFileSync(path.join(dir, 'workload.jsonl'), workload.map(row => JSON.stringify(row)).join('\n') + '\n');
    fs.writeFileSync(path.join(dir, 'phases.jsonl'), phases.map(row => JSON.stringify(row)).join('\n') + '\n');
    const result = spawnSync(process.execPath,
      [new URL('../summarize_tier_e_hybrid_decision.mjs', import.meta.url).pathname,
        path.join(dir, 'workload.jsonl'), path.join(dir, 'phases.jsonl'), path.join(dir, 'decision.json')],
      {encoding: 'utf8'});
    assert.equal(result.status, 0, result.stderr);
    const decision = JSON.parse(fs.readFileSync(path.join(dir, 'decision.json')));
    assert.equal(decision.modes['full-stream'].workload_gate, 'PASS');
    assert.equal(decision.modes['full-stream'].receipt_p99_gate, 'PASS');
    assert.equal(decision.modes['unique-edge'].receipt_p99_gate, 'FAIL');
    assert.equal(decision.adoption_decision, 'REJECT_DEFAULT_PROMOTION');
  } finally {
    fs.rmSync(dir, {recursive: true});
  }
});
