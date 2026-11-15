import hashlib
import json
import os
import re
import shutil
import stat
import sys
import tempfile
import zipfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath

def files(directory):
    if directory.is_symlink() or not directory.is_dir() or directory.stat().st_uid != os.getuid():
        raise SystemExit('Snapshot data must be a caller-owned directory')
    result = {}
    total = 0
    for index, path in enumerate(directory.rglob('*'), 1):
        entry = path.lstat()
        if index > 10000 or path.is_symlink() or entry.st_uid != os.getuid():
            raise SystemExit('Unsafe snapshot entry or too many entries')
        if stat.S_ISDIR(entry.st_mode):
            continue
        if not stat.S_ISREG(entry.st_mode):
            raise SystemExit('Snapshot entries must be regular files')
        total += entry.st_size
        if total > 2147483648:
            raise SystemExit('Snapshot exceeds 2 GiB')
        with path.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        result[path.relative_to(directory).as_posix()] = {'size': entry.st_size, 'sha256': digest}
    if result.get('odc.mv.db', {}).get('size', 0) <= 0:
        raise SystemExit('Snapshot has no Dependency-Check database')
    return result

def unpack(archive, destination, digest):
    entry = archive.lstat()
    if not stat.S_ISREG(entry.st_mode) or entry.st_uid != os.getuid() or entry.st_size > 2147483648:
        raise SystemExit('Snapshot archive must be caller-owned, regular and at most 2 GiB')
    if not isinstance(digest, str) or not re.fullmatch(r'sha256:[0-9a-f]{64}', digest):
        raise SystemExit('Missing or unsupported GitHub artifact digest')
    with archive.open('rb') as stream:
        if 'sha256:' + hashlib.file_digest(stream, 'sha256').hexdigest() != digest:
            raise SystemExit('GitHub artifact archive digest mismatch')
        stream.seek(0)
        with zipfile.ZipFile(stream) as zipped:
            members = zipped.infolist()
            if len(members) > 10002 or sum(item.file_size for item in members) > 2149580800:
                raise SystemExit('Snapshot ZIP exceeds entry or expanded-size limits')
            seen = set()
            for member in members:
                path = PurePosixPath(member.filename)
                kind = stat.S_IFMT(member.external_attr >> 16)
                if not path.parts or path.is_absolute() or '..' in path.parts or '\\' in member.filename or '\x00' in member.orig_filename or path in seen:
                    raise SystemExit('Unsafe or duplicate ZIP path')
                if kind not in (0, stat.S_IFREG, stat.S_IFDIR) or member.flag_bits & 1 or member.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
                    raise SystemExit('Unsupported ZIP entry type or encoding')
                directory = member.is_dir() or kind == stat.S_IFDIR
                if directory and member.file_size:
                    raise SystemExit('Invalid ZIP directory entry')
                seen.add(path)
                output = destination.joinpath(*path.parts)
                if directory:
                    output.mkdir(parents=True, exist_ok=True)
                else:
                    output.parent.mkdir(parents=True, exist_ok=True)
                    with zipped.open(member) as incoming, output.open('xb') as outgoing:
                        shutil.copyfileobj(incoming, outgoing, length=1048576)

mode = sys.argv[1]
if mode == 'check-records':
    with Path(sys.argv[2]).open(encoding='utf-8', errors='replace') as log:
        if any('Failed to process CVE-' in line for line in log):
            raise SystemExit('NVD omitted CVE records; refusing incomplete security data')
    raise SystemExit(0)
