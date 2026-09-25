"""Negative acceptance probes against the retained 15-fork long-control rows."""
import json
import pathlib
import subprocess
import sys
import tempfile

import pytest


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


def _verify_custom(rows, manifest=None, before=None, after=None, terminal=None):
    import tempfile
    base = EVIDENCE
    manifest = manifest if manifest is not None else json.loads((base / 'run-manifest.json').read_text())
    before = before if before is not None else json.loads((base / 'source-state-before.json').read_text())
    after = after if after is not None else json.loads((base / 'source-state-after.json').read_text())
    terminal = terminal if terminal is not None else json.loads((base / 'terminal.json').read_text())
    with tempfile.TemporaryDirectory() as directory:
        tmp = pathlib.Path(directory)
        sample = tmp / 'rows.jsonl'
        sample.write_text(''.join(json.dumps(row, allow_nan=True) + '\n' for row in rows))
        manifest_path = tmp / 'manifest.json'
        manifest_path.write_text(json.dumps(manifest))
        before_path = tmp / 'before.json'
        before_path.write_text(json.dumps(before))
        after_path = tmp / 'after.json'
        after_path.write_text(json.dumps(after))
        terminal_path = tmp / 'terminal.json'
        terminal_path.write_text(json.dumps(terminal))
        run = subprocess.run(
            [sys.executable, str(VERIFIER), '--jsonl-only', str(sample), str(manifest_path),
             str(before_path), str(after_path), str(terminal_path)],
            capture_output=True, text=True, check=False,
        )
        return run.returncode, json.loads(run.stdout)


def _retained_documents():
    rows = [json.loads(line) for line in (EVIDENCE / 'fork-results.jsonl').read_text().splitlines()]
    manifest = json.loads((EVIDENCE / 'run-manifest.json').read_text())
    before = json.loads((EVIDENCE / 'source-state-before.json').read_text())
    after = json.loads((EVIDENCE / 'source-state-after.json').read_text())
    terminal = json.loads((EVIDENCE / 'terminal.json').read_text())
    return rows, manifest, before, after, terminal


@pytest.mark.parametrize('fork_value', [None, [], {}, '1', True, 1.0, 0, 16])
def test_invalid_fork_returns_structured_incomplete(fork_value):
    rows, manifest, before, after, terminal = _retained_documents()
    rows[0]['fork'] = fork_value
    code, result = _verify_custom(rows, manifest, before, after, terminal)
    assert code == 1
    assert result['verdict'] == 'INCOMPLETE'
    assert result['verification_mode'] == 'jsonl-only'
    assert any('fork' in error for error in result['errors'])


@pytest.mark.parametrize('arm_value', [None, [], {}, 1, 'unknown-arm'])
def test_invalid_arm_returns_structured_incomplete(arm_value):
    rows, manifest, before, after, terminal = _retained_documents()
    rows[0]['arm'] = arm_value
    code, result = _verify_custom(rows, manifest, before, after, terminal)
    assert code == 1
    assert result['verdict'] == 'INCOMPLETE'
    assert any('arm' in error for error in result['errors'])


def test_oversized_cpu_returns_structured_incomplete():
    rows, manifest, before, after, terminal = _retained_documents()
    rows[0]['target_cpu_ns'] = 10 ** 400
    code, result = _verify_custom(rows, manifest, before, after, terminal)
    assert code == 1
    assert result['verdict'] == 'INCOMPLETE'
    assert any('target_cpu' in error for error in result['errors'])


@pytest.mark.parametrize('bad', ['absent', None, False, '', {}, ['oracle corrupt']])
def test_artifact_errors_must_be_an_empty_list(bad):
    rows, manifest, before, after, terminal = _retained_documents()
    if bad == 'absent':
        rows[0].pop('artifact_errors')
    else:
        rows[0]['artifact_errors'] = bad
    code, result = _verify_custom(rows, manifest, before, after, terminal)
    assert code == 1
    assert result['verdict'] == 'INCOMPLETE'
    assert any('artifact_errors' in error for error in result['errors'])


def test_split_source_provenance_rejected():
    rows, manifest, before, after, terminal = _retained_documents()
    before = dict(before, source_digest='unrelated-source')
    after = dict(after, source_digest='unrelated-source')
    code, result = _verify_custom(rows, manifest, before, after, terminal)
    assert code != 0
    assert result['verdict'] == 'INCOMPLETE'
    assert any('source_provenance_unbound' in error for error in result['errors'])


