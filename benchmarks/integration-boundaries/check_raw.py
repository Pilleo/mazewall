#!/usr/bin/env python3
"""Check schema-1 observations; companion custody authenticates artifacts separately."""
import argparse
import copy
import hashlib
import json
import pathlib
import re
import statistics

MODES = ('DIRECT', 'JDK', 'MANAGED', 'PROCESS_PORTAL')
SHAPES = {'schema': 4, 'environment': 6, 'unavailable': 2, 'prepare': 4,
          'worker-artifact': 3, 'resources': 7, 'sample': 15, 'portal-timing': 6,
          'rejected': 7, 'throughput': 5, 'accounting': 6, 'drain': 4,
          'post-drain': 3, 'physical-child-end': 3, 'physical-thread-end': 4,
          'selfcheck': 3, 'checking-smoke': 3}
PREPARATION = {('JDK', 'thread-prestart'),
               ('MANAGED', 'open+required-process-install'),
               ('MANAGED', 'domain-prepare+required-install+readiness'),
               ('PROCESS_PORTAL', 'metadata+bundle-catalog'),
               ('PROCESS_PORTAL', 'runtime-open'),
               ('PROCESS_PORTAL', 'binding+materialize+process-startup+required-install+readiness')}
DRAINS = {'DIRECT': 'synchronous-no-owner', 'JDK': 'terminated+joined',
          'MANAGED': 'Complete', 'PROCESS_PORTAL': 'close-returned'}
POST_DRAIN = {'JDK': 'REJECTED', 'MANAGED': 'REJECTED', 'PROCESS_PORTAL': 'NOT_STARTED'}
UNAVAILABLE = 'isolated-native-install,IPC,guest-body,dispatch-overhead,per-call-child-memory'

def require(condition, reason):
    if not condition:
        raise ValueError(reason)

