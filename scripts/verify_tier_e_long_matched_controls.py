#!/usr/bin/env python3
"""Independent verifier for the Tier E 15-fork seven-arm long-matched matrix run.

Deliberately implements the acceptance contract directly in Python stdlib
(never reusing the JavaScript row builder or estimator) so task-9 evidence is
cross-checked by independent code. Exits 0 only on a complete run; any missing
arm, identity drift, CPU/coverage/terminal violation, or source drift yields an
explicit INCOMPLETE verdict and nonzero exit.

Usage:
  verify_tier_e_long_matched_controls.py <decision-dir>
  verify_tier_e_long_matched_controls.py --jsonl-only <fork-results.jsonl> <run-manifest.json> <source-before.json> <source-after.json> <terminal.json>
"""
import json
import math
import os
import sys

BASELINE = 'baseline'
PROFILED_ARMS = [
    'dictionary-full-stream-v1',
    'dictionary-full-stream-v2',
    'dictionary-full-stream-hybrid',
    'dictionary-unique-edge-v1',
    'dictionary-unique-edge-v2',
    'dictionary-unique-edge-hybrid',
]
ALL_ARMS = [BASELINE] + PROFILED_ARMS
FORKS = 15
DECLARED_OPERATIONS = 75000
TARGET_BOUNDARY = 'after_workload_before_exit'
COLLECTOR_BOUNDARY = 'after_terminal_report_before_shutdown'
MAX_SAFE_INTEGER = (1 << 53) - 1


def _valid_oracle_paths(paths):
    if not isinstance(paths, list) or len(paths) != 72:
        return False
    observed = set()
    total = 0
    for entry in paths:
        if not isinstance(entry, dict):
            return False
        index = entry.get('path')
        if type(index) is not int or index not in range(72) or index in observed:
            return False
        observed.add(index)
        category = 'common' if index < 24 else 'infrequent' if index < 48 else 'singleton'
        frequency = 3000 if index < 24 else 124 if index < 48 else 1
        if (
            entry.get('category') != category
            or entry.get('method') != f'path{index:02d}'
            or type(entry.get('syscall_number')) is not int
            or entry['syscall_number'] != 257
            or type(entry.get('frequency')) is not int
            or entry['frequency'] != frequency
        ):
            return False
        total += entry['frequency']
    return observed == set(range(72)) and total == DECLARED_OPERATIONS


def _read_artifact(fork_dir, arm, fork, filename, errors):
    rel = f'{arm}/fork-{fork}/{filename}'
    try:
        with open(os.path.join(fork_dir, filename), encoding='utf-8') as handle:
            value = json.load(handle, parse_constant=_reject_nonstandard_constant)
    except FileNotFoundError:
        errors.append(f'missing_artifact: {rel}')
        return None
    except (OSError, ValueError) as error:
        errors.append(f'unreadable_artifact: {rel}: {error}')
        return None
    if not isinstance(value, dict):
        errors.append(f'artifact_not_object: {rel}')
        return None
    return value