def test_missing_source_digest_rejected():
    rows, manifest, before, after, terminal = _retained_documents()
    before = {key: value for key, value in before.items() if key != 'source_digest'}
    after = {key: value for key, value in after.items() if key != 'source_digest'}
    code, result = _verify_custom(rows, manifest, before, after, terminal)
    assert code != 0
    assert result['verdict'] == 'INCOMPLETE'
    assert any('source_provenance_unbound' in error for error in result['errors'])


def test_source_revision_change_rejected():
    rows, manifest, before, after, terminal = _retained_documents()
    after = dict(after, revision='different-revision')
    code, result = _verify_custom(rows, manifest, before, after, terminal)
    assert code != 0
    assert result['verdict'] == 'INCOMPLETE'
    assert any('revision' in error for error in result['errors'])


def test_source_file_inventory_change_rejected():
    rows, manifest, before, after, terminal = _retained_documents()
    files = dict(after['files'])
    first = next(iter(files))
    files[first] = dict(files[first], sha256='0' * 64)
    after = dict(after, files=files)
    code, result = _verify_custom(rows, manifest, before, after, terminal)
    assert code != 0
    assert result['verdict'] == 'INCOMPLETE'
    assert any('source_files' in error for error in result['errors'])


def test_empty_identity_rejected():
    rows, manifest, before, after, terminal = _retained_documents()
    rows = [dict(row, source_digest='') for row in rows]
    manifest = dict(manifest, source_digest='')
    before = dict(before, source_digest='')
    after = dict(after, source_digest='')
    code, result = _verify_custom(rows, manifest, before, after, terminal)
    assert code != 0
    assert result['verdict'] == 'INCOMPLETE'
    assert result['errors']


def _write_run_tree(root, rows, manifest, before, after, terminal):
    import tempfile
    tree = pathlib.Path(tempfile.mkdtemp(prefix='tier-e-run-tree-'))
    for row in rows:
        arm_dir = tree / row['arm'] / f"fork-{row['fork']}"
        arm_dir.mkdir(parents=True)
        host_pid = 5000 + row['fork']
        starttime = 9100000 + row['fork']
        (arm_dir / 'arm-result.json').write_text(json.dumps({
            'schema_version': 1, 'fork': row['fork'], 'arm': row['arm'],
            'status': 'COMPLETE', 'fixture_exit': 0, 'timed_out': False,
            'seed': row['seed'], 'policy_eligibility': False,
        }))
        (arm_dir / 'coverage-oracle.json').write_text(json.dumps({
            'schema_version': 1, 'seed': row['seed'], 'declared_operations': 75000,
            'paths': [
                {
                    'path': i,
                    'category': 'common' if i < 24 else 'infrequent' if i < 48 else 'singleton',
                    'frequency': 3000 if i < 24 else 124 if i < 48 else 1,
                    'method': f'path{i:02d}',
                    'syscall_number': 257,
                }
                for i in range(72)
            ],
        }))
        (arm_dir / 'target-cpu.json').write_text(json.dumps({
            'schema_version': 1, 'version': 1, 'pid': 1, 'host_pid': host_pid,
            'starttime': starttime, 'sample_boundary': 'after_workload_before_exit',
            'unit': 'ns', 'target_cpu_ns': row['target_cpu_ns'],
        }))
        if row['arm'] == 'baseline':
            (arm_dir / 'summary.json').write_text(json.dumps({
                'schema_version': 1, 'mode': 'baseline', 'target_exit': 0,
                'policy_eligibility': False,
            }))
        else:
            coverage = {
                'coverage_verdict': 'PASS', 'admission_errors': [], 'expected_edges': 72,
                'recovered_edges': 72, 'missing_paths': [], 'wrong_edges': [],
                'policy_eligibility': False,
            }
            terminal_report = {
                'schema_version': 1, 'registration_id': 'split-fixture', 'session_epoch': 19802,
                'target_pid': host_pid, 'process_incarnation': starttime,
                'evidence_integrity': 'COMPLETE', 'terminal_reason': 'COMPLETE',
                'policy_eligibility': False,
            }
            (arm_dir / 'coverage-verdict.json').write_text(json.dumps(coverage))
            (arm_dir / 'terminal-report.json').write_text(json.dumps(terminal_report))
            (arm_dir / 'collector-process-metrics.json').write_text(json.dumps({
                'schema_version': 1, 'collector_host_pid': 6000 + row['fork'],
                'cpu_ticks': 100 + row['fork'], 'cpu_ns': row['collector_cpu_ns'],
                'peak_rss_kb': 100, 'unit': 'ns',
                'cpu_time_basis': 'process_ticks_since_start',
                'sample_boundary': 'after_terminal_report_before_shutdown',
                'collector_starttime': 9200000 + row['fork'],
            }))
            (arm_dir / 'summary.json').write_text(json.dumps({
                'coverage': coverage, 'terminal': terminal_report, 'policy_eligibility': False,
            }))
    (tree / 'fork-results.jsonl').write_text(''.join(json.dumps(row) + '\n' for row in rows))
    (tree / 'run-manifest.json').write_text(json.dumps(manifest))
    (tree / 'source-state-before.json').write_text(json.dumps(before))
    (tree / 'source-state-after.json').write_text(json.dumps(after))
    (tree / 'terminal.json').write_text(json.dumps(terminal))
    return tree


