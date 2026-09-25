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
    return type(value) in (int, float) and math.isfinite(value) and value > 0


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

    errors = []
    rows = []
    try:
        with open(jsonl_path, encoding='utf-8') as handle:
            for line in handle:
                line = line.strip()
                if line:
                    rows.append(json.loads(line, parse_constant=_reject_nonstandard_constant))
    except (OSError, ValueError) as error:
        return fail([f'jsonl_unreadable: {error}'], {'jsonl': jsonl_path})

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
        if before.get('input_dirty_count') != 0 or after.get('input_dirty_count') != 0:
            errors.append(
                f"input_dirty_count: before={before.get('input_dirty_count')} after={after.get('input_dirty_count')}"
            )

    if len(rows) != FORKS * 7:
        errors.append(f'row_count: expected {FORKS * 7}, observed {len(rows)}')

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
            if row.get('artifact_errors'):
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
        arm_results = []
        terminal_reports = 0
        baseline_summaries = 0
        profiled_summaries = 0
        for name in sorted(os.listdir(decision_dir)):
            arm_dir = os.path.join(decision_dir, name)
            if not os.path.isdir(arm_dir):
                continue
            for fork_name in sorted(os.listdir(arm_dir)):
                fork_dir = os.path.join(arm_dir, fork_name)
                if not os.path.isdir(fork_dir):
                    continue
                arm_results.append(f'{name}/{fork_name}')
                if os.path.isfile(os.path.join(fork_dir, 'terminal-report.json')):
                    terminal_reports += 1
                summary_path = os.path.join(fork_dir, 'summary.json')
                if os.path.isfile(summary_path):
                    try:
                        with open(summary_path, encoding='utf-8') as handle:
                            summary = json.load(handle)
                        if summary.get('mode') == 'baseline':
                            baseline_summaries += 1
                        else:
                            profiled_summaries += 1
                    except ValueError:
                        errors.append(f'bad_summary: {name}/{fork_name}')
        dir_checks = {
            'arm_result_records': len(arm_results),
            'profiled_terminal_reports': terminal_reports,
            'baseline_summaries': baseline_summaries,
            'profiled_summaries': profiled_summaries,
        }
        if len(arm_results) != FORKS * 7:
            errors.append(f'dir_arm_result_count: {len(arm_results)}')
        if terminal_reports != FORKS * 6:
            errors.append(f'dir_profiled_terminal_reports: {terminal_reports}')
        if baseline_summaries != FORKS:
            errors.append(f'dir_baseline_summaries: {baseline_summaries}')
        if profiled_summaries != FORKS * 6:
            errors.append(f'dir_profiled_summaries: {profiled_summaries}')

    if errors:
        return fail(errors, {
            'decision_dir': decision_dir,
            'rows': len(rows),
            'baseline_rows': baseline_rows,
            'profiled_rows': profiled_rows,
            'dir_checks': dir_checks,
        })

    print(json.dumps({
        'schema_version': 1,
        'verdict': 'PASS',
        'verification_mode': 'full-directory' if decision_dir else 'jsonl-only',
        'rows': len(rows),
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