def validate(rows, bundle_sha256):
    require(bool(re.fullmatch(r'[0-9a-f]{64}', bundle_sha256)), 'custody:bundle-hash')
    require(all(r and r[0] in SHAPES and len(r) == SHAPES[r[0]] for r in rows), 'schema:tag-or-shape')
    grouped = {tag: [r for r in rows if r[0] == tag] for tag in SHAPES}
    for tag, records in grouped.items():
        require(bool(records), 'family:' + tag)
    require(grouped['schema'] == [['schema', '1', 'monotonic-ns', 'checking-only']], 'schema:identity')
    require(grouped['unavailable'] == [['unavailable', UNAVAILABLE]], 'attribution:unavailable')
    require(grouped['selfcheck'] == [['selfcheck', 'PASS', 'unicode,bounds,overload,timeout,physical-end']], 'outcome:selfcheck')
    require(grouped['checking-smoke'] == [['checking-smoke', 'PASS', 'all-accepted-results-verified,physical-ends-accounted']], 'outcome:checking-smoke')
    require(grouped['worker-artifact'] == [['worker-artifact', 'bounded-text-worker', bundle_sha256]], 'custody:worker-artifact')
    environment = grouped['environment']
    require(len(environment) == 1 and all(environment[0][1:5]), 'environment:identity')
    options = re.fullmatch(r'Options\(samples=(\d+), size=(\d+), pressure=(\d+), seconds=(\d+)\)', environment[0][5])
    if options is None:
        raise ValueError('environment:options')
    samples_count, size, pressure, seconds = map(int, options.groups())
    require(2 <= samples_count <= 1000 and 1 <= size <= 65536 and 3 <= pressure <= 64 and 5 <= seconds <= 600, 'environment:bounds')
    prepare = grouped['prepare']
    require(len(prepare) == len(PREPARATION) and {(r[1], r[3]) for r in prepare} == PREPARATION, 'attribution:prepare')
    require(all(int(r[2]) > 0 for r in prepare), 'measurement:prepare')
    for tag in ('sample', 'rejected', 'throughput', 'accounting', 'drain', 'post-drain'):
        require(all(r[1] in MODES for r in grouped[tag]), 'mode:' + tag)
    for tag in ('throughput', 'accounting', 'drain'):
        require(len(grouped[tag]) == 4 and {r[1] for r in grouped[tag]} == set(MODES), 'family:' + tag)
    require(len(grouped['post-drain']) == 3 and {r[1]: r[2] for r in grouped['post-drain']} == POST_DRAIN, 'outcome:post-drain')
    require({r[1]: r[3] for r in grouped['drain']} == DRAINS, 'outcome:drain')
    require(all(int(r[2]) == 0 if r[1] == 'DIRECT' else int(r[2]) > 0 for r in grouped['drain']), 'measurement:drain')
    ends = grouped['physical-child-end']
    require(len(ends) == 1 and int(ends[0][1]) > 0 and ends[0][2] == 'false', 'outcome:physical-child-end')
    thread_ends = grouped['physical-thread-end']
    require(len(thread_ends) == 1 and thread_ends[0][1] == 'JDK' and int(thread_ends[0][2]) > 0 and thread_ends[0][3] == 'false', 'outcome:physical-thread-end')
    resources = grouped['resources']
    resource_labels = {'worker-ready', 'worker-after-work', 'final'} | {m + '-' + phase for m in MODES for phase in ('before', 'after')}
    require(len(resources) == len(resource_labels) and {r[1] for r in resources} == resource_labels, 'family:resources')
    host_pids = {r[2] for r in resources if not r[1].startswith('worker-')}
    require(len(host_pids) == 1 and ends[0][1] not in host_pids, 'resources:host-pid')
    for r in resources:
        require(int(r[2]) > 0 and re.fullmatch(r'\d+ kB', r[3]) is not None and int(r[4]) > 0 and int(r[5]) >= 0 and int(r[6]) > 0, 'measurement:resources')
        require(not r[1].startswith('worker-') or r[2] == ends[0][1], 'resources:child-pid')
    summary = {}
    ordinary = {}
    total = samples_count + 1 + pressure
    for mode in MODES:
        selected = [r for r in grouped['sample'] if r[1] == mode]
        rejected = [r for r in grouped['rejected'] if r[1] == mode]
        offered, accepted, denied, completed = map(int, next(r[2:] for r in grouped['accounting'] if r[1] == mode))
        expected_denied = 0 if mode == 'DIRECT' else pressure - 2
        require((offered, accepted, denied, completed) == (total, total - expected_denied, expected_denied, total - expected_denied), 'configured:accounting')
        require(len(selected) == accepted and len(rejected) == denied, 'accounting:records')
        ids = [int(r[3]) for r in selected + rejected]
        require(len(ids) == total and set(ids) == set(range(total)), 'configured:ids')
        for r in selected + rejected:
            request_id = int(r[3])
            require(int(r[4]) == size, 'configured:size')
            phase = 'first-measured' if request_id == 0 else 'warm' if request_id <= samples_count else 'pressure'
            require(r[2] == phase, 'configured:phase')
            text = ''.join(chr(ord('a') + (request_id + i) % 26) for i in range(size))
            require(r[5] == hashlib.sha256(text.encode()).hexdigest(), 'digest:input')
            if r[0] == 'rejected':
                require(mode != 'DIRECT' and request_id >= samples_count + 3, 'configured:rejection-id')
                form = 'not-started-handle' if mode == 'PROCESS_PORTAL' else 'synchronous-admission'
                require(r[6] == form, 'outcome:rejected')
                continue
            require(mode == 'DIRECT' or request_id < samples_count + 3, 'configured:accepted-id')
            require(r[6] == hashlib.sha256(text.upper().encode()).hexdigest(), 'digest:output')
            submitted, started, ended, delivered, physical = map(int, r[7:12])
            require(0 < submitted <= started <= ended <= delivered <= physical, 'clock:order')
            require(int(r[12]) == started - submitted and int(r[13]) == ended - started, 'clock:delta')
            label = 'host-mapping+transport+worker' if mode == 'PROCESS_PORTAL' else 'local-body'
            require(r[14] == label, 'attribution:sample')
        normal = [r for r in selected if r[2] != 'pressure']
        ordinary[mode] = {(r[2], r[3]): r for r in normal}
        require(len(normal) == samples_count + 1, 'configured:ordinary')
        warm = [int(r[10]) - int(r[7]) for r in normal if r[2] == 'warm']
        throughput = next(r for r in grouped['throughput'] if r[1] == mode)
        require(throughput[4] == 'includes-result-verification+raw-emission', 'attribution:throughput')
        require(int(throughput[2]) == len(normal) and int(throughput[3]) > 0, 'measurement:throughput')
        summary[mode] = {'warm_result_observed_ns': {'n': len(warm), 'min': min(warm), 'median': statistics.median(warm), 'max': max(warm)}, 'harness_ordinary_completions_per_second_including_checks_and_emission': len(normal) * 1e9 / int(throughput[3]), 'pressure_offered': pressure, 'pressure_rejected': denied}
    require(all({key: r[4:7] for key, r in ordinary[m].items()} == {key: r[4:7] for key, r in ordinary['DIRECT'].items()} for m in MODES), 'equivalence:ordinary')
    timings = grouped['portal-timing']
    require(len(timings) == samples_count + 1 and {(r[1], r[2]) for r in timings} == set(ordinary['PROCESS_PORTAL']), 'portal:timing-ids')
    for r in timings:
        require(r[5] == 'broker-queue,transport+worker', 'attribution:portal-timing')
        mapping = ordinary['PROCESS_PORTAL'][(r[1], r[2])]
        queue, execution = map(int, r[3:5])
        require(queue >= 0 and execution >= 0 and queue + execution <= int(mapping[13]), 'measurement:portal-timing')
    return summary

