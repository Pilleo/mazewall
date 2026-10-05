#!/usr/bin/env python3
"""Offline carrier/logical oracle comparison; never injects oracle identity into capture."""
import argparse
import json
from pathlib import Path


def verify(oracle, records):
    terminals={}
    for row in records:
        if row['kind']=='enrichment':
            if row['event_id'] in terminals:raise ValueError('duplicate terminal')
            terminals[row['event_id']]=row['evidence']
    seen,logical,carriers=set(),0,set()
    for row in records:
        if row['kind']!='event':continue
        event=row['event'];token=event['filename'].rsplit('/',1)[-1]
        if token not in oracle:continue
        if token in seen:raise ValueError('duplicate logical operation')
        seen.add(token);carriers.add(event['host_tid']);identity=event.get('thread_identity',{})
        if identity.get('source')=='oracle':raise ValueError('oracle substituted for capture')
        if identity.get('scope')!='mounted_virtual':continue
        if identity.get('logical_id')!=oracle[token]['logical_id']:raise ValueError('wrong logical thread')
        if not identity.get('object_lease') or not identity.get('mount_capture',0) or identity.get('mount_capture')!=identity.get('mount_decode'):raise ValueError('unproved mount/object identity')
        if identity.get('parent_available',False):raise ValueError('parent source unqualified')
        evidence=terminals.get(row['event_id'])
        if evidence is None:raise ValueError('missing identity terminal')
        frames=[f['function_name'] for f in evidence['frames']]
        if evidence['attribution']!='event_correlated':
            if frames:raise ValueError('unavailable frames not empty')
            continue
        expected=oracle[token]['route'];owner=expected[0].rsplit('.',1)[0]+'.';application=[f for f in frames if owner in f]
        if len(application)<len(expected) or any(name not in application[i] for i,name in enumerate(expected)):raise ValueError('wrong or mixed logical route')
        logical+=1
    if seen!=set(oracle):raise ValueError('missing virtual operations')
    return {'marked':len(seen),'logical_observed':logical,'carrier_tids':len(carriers),'parent_available':False,'production_qualified':False}


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('oracle',type=Path);parser.add_argument('capture',type=Path);a=parser.parse_args()
    oracle={r['token']:r for r in [json.loads(x) for x in a.oracle.read_text().splitlines() if x.strip()]}
    print(json.dumps(verify(oracle,[json.loads(x) for x in a.capture.read_text().splitlines() if x.strip()])))