if mode == 'select':
    def timestamp(value):
        try:
            parsed = datetime.fromisoformat(value.replace('Z', '+00:00'))
            return parsed.timestamp() if parsed.tzinfo else 0
        except (AttributeError, TypeError, ValueError, OverflowError):
            return 0
    payload = json.loads(Path(sys.argv[2]).read_text())
    repository_id = payload['repository_id']
    workflow_ids = payload['workflow_ids']
    prefix = 'security-snapshot-' + os.environ['SNAPSHOT_PREFIX']
    candidates = []
    for candidate in payload['candidates']:
        artifact, run, attempt = candidate['artifact'], candidate['run'], candidate['attempt']
        origin = artifact.get('workflow_run') or {}
        head = run.get('head_repository') or {}
        run_id = run.get('id')
        if type(artifact.get('id')) is not int or artifact['id'] <= 0 or type(run_id) is not int:
            continue
        if artifact.get('expired') is not False or type(artifact.get('size_in_bytes')) is not int or not 0 < artifact['size_in_bytes'] <= 2147483648:
            continue
        created = timestamp(artifact.get('created_at'))
        if not created or timestamp(artifact.get('expires_at')) <= datetime.now(timezone.utc).timestamp():
            continue
        name = artifact.get('name', '')
        base = prefix + str(run_id) + '-'
        number = name.removeprefix(base)
        if not name.startswith(base) or not re.fullmatch(r'[1-9][0-9]*', number):
            continue
        number = int(number)
        if type(run.get('run_attempt')) is not int or number > run['run_attempt'] or attempt.get('run_attempt') != number or attempt.get('status') != 'completed':
            continue
        if any(attempt.get(field) != run.get(field) for field in ('id', 'workflow_id', 'repository', 'head_repository', 'head_sha', 'head_branch', 'event')):
            continue
        if (run.get('repository') or {}).get('id') != repository_id or head.get('id') != repository_id or run.get('head_branch') != payload['default_branch']:
            continue
        if origin.get('id') != run_id or origin.get('repository_id') != repository_id or origin.get('head_repository_id') != repository_id or origin.get('head_sha') != run.get('head_sha') or origin.get('head_branch') != run.get('head_branch'):
            continue
        trusted = ((workflow_ids.get('updater') is not None and run.get('workflow_id') == workflow_ids['updater'] and run.get('event') in ('schedule', 'workflow_dispatch')) or
                   (run.get('workflow_id') == workflow_ids['legacy_ci'] and run.get('event') == 'push'))
        if trusted:
            candidates.append((created, artifact['id'], name, run_id, artifact.get('digest'), artifact['size_in_bytes']))
    if not candidates:
        raise SystemExit('No compatible trusted security snapshot')
    _, artifact_id, name, run_id, digest, size = max(candidates, key=lambda candidate: candidate[:2])
    if not isinstance(digest, str) or not re.fullmatch(r'sha256:[0-9a-f]{64}', digest):
        raise SystemExit('Selected snapshot has no verifiable GitHub SHA-256 digest')
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        print(f'artifact_id={artifact_id}\nrun_id={run_id}\nkey={name.removeprefix("security-snapshot-")}\ndigest={digest}\nsize={size}', file=output)
    print(f'Recovering completed security data from run {run_id}, artifact {artifact_id}')
    raise SystemExit(0)

source, destination = sys.argv[2:]
source, destination = Path(source), Path(destination)
key = os.environ['SNAPSHOT_KEY']
if mode == 'pack':
    expected = files(source)
    destination.mkdir()
    shutil.copytree(source, destination / 'data')
    if files(destination / 'data') != expected:
        raise SystemExit('Database changed during snapshot preparation')
    manifest = json.dumps({'schema': 1, 'key': key, 'files': expected}, sort_keys=True)
    if len(manifest.encode()) > 2097152:
        raise SystemExit('Snapshot manifest exceeds 2 MiB')
    (destination / 'manifest.json').write_text(manifest)
elif mode == 'restore':
    with tempfile.TemporaryDirectory(prefix='security-unpack-', dir=source.parent) as extracted:
        unpack(source, Path(extracted), os.environ.get('SNAPSHOT_DIGEST'))
        source = Path(extracted)
        if {p.name for p in source.iterdir()} != {'manifest.json', 'data'}:
            raise SystemExit('Incomplete snapshot artifact')
        manifest_file = source / 'manifest.json'
        if manifest_file.is_symlink() or not manifest_file.is_file() or manifest_file.stat().st_size > 2097152:
            raise SystemExit('Invalid snapshot manifest')
        manifest = json.loads(manifest_file.read_text())
        expected = files(source / 'data')
        if not isinstance(manifest, dict) or type(manifest.get('schema')) is not int or manifest != {'schema': 1, 'key': key, 'files': expected}:
            raise SystemExit('Incompatible, incomplete or damaged snapshot')
        if destination.is_symlink() or (destination.exists() and (not destination.is_dir() or destination.stat().st_uid != os.getuid() or any(destination.iterdir()))):
            raise SystemExit('Refusing to overwrite existing security data')
        destination.parent.mkdir(parents=True, exist_ok=True)
        staging = Path(tempfile.mkdtemp(prefix='.security-snapshot-', dir=destination.parent))
        try:
            shutil.copytree(source / 'data', staging, dirs_exist_ok=True)
            if files(staging) != expected:
                raise SystemExit('Snapshot changed during restoration')
            if destination.exists():
                destination.rmdir()
            staging.rename(destination)
        finally:
            if staging.exists():
                shutil.rmtree(staging)
else:
    raise SystemExit('Expected pack or restore')
