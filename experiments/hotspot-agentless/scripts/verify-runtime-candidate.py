#!/usr/bin/env python3
"""Offline exact-token oracle; useful frames never imply a metadata lifetime lease."""
import argparse
import json
from pathlib import Path


def verify(manifest, rows):
    events, terminals, sessions = {}, {}, set()
    for row in rows:
        if 'session_id' in row:sessions.add(row['session_id'])
        if row['kind']=='event' and row['event']['filename'] in manifest['tokens']:
            event,key=row['event'],row['event_id']
            if key in events:raise ValueError('duplicate event')
            if event['host_pid']!=manifest['pid'] or event['process_start_ticks']!=manifest['start_ticks']:raise ValueError('process generation mismatch')
            events[key]=event
        elif row['kind']=='enrichment':
            key=row['event_id']
            if key in terminals:raise ValueError('duplicate terminal')
            terminals[key]=row['evidence']
    if len(sessions)>1:raise ValueError('mixed collector sessions')
    useful,application_unavailable,seen=0,0,set()
    for key,event in events.items():
        token=event['filename']
        if token in seen:raise ValueError('duplicate token')
        seen.add(token)
        if key not in terminals:raise ValueError('missing terminal')
        evidence=terminals[key];frames=[f['function_name'] for f in evidence['frames']]
        if evidence['attribution']!='event_correlated':
            if evidence['attribution'] not in ('unavailable','skipped') or frames:raise ValueError('invalid unavailable terminal')
            application_unavailable+=1
            continue
        expected=manifest['tokens'][token]
        # Select the oracle's application class; do not call this a full-stack proof.
        owner=expected[0].rsplit('.',1)[0]+'.'
        application=[f for f in frames if owner in f]
        if not application:
            application_unavailable+=1
            continue
        if len(application)<len(expected) or any(name not in application[i] for i,name in enumerate(expected)):raise ValueError('wrong or reordered application route')
        useful+=1
    if seen!=set(manifest['tokens']):raise ValueError('missing marked token')
    return {'marked':len(seen),'useful':useful,'application_unavailable':application_unavailable,'lifetime_qualified':False,'scope':'application_route_prefix'}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('manifest',type=Path);parser.add_argument('capture',type=Path);a=parser.parse_args()
    print(json.dumps(verify(json.loads(a.manifest.read_text()),[json.loads(x) for x in a.capture.read_text().splitlines() if x.strip()])))
