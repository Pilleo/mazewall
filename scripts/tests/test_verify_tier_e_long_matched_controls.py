"""Negative acceptance probes against the retained 15-fork long-control rows."""
import json
import pathlib
import subprocess
import sys
import tempfile


ROOT = pathlib.Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / 'docs/internals/evidence/tier-e-long-matched-controls'
VERIFIER = ROOT / 'scripts/verify_tier_e_long_matched_controls.py'


def verify(rows):
    with tempfile.TemporaryDirectory() as directory:
        sample = pathlib.Path(directory) / 'rows.jsonl'
        sample.write_text(''.join(json.dumps(row) + '\n' for row in rows))
        run = subprocess.run(
            [sys.executable, str(VERIFIER), '--jsonl-only', str(sample),
             str(EVIDENCE / 'run-manifest.json'), str(EVIDENCE / 'source-state-before.json'),
             str(EVIDENCE / 'source-state-after.json'), str(EVIDENCE / 'terminal.json')],
            capture_output=True, text=True, check=False,
        )
        return run.returncode, json.loads(run.stdout)


def test_retained_run_passes():
    rows = [json.loads(line) for line in (EVIDENCE / 'fork-results.jsonl').read_text().splitlines()]
    code, result = verify(rows)
    assert code == 0
    assert result['verdict'] == 'PASS'


def test_mutated_inputs_fail_closed():
    original = [json.loads(line) for line in (EVIDENCE / 'fork-results.jsonl').read_text().splitlines()]
    mutations = {
        'missing_arm': lambda rows: rows.pop(),
        'cross_fork_identity': lambda rows: [row.__setitem__('source_digest', 'different') for row in rows if row['fork'] == 1],
        'missing_identity': lambda rows: [row.__setitem__('image_id', None) for row in rows],
        'unexpected_arm': lambda rows: rows.append({**rows[0], 'arm': 'unexpected-arm'}),
        'artifact_error': lambda rows: rows[0].__setitem__('artifact_errors', ['oracle: corrupt']),
        'timeout': lambda rows: rows[0].__setitem__('timed_out', True),
        'source_manifest_drift': lambda rows: [row.__setitem__('native_so_sha256', 'drift') for row in rows],
    }
    for name, mutate in mutations.items():
        rows = [dict(row) for row in original]
        mutate(rows)
        code, result = verify(rows)
        assert code != 0, name
        assert result['verdict'] == 'INCOMPLETE', name
        assert result['errors'], name