def _verify_fork_dir(arm, fork, fork_dir, row, errors, counters):
    rel = lambda filename: f'{arm}/fork-{fork}/{filename}'
    expected_seed = 23063 + fork - 1
    baseline = arm == BASELINE
    if row is None:
        errors.append(f'missing_row_for_artifacts: {arm}/fork-{fork}')
        return

    result = _read_artifact(fork_dir, arm, fork, 'arm-result.json', errors)
    if result is not None:
        if (
            result.get('schema_version') == 1
            and result.get('fork') == fork
            and result.get('arm') == arm
            and result.get('status') == 'COMPLETE'
            and result.get('fixture_exit') == 0
            and result.get('timed_out') is False
            and result.get('seed') == expected_seed
            and result.get('policy_eligibility') is False
        ):
            counters['arm_result_records'] += 1
        else:
            errors.append(f'arm_result_mismatch: {rel("arm-result.json")}')

    oracle = _read_artifact(fork_dir, arm, fork, 'coverage-oracle.json', errors)
    if oracle is not None:
        paths = oracle.get('paths')
        if (
            oracle.get('seed') != expected_seed
            or oracle.get('declared_operations') != DECLARED_OPERATIONS
            or not _valid_oracle_paths(paths)
        ):
            errors.append(f'oracle_mismatch: {rel("coverage-oracle.json")}')
        if row.get('seed') != oracle.get('seed') or row.get('declared_operations') != oracle.get(
            'declared_operations'
        ):
            errors.append(f'oracle_row_mismatch: {rel("coverage-oracle.json")}')

    target = _read_artifact(fork_dir, arm, fork, 'target-cpu.json', errors)
    target_ok = False
    if target is not None:
        target_ok = (
            target.get('schema_version') == 1
            and target.get('version') == 1
            and _positive_finite(target.get('target_cpu_ns'))
            and target.get('unit') == 'ns'
            and target.get('sample_boundary') == TARGET_BOUNDARY
            and type(target.get('host_pid')) is int
            and target.get('host_pid') > 0
            and type(target.get('starttime')) is int
            and target.get('starttime') > 0
        )
        if not target_ok:
            errors.append(f'target_cpu_mismatch: {rel("target-cpu.json")}')
        if row.get('target_cpu_ns') != target.get('target_cpu_ns') or row.get(
            'target_sample_boundary'
        ) != target.get('sample_boundary'):
            errors.append(f'target_cpu_row_mismatch: {rel("target-cpu.json")}')

    if baseline:
        for forbidden in ('terminal-report.json', 'collector-process-metrics.json'):
            if os.path.exists(os.path.join(fork_dir, forbidden)):
                errors.append(f'baseline_has_profiled_artifact: {rel(forbidden)}')
        summary = _read_artifact(fork_dir, arm, fork, 'summary.json', errors)
        if summary is not None:
            if (
                summary.get('mode') == 'baseline'
                and summary.get('target_exit') == 0
                and summary.get('policy_eligibility') is False
            ):
                counters['baseline_summaries'] += 1
            else:
                errors.append(f'baseline_summary_mismatch: {rel("summary.json")}')
        return

    coverage = _read_artifact(fork_dir, arm, fork, 'coverage-verdict.json', errors)
    if coverage is not None:
        if not (
            coverage.get('coverage_verdict') == 'PASS'
            and coverage.get('expected_edges') == 72
            and coverage.get('recovered_edges') == 72
            and coverage.get('admission_errors') == []
            and coverage.get('missing_paths') == []
            and coverage.get('wrong_edges') == []
            and coverage.get('policy_eligibility') is False
        ):
            errors.append(f'coverage_mismatch: {rel("coverage-verdict.json")}')
        if row.get('coverage_verdict') != coverage.get('coverage_verdict') or row.get(
            'covered_paths'
        ) != coverage.get('recovered_edges'):
            errors.append(f'coverage_row_mismatch: {rel("coverage-verdict.json")}')

    terminal = _read_artifact(fork_dir, arm, fork, 'terminal-report.json', errors)
    terminal_ok = False
    if terminal is not None:
        terminal_ok = (
            terminal.get('evidence_integrity') == 'COMPLETE'
            and terminal.get('terminal_reason') == 'COMPLETE'
            and terminal.get('policy_eligibility') is False
            and isinstance(terminal.get('registration_id'), str)
            and terminal.get('registration_id') != ''
            and type(terminal.get('session_epoch')) is int
            and type(terminal.get('target_pid')) is int
            and terminal.get('target_pid') > 0
            and type(terminal.get('process_incarnation')) is int
            and terminal.get('process_incarnation') > 0
        )
        if not terminal_ok:
            errors.append(f'terminal_mismatch: {rel("terminal-report.json")}')
        if row.get('terminal_integrity') != terminal.get('evidence_integrity'):
            errors.append(f'terminal_row_mismatch: {rel("terminal-report.json")}')
        if terminal_ok:
            counters['profiled_terminal_reports'] += 1

    if target_ok and terminal_ok:
        if target.get('host_pid') != terminal.get('target_pid') or target.get(
            'starttime'
        ) != terminal.get('process_incarnation'):
            errors.append(
                f'pid_incarnation_mismatch: {rel("target-cpu.json")} vs {rel("terminal-report.json")}'
            )

    collector = _read_artifact(fork_dir, arm, fork, 'collector-process-metrics.json', errors)
    if collector is not None:
        if not (
            _positive_finite(collector.get('cpu_ns'))
            and _positive_finite(collector.get('cpu_ticks'))
            and collector.get('unit') == 'ns'
            and collector.get('cpu_time_basis') == 'process_ticks_since_start'
            and collector.get('sample_boundary') == COLLECTOR_BOUNDARY
        ):
            errors.append(f'collector_cpu_mismatch: {rel("collector-process-metrics.json")}')
        if row.get('collector_cpu_ns') != collector.get('cpu_ns') or row.get(
            'collector_sample_boundary'
        ) != collector.get('sample_boundary'):
            errors.append(f'collector_cpu_row_mismatch: {rel("collector-process-metrics.json")}')

    summary = _read_artifact(fork_dir, arm, fork, 'summary.json', errors)
    if summary is not None:
        if (
            isinstance(summary.get('coverage'), dict)
            and isinstance(summary.get('terminal'), dict)
            and summary.get('policy_eligibility') is False
            and (coverage is None or summary.get('coverage') == coverage)
            and (terminal is None or summary.get('terminal') == terminal)
        ):
            counters['profiled_summaries'] += 1
        else:
            errors.append(f'summary_mismatch: {rel("summary.json")}')