def _verify_tree(tree):
    run = subprocess.run(
        [sys.executable, str(VERIFIER), str(tree)],
        capture_output=True, text=True, check=False,
    )
    return run.returncode, json.loads(run.stdout)


def test_full_directory_run_tree_passes():
    import shutil
    rows, manifest, before, after, terminal = _retained_documents()
    tree = _write_run_tree(None, rows, manifest, before, after, terminal)
    try:
        code, result = _verify_tree(tree)
        assert code == 0, result.get('errors')
        assert result['verdict'] == 'PASS'
        assert result['verification_mode'] == 'full-directory'
        assert result['dir_checks']['arm_result_records'] == 105
        assert result['dir_checks']['profiled_terminal_reports'] == 90
        assert result['dir_checks']['baseline_summaries'] == 15
        assert result['dir_checks']['profiled_summaries'] == 90
    finally:
        shutil.rmtree(tree, ignore_errors=True)


def _mutated_tree(base_tree):
    import shutil
    clone = pathlib.Path(base_tree).parent / (pathlib.Path(base_tree).name + '-mut')
    if clone.exists():
        shutil.rmtree(clone, ignore_errors=True)
    shutil.copytree(base_tree, clone)
    return clone


def _rewrite_json(path, updates):
    data = json.loads(path.read_text())
    data.update(updates)
    path.write_text(json.dumps(data))


def _oracle_mutation(oracle, name):
    paths = oracle['paths']
    if name == 'null_entries':
        oracle['paths'] = [None] * 72
    elif name == 'duplicate_entries':
        oracle['paths'] = [paths[0]] * 72
    elif name == 'missing_expected_path':
        paths[-1]['path'] = 72
    elif name == 'nonobject_entry':
        paths[0] = []
    elif name == 'wrong_method':
        paths[0]['method'] = 'path71'
    elif name == 'wrong_category':
        paths[0]['category'] = 'singleton'
    elif name == 'wrong_syscall':
        paths[0]['syscall_number'] = 999
    elif name == 'missing_syscall':
        paths[0].pop('syscall_number')
    elif name == 'zero_frequency':
        paths[0]['frequency'] = 0
    elif name == 'negative_frequency':
        paths[0]['frequency'] = -1
    elif name == 'boolean_frequency':
        paths[0]['frequency'] = True
    elif name == 'string_frequency':
        paths[0]['frequency'] = '3000'
    elif name == 'wrong_frequency_total':
        paths[0]['frequency'] = 3001
    elif name == 'wrong_singleton_frequency':
        paths[48]['frequency'] = 2
    else:
        raise AssertionError(name)


@pytest.mark.parametrize('name', [
    'null_entries', 'duplicate_entries', 'missing_expected_path', 'nonobject_entry',
    'wrong_method', 'wrong_category', 'wrong_syscall', 'missing_syscall',
    'zero_frequency', 'negative_frequency', 'boolean_frequency', 'string_frequency',
    'wrong_frequency_total', 'wrong_singleton_frequency',
])
def test_invalid_oracle_inventory_is_rejected(name):
    import shutil
    rows, manifest, before, after, terminal = _retained_documents()
    tree = _write_run_tree(None, rows, manifest, before, after, terminal)
    try:
        oracle_path = tree / 'baseline' / 'fork-1' / 'coverage-oracle.json'
        oracle = json.loads(oracle_path.read_text())
        _oracle_mutation(oracle, name)
        oracle_path.write_text(json.dumps(oracle, allow_nan=True))
        code, result = _verify_tree(tree)
        assert code == 1, name
        assert result['verdict'] == 'INCOMPLETE', name
        assert any('oracle_mismatch:' in error for error in result['errors']), (name, result['errors'])
    finally:
        shutil.rmtree(tree, ignore_errors=True)


