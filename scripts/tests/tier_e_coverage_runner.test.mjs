import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

for (const status of [0, 7]) {
  test(`saved wrapper reports partial artifacts and preserves runner status ${status}`, () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'tier-e-coverage-runner-test-'));
    const scripts = path.join(root, 'scripts');
    const artifact = path.join(root, 'artifact');
    fs.mkdirSync(scripts);
    fs.copyFileSync(new URL('../run_tier_e_coverage_benchmark.sh', import.meta.url), path.join(scripts, 'run_tier_e_coverage_benchmark.sh'));
    fs.writeFileSync(path.join(scripts, 'run_tier_e_trustworthy_benchmark.sh'),
      `#!/bin/bash\nmkdir -p "$1"\nprintf '%s\\n' '{"kind":"metadata","forks":1}' > "$1/results.jsonl"\nexit ${status}\n`);
    fs.writeFileSync(path.join(scripts, 'summarize_tier_e_coverage.mjs'),
      'import fs from "node:fs"; fs.writeFileSync(process.argv[2] + "/reported", "yes");\n');
    try {
      const child = spawnSync('bash', [path.join(scripts, 'run_tier_e_coverage_benchmark.sh'), 'smoke', artifact],
        {encoding: 'utf8', timeout: 10000});
      assert.equal(child.status, status, child.stderr);
      assert.equal(fs.readFileSync(path.join(artifact, 'reported'), 'utf8'), 'yes');
    } finally {
      fs.rmSync(root, {recursive: true});
    }
  });
}