def _verify_run_tree(decision_dir, rows, errors, counters):
    row_by_key = {}
    for row in rows:
        if isinstance(row, dict):
            row_by_key[(row.get('arm'), row.get('fork'))] = row
    try:
        top_entries = sorted(os.listdir(decision_dir))
    except OSError as error:
        errors.append(f'decision_dir_unreadable: {error}')
        return
    for name in top_entries:
        full = os.path.join(decision_dir, name)
        if not os.path.isdir(full):
            continue
        if name not in ALL_ARMS:
            errors.append(f'unexpected_arm_dir: {name}')
            continue
        try:
            fork_entries = sorted(os.listdir(full))
        except OSError as error:
            errors.append(f'arm_dir_unreadable: {name}: {error}')
            continue
        for fork_name in fork_entries:
            fork_full = os.path.join(full, fork_name)
            if not os.path.isdir(fork_full):
                continue
            fork = None
            if fork_name.startswith('fork-'):
                try:
                    fork = int(fork_name.split('-', 1)[1])
                except ValueError:
                    fork = None
            if fork is None or fork < 1 or fork > FORKS:
                errors.append(f'unexpected_fork_dir: {name}/{fork_name}')
                continue
            _verify_fork_dir(name, fork, fork_full, row_by_key.get((name, fork)), errors, counters)
    for arm in ALL_ARMS:
        for fork in range(1, FORKS + 1):
            if not os.path.isdir(os.path.join(decision_dir, arm, f'fork-{fork}')):
                errors.append(f'missing_arm_dir: {arm}/fork-{fork}')


def fail(errors, context):
    verdict = {
        'schema_version': 1,
        'verdict': 'INCOMPLETE',
        'verification_mode': 'full-directory' if (context or {}).get('decision_dir') else 'jsonl-only',
        'errors': errors,
        'context': context,
        'policy_eligibility': False,
    }
    print(json.dumps(verdict, indent=2, sort_keys=True))
    return 1


def _reject_nonstandard_constant(value):
    raise ValueError(f'nonstandard_json_constant: {value}')


def _positive_finite(value):
    if type(value) is int:
        return 0 < value <= MAX_SAFE_INTEGER
    return type(value) is float and math.isfinite(value) and 0 < value <= MAX_SAFE_INTEGER


def _freeze(value):
    try:
        hash(value)
        return value
    except TypeError:
        return repr(value)


