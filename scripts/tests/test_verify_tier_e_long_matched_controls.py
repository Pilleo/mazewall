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
    assert result['verification_mode'] == 'jsonl-only'


CPU_FIELD_CASES = [
    ('nan_target', 'target_cpu_ns', float('nan')),
    ('inf_target', 'target_cpu_ns', float('inf')),
    ('neg_inf_target', 'target_cpu_ns', float('-inf')),
    ('zero_target', 'target_cpu_ns', 0),
    ('negative_target', 'target_cpu_ns', -5),
    ('null_target', 'target_cpu_ns', None),
    ('string_target', 'target_cpu_ns', '6770000000'),
    ('bool_target', 'target_cpu_ns', True),
    ('list_target', 'target_cpu_ns', [6770000000]),
    ('dict_target', 'target_cpu_ns', {'ns': 6770000000}),
    ('nan_collector', 'collector_cpu_ns', float('nan')),
    ('inf_collector', 'collector_cpu_ns', float('inf')),
    ('zero_collector', 'collector_cpu_ns', 0),
    ('negative_collector', 'collector_cpu_ns', -1),
    ('string_collector', 'collector_cpu_ns', '62550000000'),
    ('bool_collector', 'collector_cpu_ns', False),
]


def test_malformed_cpu_values_fail_closed():
    import tempfile
    original = [json.loads(line) for line in (EVIDENCE / 'fork-results.jsonl').read_text().splitlines()]
    profiled = next(row for row in original if row['arm'] != 'baseline')
    for name, field, bad in CPU_FIELD_CASES:
        rows = [dict(row) for row in original]
        target = next(row for row in rows if row['fork'] == profiled['fork'] and row['arm'] == profiled['arm'])
        target[field] = bad
        with tempfile.TemporaryDirectory() as directory:
            sample = pathlib.Path(directory) / 'rows.jsonl'
            sample.write_text(''.join(json.dumps(row, allow_nan=True) + '\n' for row in rows))
            run = subprocess.run(
                [sys.executable, str(VERIFIER), '--jsonl-only', str(sample),
                 str(EVIDENCE / 'run-manifest.json'), str(EVIDENCE / 'source-state-before.json'),
                 str(EVIDENCE / 'source-state-after.json'), str(EVIDENCE / 'terminal.json')],
                capture_output=True, text=True, check=False,
            )
            assert run.returncode != 0, name
            result = json.loads(run.stdout)
            assert result['verdict'] == 'INCOMPLETE', name
            assert result['errors'], name
            assert 'Traceback' not in run.stderr, name


def test_malformed_top_level_inputs_fail_closed():
    import tempfile
    original = [json.loads(line) for line in (EVIDENCE / 'fork-results.jsonl').read_text().splitlines()]
    good_text = ''.join(json.dumps(row) + '\n' for row in original)
    with tempfile.TemporaryDirectory() as directory:
        tmp = pathlib.Path(directory)
        bad_manifest = tmp / 'manifest.json'
        bad_manifest.write_text('[]')
        sample = tmp / 'rows.jsonl'
        sample.write_text(good_text)
        run = subprocess.run(
            [sys.executable, str(VERIFIER), '--jsonl-only', str(sample), str(bad_manifest),
             str(EVIDENCE / 'source-state-before.json'),
             str(EVIDENCE / 'source-state-after.json'), str(EVIDENCE / 'terminal.json')],
            capture_output=True, text=True, check=False,
        )
        assert run.returncode != 0
        assert json.loads(run.stdout)['verdict'] == 'INCOMPLETE'

        null_row = tmp / 'null.jsonl'
        null_row.write_text('null\n')
        run = subprocess.run(
            [sys.executable, str(VERIFIER), '--jsonl-only', str(null_row),
             str(EVIDENCE / 'run-manifest.json'), str(EVIDENCE / 'source-state-before.json'),
             str(EVIDENCE / 'source-state-after.json'), str(EVIDENCE / 'terminal.json')],
            capture_output=True, text=True, check=False,
        )
        assert run.returncode != 0
        assert json.loads(run.stdout)['verdict'] == 'INCOMPLETE'

        array_row = tmp / 'array.jsonl'
        array_row.write_text('[]\n')
        run = subprocess.run(
            [sys.executable, str(VERIFIER), '--jsonl-only', str(array_row),
             str(EVIDENCE / 'run-manifest.json'), str(EVIDENCE / 'source-state-before.json'),
             str(EVIDENCE / 'source-state-after.json'), str(EVIDENCE / 'terminal.json')],
            capture_output=True, text=True, check=False,
        )
        assert run.returncode != 0
        assert json.loads(run.stdout)['verdict'] == 'INCOMPLETE'

        corrupt = tmp / 'corrupt.jsonl'
        corrupt.write_text('{not json\n')
        run = subprocess.run(
            [sys.executable, str(VERIFIER), '--jsonl-only', str(corrupt),
             str(EVIDENCE / 'run-manifest.json'), str(EVIDENCE / 'source-state-before.json'),
             str(EVIDENCE / 'source-state-after.json'), str(EVIDENCE / 'terminal.json')],
            capture_output=True, text=True, check=False,
        )
        assert run.returncode != 0
        assert json.loads(run.stdout)['verdict'] == 'INCOMPLETE'


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