def test_valid_oracle_order_is_not_significant():
    import shutil
    rows, manifest, before, after, terminal = _retained_documents()
    tree = _write_run_tree(None, rows, manifest, before, after, terminal)
    try:
        oracle_path = tree / 'baseline' / 'fork-1' / 'coverage-oracle.json'
        oracle = json.loads(oracle_path.read_text())
        oracle['paths'].reverse()
        oracle_path.write_text(json.dumps(oracle))
        code, result = _verify_tree(tree)
        assert code == 0, result.get('errors')
    finally:
        shutil.rmtree(tree, ignore_errors=True)


def _swap_files(first, second):
    a, b = first.read_text(), second.read_text()
    first.write_text(b)
    second.write_text(a)


MUTATION_NAMES = [
    'missing_arm_result',
    'corrupt_terminal',
    'empty_summary',
    'incomplete_arm_result',
    'wrong_arm_in_result',
    'terminal_disagrees_with_row',
    'standalone_coverage_disagrees',
    'cpu_disagrees_with_row',
    'swapped_target_cpu',
    'collector_in_baseline',
    'renamed_directory',
]


def test_full_directory_mutations_fail_closed():
    import shutil
    rows, manifest, before, after, terminal = _retained_documents()
    pristine = _write_run_tree(None, rows, manifest, before, after, terminal)
    try:
        for name in MUTATION_NAMES:
            tree = _mutated_tree(pristine)
            try:
                _apply_mutation_on_tree(tree, name)
                code, result = _verify_tree(tree)
                assert code != 0, name
                assert result['verdict'] == 'INCOMPLETE', name
                assert result['errors'], name
            finally:
                shutil.rmtree(tree, ignore_errors=True)
    finally:
        shutil.rmtree(pristine, ignore_errors=True)


def _apply_mutation_on_tree(tree, name):
    first_profiled = tree / 'dictionary-full-stream-v1' / 'fork-1'
    first_baseline = tree / 'baseline' / 'fork-1'
    if name == 'missing_arm_result':
        (first_profiled / 'arm-result.json').unlink()
    elif name == 'corrupt_terminal':
        (first_profiled / 'terminal-report.json').write_text('{broken')
    elif name == 'empty_summary':
        (first_profiled / 'summary.json').write_text('{}')
    elif name == 'incomplete_arm_result':
        (first_profiled / 'arm-result.json').write_text(json.dumps({
            'schema_version': 1, 'fork': 1, 'arm': 'dictionary-full-stream-v1',
            'status': 'INCOMPLETE', 'fixture_exit': 1, 'timed_out': False,
            'seed': 23063, 'policy_eligibility': False}))
    elif name == 'wrong_arm_in_result':
        (first_profiled / 'arm-result.json').write_text(json.dumps({
            'schema_version': 1, 'fork': 1, 'arm': 'dictionary-full-stream-v2',
            'status': 'COMPLETE', 'fixture_exit': 0, 'timed_out': False,
            'seed': 23063, 'policy_eligibility': False}))
    elif name == 'terminal_disagrees_with_row':
        _rewrite_json(first_profiled / 'terminal-report.json', {'evidence_integrity': 'INCOMPLETE'})
    elif name == 'standalone_coverage_disagrees':
        _rewrite_json(first_profiled / 'coverage-verdict.json', {'recovered_edges': 71})
    elif name == 'cpu_disagrees_with_row':
        _rewrite_json(first_profiled / 'target-cpu.json', {'target_cpu_ns': 1})
    elif name == 'swapped_target_cpu':
        _swap_files(first_profiled / 'target-cpu.json',
                     tree / 'dictionary-full-stream-v1' / 'fork-2' / 'target-cpu.json')
    elif name == 'collector_in_baseline':
        (first_baseline / 'collector-process-metrics.json').write_text(
            (first_profiled / 'collector-process-metrics.json').read_text())
    elif name == 'renamed_directory':
        import shutil
        shutil.rmtree(tree / 'baseline' / 'fork-15')
        (tree / 'unexpected-arm' / 'fork-1').mkdir(parents=True)
        (tree / 'unexpected-arm' / 'fork-1' / 'arm-result.json').write_text('{}')
    else:
        raise AssertionError(name)


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