def main(argv):
    if argv and argv[0] == '--jsonl-only':
        if len(argv) != 6:
            print('usage: --jsonl-only jsonl manifest before after terminal', file=sys.stderr)
            return 2
        jsonl_path, manifest_path, before_path, after_path, terminal_path = argv[1:]
        decision_dir = None
    else:
        if len(argv) != 1:
            print(__doc__, file=sys.stderr)
            return 2
        decision_dir = argv[0]
        jsonl_path = os.path.join(decision_dir, 'fork-results.jsonl')
        manifest_path = os.path.join(decision_dir, 'run-manifest.json')
        before_path = os.path.join(decision_dir, 'source-state-before.json')
        after_path = os.path.join(decision_dir, 'source-state-after.json')
        terminal_path = os.path.join(decision_dir, 'terminal.json')

    verification_mode = 'full-directory' if decision_dir else 'jsonl-only'
    errors = []
    rows = []
    try:
        with open(jsonl_path, encoding='utf-8') as handle:
            for line in handle:
                line = line.strip()
                if line:
                    rows.append(json.loads(line, parse_constant=_reject_nonstandard_constant))
    except (OSError, ValueError) as error:
        return fail([f'jsonl_unreadable: {error}'],
                    {'jsonl': jsonl_path, 'decision_dir': decision_dir})

    def load(path, label):
        try:
            with open(path, encoding='utf-8') as handle:
                value = json.load(handle, parse_constant=_reject_nonstandard_constant)
        except (OSError, ValueError) as error:
            errors.append(f'{label}_unreadable: {error}')
            return None
        if not isinstance(value, dict):
            errors.append(f'{label}_not_object')
            return None
        return value

    manifest = load(manifest_path, 'manifest')
    before = load(before_path, 'source_before')
    after = load(after_path, 'source_after')
    terminal = load(terminal_path, 'terminal')

    if terminal is not None:
        if terminal.get('success') is not True:
            errors.append(f"terminal_not_success: {terminal.get('success')}")
        if terminal.get('source_state_unchanged') is not True:
            errors.append('terminal_source_state_not_unchanged')
        if terminal.get('binaries_unchanged') is not True:
            errors.append('terminal_binaries_not_unchanged')
        if terminal.get('policy_eligibility') is not False:
            errors.append(f"terminal_policy_eligibility: {terminal.get('policy_eligibility')}")
        if terminal.get('forks') != FORKS or terminal.get('arms') != FORKS * 7:
            errors.append(f"terminal_counts: forks={terminal.get('forks')} arms={terminal.get('arms')}")

    if before is not None and after is not None:
        if before.get('source_digest') != after.get('source_digest'):
            errors.append('source_digest_drift')
        if after.get('unchanged') is not True:
            errors.append('source_state_unchanged_false')
        for label, state in (('before', before), ('after', after)):
            if type(state.get('input_dirty_count')) is not int or state.get('input_dirty_count') != 0:
                errors.append(f"input_dirty_count_not_zero: {label}={state.get('input_dirty_count')!r}")
        if not before.get('revision') or not after.get('revision'):
            errors.append('source_revision_missing')
        elif before.get('revision') != after.get('revision'):
            errors.append(
                f"source_revision_mismatch: before={before.get('revision')} after={after.get('revision')}"
            )
        before_files = before.get('files')
        after_files = after.get('files')
        if not isinstance(before_files, dict) or not isinstance(after_files, dict):
            errors.append('source_files_invalid')
        elif before_files != after_files:
            errors.append('source_files_drift')
        elif before.get('source_file_count') != len(before_files) or after.get('source_file_count') != len(after_files):
            errors.append(
                f"source_file_count_mismatch: before={before.get('source_file_count')} "
                f"after={after.get('source_file_count')} files={len(before_files)}"
            )

    raw_row_count = len(rows)
    if raw_row_count != FORKS * 7:
        errors.append(f'row_count: expected {FORKS * 7}, observed {raw_row_count}')

    valid_rows = []
    for index, row in enumerate(rows):
        if not isinstance(row, dict):
            errors.append(f'row_{index}_not_object')
            continue
        fork = row.get('fork')
        arm = row.get('arm')
        if type(fork) is not int or fork < 1 or fork > FORKS:
            errors.append(f'row_{index}_fork_invalid: {fork!r}')
            continue
        if not isinstance(arm, str) or arm not in ALL_ARMS:
            errors.append(f'row_{index}_arm_invalid: {arm!r}')
            continue
        if type(row.get('artifact_errors')) is not list or row['artifact_errors'] != []:
            errors.append(f'row_{index}_artifact_errors_invalid: {row.get("artifact_errors")!r}')
            continue
        if not _positive_finite(row.get('target_cpu_ns')):
            errors.append(f'row_{index}_target_cpu_invalid: {row.get("target_cpu_ns")!r}')
            continue
        if arm != BASELINE and not _positive_finite(row.get('collector_cpu_ns')):
            errors.append(f'row_{index}_collector_cpu_invalid: {row.get("collector_cpu_ns")!r}')
            continue
        valid_rows.append(row)
    rows = valid_rows

    by_fork = {}
    for row in rows:
        if not isinstance(row, dict):
            errors.append('row_not_object')
            continue
        by_fork.setdefault(row.get('fork'), []).append(row)

    identity_fields = ['source_digest', 'image_id', 'native_so_sha256', 'daemon_jars_sha256']
    identity_values = {field: {_freeze(row.get(field)) for row in rows if isinstance(row, dict)} for field in identity_fields}
    for field, values in identity_values.items():
        if len(values) != 1 or None in values:
            errors.append(f'identity_not_uniform: {field}={sorted(map(str, values))}')
        else:
            raw = next(row.get(field) for row in rows if isinstance(row, dict))
            if not (isinstance(raw, str) and raw.strip()):
                errors.append(f'identity_invalid: {field}={raw!r}')
    if before is not None and after is not None and manifest is not None and rows:
        chain_raw = [
            before.get('source_digest'),
            after.get('source_digest'),
            manifest.get('source_digest'),
        ]
        chain_raw.extend(row.get('source_digest') for row in rows if isinstance(row, dict))
        if len({_freeze(value) for value in chain_raw}) != 1 or not all(
            isinstance(value, str) and value.strip() for value in chain_raw
        ):
            errors.append('source_provenance_unbound: before/after source-state, manifest, and rows disagree')
    if manifest is not None and rows and isinstance(rows[0], dict):
        for field in identity_fields:
            if manifest.get(field) != rows[0].get(field):
                errors.append(
                    f'identity_manifest_mismatch: {field} manifest={manifest.get(field)} rows={rows[0].get(field)}'
                )
        if manifest.get('forks') != FORKS or manifest.get('arms_per_fork') != 7:
            errors.append('manifest_counts_invalid')
        if manifest.get('declared_operations') != DECLARED_OPERATIONS:
            errors.append(f"manifest_operations: {manifest.get('declared_operations')}")
        if manifest.get('policy_eligibility') is not False:
            errors.append('manifest_policy_eligibility_not_false')

    baseline_rows = 0
    profiled_rows = 0
    for fork in range(1, FORKS + 1):
        fork_rows = by_fork.get(fork, [])
        arms = [row.get('arm') for row in fork_rows]
        if sorted(arms) != sorted(ALL_ARMS):
            errors.append(f'fork_{fork}_arms: {sorted(arms)}')
            continue
        for row in fork_rows:
            expected_seed = 23063 + fork - 1
            if row.get('seed') != expected_seed:
                errors.append(f"fork_{fork}_{row.get('arm')}_seed: {row.get('seed')}")
            if row.get('declared_operations') != DECLARED_OPERATIONS:
                errors.append(f"fork_{fork}_{row.get('arm')}_operations: {row.get('declared_operations')}")
            if row.get('status') != 0 or row.get('timed_out') is not False:
                errors.append(f"fork_{fork}_{row.get('arm')}_status: {row.get('status')}")
            if row.get('policy_eligibility') is not False:
                errors.append(f"fork_{fork}_{row.get('arm')}_policy_eligibility")
            if type(row.get('artifact_errors')) is not list or row['artifact_errors'] != []:
                errors.append(f"fork_{fork}_{row.get('arm')}_artifact_errors: {row.get('artifact_errors')}")
            if not _positive_finite(row.get('target_cpu_ns')):
                errors.append(f"fork_{fork}_{row.get('arm')}_target_cpu")
            if row.get('target_sample_boundary') != TARGET_BOUNDARY:
                errors.append(f"fork_{fork}_{row.get('arm')}_target_boundary")
            if row.get('arm') == BASELINE:
                baseline_rows += 1
                if row.get('collector_cpu_ns') is not None:
                    errors.append(f'fork_{fork}_baseline_has_collector_cpu')
                if row.get('terminal_integrity') is not None:
                    errors.append(f'fork_{fork}_baseline_has_terminal')
                if row.get('coverage_verdict') != 'NOT_APPLICABLE':
                    errors.append(f"fork_{fork}_baseline_coverage: {row.get('coverage_verdict')}")
                if row.get('covered_paths') is not None:
                    errors.append(f'fork_{fork}_baseline_covered_paths')
            else:
                profiled_rows += 1
                if row.get('coverage_verdict') != 'PASS' or row.get('covered_paths') != 72:
                    errors.append(
                        f"fork_{fork}_{row.get('arm')}_coverage: {row.get('coverage_verdict')}/{row.get('covered_paths')}"
                    )
                if row.get('terminal_integrity') != 'COMPLETE':
                    errors.append(f"fork_{fork}_{row.get('arm')}_terminal: {row.get('terminal_integrity')}")
                if not _positive_finite(row.get('collector_cpu_ns')):
                    errors.append(f"fork_{fork}_{row.get('arm')}_collector_cpu")
                if row.get('collector_sample_boundary') != COLLECTOR_BOUNDARY:
                    errors.append(f"fork_{fork}_{row.get('arm')}_collector_boundary")
    if len(by_fork) != FORKS or any(len(v) != 7 for v in by_fork.values()):
        errors.append(f'fork_coverage: forks={sorted(by_fork)} lengths={ {k: len(v) for k, v in sorted(by_fork.items())} }')
    if baseline_rows != FORKS:
        errors.append(f'baseline_count: {baseline_rows}')
    if profiled_rows != FORKS * 6:
        errors.append(f'profiled_count: {profiled_rows}')

    dir_checks = None
    if decision_dir and os.path.isdir(decision_dir):
        counters = {
            'arm_result_records': 0,
            'profiled_terminal_reports': 0,
            'baseline_summaries': 0,
            'profiled_summaries': 0,
        }
        _verify_run_tree(decision_dir, rows, errors, counters)
        dir_checks = counters
        if counters['arm_result_records'] != FORKS * 7:
            errors.append(f'dir_arm_result_count: {counters["arm_result_records"]}')
        if counters['profiled_terminal_reports'] != FORKS * 6:
            errors.append(f'dir_profiled_terminal_reports: {counters["profiled_terminal_reports"]}')
        if counters['baseline_summaries'] != FORKS:
            errors.append(f'dir_baseline_summaries: {counters["baseline_summaries"]}')
        if counters['profiled_summaries'] != FORKS * 6:
            errors.append(f'dir_profiled_summaries: {counters["profiled_summaries"]}')

    if errors:
        return fail(errors, {
            'decision_dir': decision_dir,
            'rows': raw_row_count,
            'baseline_rows': baseline_rows,
            'profiled_rows': profiled_rows,
            'dir_checks': dir_checks,
        })

    print(json.dumps({
        'schema_version': 1,
        'verdict': 'PASS',
        'verification_mode': 'full-directory' if decision_dir else 'jsonl-only',
        'rows': raw_row_count,
        'baseline_rows': baseline_rows,
        'profiled_rows': profiled_rows,
        'forks': sorted(by_fork),
        'identities': {field: sorted(values)[0] for field, values in identity_values.items()},
        'input_dirty_count': before.get('input_dirty_count') if before else None,
        'dir_checks': dir_checks,
        'policy_eligibility': False,
    }, indent=2, sort_keys=True))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
