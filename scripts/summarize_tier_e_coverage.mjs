import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { normalizeCoverageStacks } from './tier_e_coverage_stack_identity.mjs';
import { buildCoverageSummary, parseCoverageJsonl } from './tier_e_coverage_summary.mjs';

const directory = process.argv[2];
if (!directory) throw new Error('usage: node scripts/summarize_tier_e_coverage.mjs ARTIFACT_DIRECTORY');
const rows = parseCoverageJsonl(fs.readFileSync(path.join(directory, 'results.jsonl'), 'utf8'));
const coverage = rows.filter(row => row.kind === 'coverage');
const comparison = [];
for (const row of coverage) {
  const filename = path.join(directory, 'coverage-manifests', `${row.variant}-fork-${row.fork}.txt.stacks.json`);
  const reference = path.join(directory, 'coverage-manifests', `dictionary_full_stream-fork-${row.fork}.txt.stacks.json`);
  if (!fs.existsSync(filename) || !fs.existsSync(reference)) {
    comparison.push({ variant: row.variant, fork: row.fork, status: 'UNVERIFIED' });
    continue;
  }
  const actual = normalizeCoverageStacks(JSON.parse(fs.readFileSync(filename, 'utf8')));
  const expected = normalizeCoverageStacks(JSON.parse(fs.readFileSync(reference, 'utf8')));
  const shared = Object.keys(expected).filter(key => key in actual);
  const mismatches = shared.filter(key => JSON.stringify(expected[key]) !== JSON.stringify(actual[key]));
  comparison.push({ variant: row.variant, fork: row.fork, shared_paths: shared.length, mismatches,
    status: mismatches.length ? 'FAILED' : shared.length === 72 ? 'PASS' : 'INCOMPLETE',
    qualification: 'same_immutable_bundle_and_JDK_no_redefinition_workload' });
}
const summary = {
  ...buildCoverageSummary(rows),
  full_stack_comparison: comparison,
};
fs.writeFileSync(path.join(directory, 'coverage-summary.json'), JSON.stringify(summary, null, 2) + '\n');
const files = ['results.jsonl', 'manifest.json', 'coverage-summary.json'];
const checksums = files.filter(file => fs.existsSync(path.join(directory, file)))
  .map(file => `${crypto.createHash('sha256').update(fs.readFileSync(path.join(directory, file))).digest('hex')}  ${file}`);
fs.writeFileSync(path.join(directory, 'coverage-checksums.sha256'), checksums.join('\n') + '\n');
console.log(JSON.stringify({ artifact: path.join(directory, 'coverage-summary.json'), completed: summary.run_completed,
  variants: summary.variants.map(item => ({ variant: item.variant, all_forks_recover_all_paths: item.all_forks_recover_all_paths, cumulative_recall: item.cumulative_recall })) }));
