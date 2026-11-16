#!/usr/bin/env python3
"""Executable controls for trusted security snapshot selection and restoration."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).with_name('security_snapshot.py')
PREFIX = 'dc-data-v1-Linux-X64-dc13.0.0_'
SHA = 'a' * 40
DIGEST = 'sha256:' + 'b' * 64


def candidate(attempt=1, latest=1):
    run = {'id': 42, 'workflow_id': 101, 'run_attempt': latest,
           'repository': {'id': 1}, 'head_repository': {'id': 1},
           'head_sha': SHA, 'head_branch': 'master', 'event': 'schedule',
           'status': 'completed'}
    producer = dict(run, run_attempt=attempt, status='completed')
    artifact = {'id': 88, 'name': f'security-snapshot-{PREFIX}42-{attempt}',
                'expired': False, 'size_in_bytes': 100, 'digest': DIGEST,
                'created_at': '2026-01-01T00:00:00Z',
                'expires_at': '2099-01-01T00:00:00Z',
                'workflow_run': {'id': 42, 'repository_id': 1,
                                 'head_repository_id': 1, 'head_sha': SHA,
                                 'head_branch': 'master'}}
    return {'artifact': artifact, 'run': run, 'attempt': producer}


class SnapshotControls(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.output = self.root / 'output'
        self.env = dict(os.environ, GITHUB_OUTPUT=str(self.output), SNAPSHOT_PREFIX=PREFIX)

    def select(self, item, *, updater=101, legacy=102):
        payload = {'repository_id': 1, 'default_branch': 'master',
                   'workflow_ids': {'updater': updater, 'legacy_ci': legacy},
                   'candidates': [] if item is None else [item]}
        path = self.root / 'candidates.json'
        path.write_text(json.dumps(payload))
        return subprocess.run([sys.executable, str(SCRIPT), 'select', str(path)],
                              env=self.env, capture_output=True, text=True)

    def test_trusted_updater_and_legacy_bootstrap(self):
        self.assertEqual(self.select(candidate()).returncode, 0)
        legacy = candidate()
        for part in ('run', 'attempt'):
            legacy[part].update(workflow_id=102, event='push')
        self.assertEqual(self.select(legacy, updater=None).returncode, 0)
        self.assertIn('artifact_id=88', self.output.read_text())

    def test_previous_attempt_survives_new_attempt(self):
        for status in ('in_progress', 'completed'):
            item = candidate(latest=2)
            item['run'].update(status=status, conclusion='failure')
            with self.subTest(status=status):
                self.assertEqual(self.select(item).returncode, 0)

    def test_reject_untrusted_or_inconsistent_producers(self):
        changes = [
            ('run', 'workflow_id', 999), ('run', 'event', 'pull_request'),
            ('run', 'head_branch', 'feature'), ('run', 'head_repository', {'id': 2}),
            ('run', 'repository', {'id': 2}), ('attempt', 'run_attempt', 2),
            ('attempt', 'status', 'in_progress'), ('attempt', 'head_sha', 'c' * 40),
            ('attempt', 'workflow_id', 102), ('attempt', 'head_branch', 'feature'),
            ('attempt', 'repository', {'id': 2}), ('artifact', 'digest', None),
            ('artifact', 'expired', True), ('artifact', 'size_in_bytes', 2147483649),
            ('artifact', 'name', f'security-snapshot-{PREFIX}42-2'),
        ]
        for part, field, value in changes:
            item = candidate()
            item[part][field] = value
            with self.subTest(part=part, field=field):
                result = self.select(item)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn("can't open file", result.stderr)
        result = self.select(None)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("can't open file", result.stderr)

    def test_workflow_resolution_errors_fail_closed(self):
        workflow = (SCRIPT.parent.parent / '.github/workflows/ci.yml').read_text()
        selection = workflow.split('- name: Select Retained Security Snapshot', 1)[1].split(
            '- name: Download Retained Security Snapshot', 1)[0]
        script = selection.split('script: |\n', 1)[1]
        script = '\n'.join(line[22:] for line in script.splitlines())
        harness = r"""
const AsyncFunction = Object.getPrototypeOf(async function(){}).constructor;
const script = JSON.parse(process.argv[1]);
const scenario = process.argv[2];
const context = {repo: {owner: 'o', repo: 'r'}, payload: {repository: {id: 1, default_branch: 'master'}}};
const github = {rest: {actions: {getWorkflow: async ({workflow_id}) => {
  if (workflow_id === 'ci.yml' && scenario === 'legacy404') throw {status: 404};
  if (workflow_id === 'security-data.yml' && ['404', '403', '500'].includes(scenario)) throw {status: Number(scenario)};
  return {data: {id: workflow_id === 'ci.yml' ? 102 : scenario === 'malformed' ? 'bad' : 101}};
}}}, paginate: async () => []};
const exec = {exec: async () => 0};
new AsyncFunction('require', 'context', 'github', 'exec', script)(require, context, github, exec)
  .then(() => process.exit(0), () => process.exit(1));
"""
        env = dict(self.env, RUNNER_TEMP=str(self.root))
        for scenario, expected in [('404', 0), ('403', 1), ('500', 1), ('legacy404', 1), ('malformed', 1)]:
            with self.subTest(scenario=scenario):
                result = subprocess.run(['node', '-e', harness, json.dumps(script), scenario],
                                        env=env, capture_output=True, text=True)
                self.assertEqual(result.returncode, expected, result.stderr)

    def test_selector_skips_untrusted_before_attempt_lookup(self):
        workflow = (SCRIPT.parent.parent / '.github/workflows/ci.yml').read_text()
        selection = workflow.split('- name: Select Retained Security Snapshot', 1)[1].split(
            '- name: Download Retained Security Snapshot', 1)[0]
        script = '\n'.join(line[22:] for line in selection.split('script: |\n', 1)[1].splitlines())
        harness = r"""