def selfcheck(rows, bundle_sha256):
    cases = []
    def change(name, tag, index, value, reason, mode=None):
        mutated = copy.deepcopy(rows)
        record = next(r for r in mutated if r[0] == tag and (mode is None or r[1] == mode))
        replacement = value(record[index]) if callable(value) else value
        require(replacement != record[index], 'selfcheck:noop-field:' + name)
        record[index] = replacement
        cases.append((name, mutated, reason))
    for tag in SHAPES:
        cases.append(('missing-family-' + tag, [r for r in rows if r[0] != tag], 'family:' + tag))
    for index, r in enumerate(rows):
        if r[0] in ('prepare', 'resources'):
            reason = 'attribution:prepare' if r[0] == 'prepare' else 'family:resources'
            cases.append(('missing-record-' + '-'.join(r[:2]) + '-' + str(index), rows[:index] + rows[index + 1:], reason))
    for mode in MODES:
        change('sample-label-' + mode, 'sample', 14, 'guest-body', 'attribution:sample', mode)
        change('throughput-label-' + mode, 'throughput', 4, 'isolated-throughput', 'attribution:throughput', mode)
        change('drain-outcome-' + mode, 'drain', 3, 'Incomplete', 'outcome:drain', mode)
        change('drain-duration-' + mode, 'drain', 2, '-1', 'measurement:drain', mode)
    for mode in POST_DRAIN:
        change('post-drain-' + mode, 'post-drain', 2, 'ACCEPTED', 'outcome:post-drain', mode)
    change('portal-label', 'portal-timing', 5, 'isolated-IPC', 'attribution:portal-timing')
    change('portal-wrong-id', 'portal-timing', 2, lambda value: str(-int(value) - 1), 'portal:timing-ids')
    change('portal-wrong-phase', 'portal-timing', 1, 'pressure', 'portal:timing-ids')
    mutated = copy.deepcopy(rows)
    timings = [r for r in mutated if r[0] == 'portal-timing']
    timings[1][1:3] = timings[0][1:3]
    cases.append(('portal-duplicated-id', mutated, 'portal:timing-ids'))
    excessive_duration = max(int(r[13]) for r in rows if r[0] == 'sample' and r[1] == 'PROCESS_PORTAL') + 1
    change('portal-duration', 'portal-timing', 4, str(excessive_duration), 'measurement:portal-timing')
    change('bundle-identity', 'worker-artifact', 2, lambda value: ('1' if value[0] == '0' else '0') + value[1:], 'custody:worker-artifact')
    change('schema-version', 'schema', 1, '2', 'schema:identity')
    change('unavailable-label', 'unavailable', 1, 'nothing', 'attribution:unavailable')
    change('prepare-label', 'prepare', 3, 'isolated-native-install', 'attribution:prepare')
    change('prepare-duration', 'prepare', 2, '-1', 'measurement:prepare')
    change('resource-unit', 'resources', 3, '123 bytes', 'measurement:resources')
    change('resource-label', 'resources', 1, 'isolated-allocation', 'family:resources')
    change('resource-pid', 'resources', 2, lambda value: str(int(value) + 1), 'resources:child-pid', 'worker-ready')
    change('selfcheck-outcome', 'selfcheck', 1, 'FAIL', 'outcome:selfcheck')
    change('unknown-mode', 'sample', 1, 'UNKNOWN', 'mode:sample')
    change('unknown-tag', 'sample', 0, 'invented', 'schema:tag-or-shape')
    change('configured-size', 'sample', 4, lambda value: '2' if int(value) == 1 else '1', 'configured:size')
    mutated = copy.deepcopy(rows)
    next(r for r in mutated if r[0] == 'sample').pop()
    cases.append(('truncated-record', mutated, 'schema:tag-or-shape'))
    change('environment-counts', 'environment', 5, lambda value: re.sub(r'samples=(\d+)', lambda match: 'samples=' + ('3' if int(match[1]) == 2 else '2'), value, count=1), 'configured:accounting')
    change('counter', 'accounting', 3, lambda value: str(int(value) + 1), 'configured:accounting')
    change('output-digest', 'sample', 6, 'bad-digest', 'digest:output')
    change('clock', 'sample', 8, '0', 'clock:order')
    change('live-child', 'physical-child-end', 2, 'true', 'outcome:physical-child-end')
    change('live-thread', 'physical-thread-end', 3, 'true', 'outcome:physical-thread-end')
    mutated = [copy.deepcopy(r) for r in rows if not (r[0] in ('sample', 'rejected') and r[2] == 'pressure')]
    for r in mutated:
        if r[0] == 'accounting':
            normal_count = len([s for s in mutated if s[0] == 'sample' and s[1] == r[1]])
            r[2:] = [str(normal_count), str(normal_count), '0', str(normal_count)]
    cases.append(('consistent-counters-missing-configured-pressure', mutated, 'family:rejected'))
    removed_id = next(r[3] for r in rows if r[0] == 'sample' and r[1] == 'MANAGED' and r[2] == 'pressure')
    truncated = [copy.deepcopy(r) for r in rows if not (r[0] in ('sample', 'rejected') and r[3] == removed_id)]
    for r in truncated:
        if r[0] == 'accounting':
            accepted = sum(s[0] == 'sample' and s[1] == r[1] for s in truncated)
            denied = sum(s[0] == 'rejected' and s[1] == r[1] for s in truncated)
            r[2:] = [str(accepted + denied), str(accepted), str(denied), str(accepted)]
    cases.append(('consistent-counters-truncated-pressure-all-families-present', truncated, 'configured:accounting'))
    cases.append(('missing-managed-samples-other-families-present', [r for r in rows if not (r[0] == 'sample' and r[1] == 'MANAGED')], 'accounting:records'))
    for name, mutated, expected in cases:
        require(mutated != rows, 'selfcheck:noop-dataset:' + name)
        try:
            validate(mutated, bundle_sha256)
        except ValueError as failure:
            require(str(failure) == expected, f'selfcheck:{name}:unexpected-reason:{failure}')
            print('RAW_EXPECTED_REJECT', name, expected)
        else:
            raise ValueError('selfcheck:accepted-corruption:' + name)
    print('RAW_SELFCHECK PASS', len(cases), 'independent-corruptions')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('raw', type=pathlib.Path)
    parser.add_argument('--custody', required=True, type=pathlib.Path, help='frozen publication manifest; verify its companion custody separately')
    parser.add_argument('--selfcheck', action='store_true')
    args = parser.parse_args()
    require(args.raw.stat().st_size <= 10_000_000, 'raw:bounded-size')
    require(args.custody.stat().st_size <= 10_000_000, 'custody:bounded-size')
    bundle = json.loads(args.custody.read_text())['workerBundleSha256']
    rows = [line.split('\t') for line in args.raw.read_text().splitlines() if '\t' in line]
    summary = validate(rows, bundle)
    if args.selfcheck:
        selfcheck(rows, bundle)
    print(json.dumps({'scope': 'checking smoke; descriptive, order/JIT/verification effects included; no performance conclusion; artifact authenticity requires companion custody verification', 'modes': summary}, indent=2))

if __name__ == '__main__':
    main()