const AsyncFunction = Object.getPrototypeOf(async function(){}).constructor;
const fs = require('node:fs');
const script = JSON.parse(process.argv[1]);
const scenario = process.argv[2];
const context = {repo: {owner: 'o', repo: 'r'}, payload: {repository: {id: 1, default_branch: 'master'}}};
const sha = 'a'.repeat(40);
const run = (id) => ({id, workflow_id: 101, run_attempt: 1, repository: {id: 1},
  head_repository: {id: 1}, head_sha: sha, head_branch: 'master', event: 'schedule'});
const artifact = (id, attempt) => ({name: 'security-snapshot-' + process.env.SNAPSHOT_PREFIX + id + '-' + attempt,
  expired: false, workflow_run: {id, repository_id: 1, head_repository_id: 1, head_sha: sha, head_branch: 'master'}});
const junk = artifact(41, scenario === 'range' ? 2 : 1);
const good = artifact(42, 1);
const junkRun = run(41);
if (scenario === 'pr') junkRun.event = 'pull_request';
if (scenario === 'fork') { junkRun.head_repository = {id: 2}; junk.workflow_run.head_repository_id = 2; }
const calls = [];
const github = {rest: {actions: {
  getWorkflow: async ({workflow_id}) => ({data: {id: workflow_id === 'ci.yml' ? 102 : 101}}),
  getWorkflowRun: async ({run_id}) => ({data: run_id === 41 ? junkRun : run(42)}),
  getWorkflowRunAttempt: async ({run_id, attempt_number}) => {
    calls.push([run_id, attempt_number]);
    if (run_id === 41) throw {status: 404};
    if (['404', '403', '500'].includes(scenario)) throw {status: Number(scenario)};
    return {data: {...run(42), status: 'completed'}};
  }
}}, paginate: async () => ['pr', 'fork', 'range'].includes(scenario) ? [junk, good] : [good]};
const exec = {exec: async (_, args) => {
  const payload = JSON.parse(fs.readFileSync(args[2], 'utf8'));
  if (payload.candidates.length !== 1 || payload.candidates[0].run.id !== 42) throw Error('wrong candidates');
}};
new AsyncFunction('require', 'context', 'github', 'exec', script)(require, context, github, exec)
  .then(() => { if (['404', '403', '500'].includes(scenario) || calls.length !== 1 || calls[0][0] !== 42) process.exit(2); },
        () => { if (!['404', '403', '500'].includes(scenario) || calls.length !== 1 || calls[0][0] !== 42) process.exit(3); });
"""
        env = dict(self.env, RUNNER_TEMP=str(self.root))
        for scenario in ('pr', 'fork', 'range', '404', '403', '500'):
            with self.subTest(scenario=scenario):
                result = subprocess.run(['node', '-e', harness, json.dumps(script), scenario],
                                        env=env, capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_pack_restore_round_trip(self):
        source = self.root / 'source'
        source.mkdir()
        (source / 'odc.mv.db').write_bytes(b'database fixture')
        (source / 'nested').mkdir()
        (source / 'nested' / 'metadata').write_bytes(b'metadata fixture')
        packed = self.root / 'packed'
        env = dict(self.env, SNAPSHOT_KEY=PREFIX + '42-1')
        result = subprocess.run([sys.executable, str(SCRIPT), 'pack', str(source), str(packed)],
                                env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        manifest = json.loads((packed / 'manifest.json').read_text())
        self.assertEqual(manifest['files']['odc.mv.db']['sha256'],
                         hashlib.sha256(b'database fixture').hexdigest())
        archive = self.root / 'valid.zip'
        with zipfile.ZipFile(archive, 'w') as zipped:
            for path in packed.rglob('*'):
                if path.is_file():
                    zipped.write(path, path.relative_to(packed).as_posix())
        env['SNAPSHOT_DIGEST'] = 'sha256:' + hashlib.sha256(archive.read_bytes()).hexdigest()
        restored = self.root / 'restored'
        result = subprocess.run([sys.executable, str(SCRIPT), 'restore', str(archive), str(restored)],
                                env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((restored / 'odc.mv.db').read_bytes(), b'database fixture')
        self.assertEqual((restored / 'nested' / 'metadata').read_bytes(), b'metadata fixture')

    def test_restore_rejects_digest_and_zip_tampering(self):
        archive = self.root / 'snapshot.zip'
        with zipfile.ZipFile(archive, 'w') as zipped:
            zipped.writestr('../escape', 'bad')
        digest = 'sha256:' + hashlib.sha256(archive.read_bytes()).hexdigest()
        env = dict(self.env, SNAPSHOT_KEY=PREFIX + '42-1', SNAPSHOT_DIGEST=digest)
        dest = self.root / 'data'
        args = [sys.executable, str(SCRIPT), 'restore', str(archive), str(dest)]
        result = subprocess.run(args, env=env, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("can't open file", result.stderr)
        env['SNAPSHOT_DIGEST'] = 'sha256:' + '0' * 64
        result = subprocess.run(args, env=env, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("can't open file", result.stderr)
        self.assertFalse(dest.exists())
        self.assertFalse((self.root / 'escape').exists())


if __name__ == '__main__':
    unittest.main()
