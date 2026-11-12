import pathlib, hashlib, os, stat, sys, errno, re


def digest(p):
    h=hashlib.sha256()
    with open(p,'rb') as f:
        for b in iter(lambda:f.read(4096),b''): h.update(b)
    print(h.hexdigest(),p)


def fingerprint_mappings(pid, agent, read_text=lambda p: pathlib.Path(p).read_text(), opener=open):
    mappings=[line.split() for line in read_text('/proc/'+str(pid)+'/maps').splitlines()]
    selected=[m for m in mappings if len(m)==6 and (m[5]==agent or m[5].endswith('/lib/server/libjvm.so'))]
    if not any(m[5]==agent for m in selected):
        raise ValueError(f'/proc/{pid}/maps: missing agent mapping {agent}')
    if not any(m[5].endswith('/lib/server/libjvm.so') for m in selected):
        raise ValueError(f'/proc/{pid}/maps: missing libjvm mapping')
    for path in sorted(set(m[5] for m in selected)):
        rooted='/proc/'+str(pid)+'/root'+path
        hashed_identity=None
        for m in [row for row in selected if row[5]==path]:
            mapped='/proc/'+str(pid)+'/map_files/'+m[0]
            observations={'mapped_stat':'unavailable','root_stat':'unavailable'}
            def context():
                return f'{mapped} {rooted} maps={" ".join(m)} '+ ' '.join(f'{k}={v}' for k,v in observations.items())
            try:
                # map_files is the required proc magic link, not an arbitrary pathname fallback.
                with opener(mapped,'rb') as mapped_file:
                    mapped_info=os.fstat(mapped_file.fileno())
                    observations['mapped_stat']=f'{os.major(mapped_info.st_dev)}:{os.minor(mapped_info.st_dev)}:{mapped_info.st_ino}'
                    with opener(rooted,'rb') as root_file:
                        root_info=os.fstat(root_file.fileno())
                        observations['root_stat']=f'{os.major(root_info.st_dev)}:{os.minor(root_info.st_dev)}:{root_info.st_ino}'
                        identity=(mapped_info.st_dev,mapped_info.st_ino)
                        if (not stat.S_ISREG(mapped_info.st_mode) or not stat.S_ISREG(root_info.st_mode)
                                or identity!=(root_info.st_dev,root_info.st_ino)
                                or int(m[4])!=mapped_info.st_ino
                                or (hashed_identity is not None and identity!=hashed_identity)):
                            raise ValueError('mapped file identity mismatch')
                        print('OWNED_MAPPING',' '.join(m),*(f'{k}={v}' for k,v in observations.items()))
                        if hashed_identity is None:
                            h=hashlib.sha256()
                            for b in iter(lambda:mapped_file.read(4096),b''): h.update(b)
                            print(h.hexdigest(),rooted)
                            hashed_identity=identity
            except (OSError, ValueError) as error:
                raise type(error)('mapped file fingerprint failed: '+context()+': '+str(error)) from error


def fingerprint_lsm(role, owned, inventory, read_text=lambda p: pathlib.Path(p).read_text()):
    modules=inventory.split(',')
    if not modules or any(not re.fullmatch(r'[a-zA-Z0-9_]+', m) for m in modules) or len(set(modules))!=len(modules):
        raise ValueError('invalid host LSM inventory: '+repr(inventory))
    path='/proc/'+owned+'/attr/current'
    try:
        label=read_text(path).strip()
    except OSError as error:
        if error.errno!=errno.EINVAL or not set(modules)<={'capability','landlock','lockdown','yama','bpf'}:
            raise
        print('OWNED_LSM',role,path,'label=UNAVAILABLE errno=22', 'source=host:/sys/kernel/security/lsm', 'modules='+inventory)
        return
    if not label:
        raise ValueError(f'{role} {path}: empty LSM label')
    print('OWNED_LSM',role,path,label,'source=host:/sys/kernel/security/lsm','modules='+inventory)


def main(args):
    pid=int(args[0]); agent=args[1]
    inventory=args[4]
    print('ACTIVE_LSM source=host:/sys/kernel/security/lsm modules='+inventory)
    digest('/proc/'+str(pid)+'/exe')
    fingerprint_mappings(pid, agent)
    for p in pathlib.Path('/installed').rglob('*'):
        if p.is_file() and not p.is_symlink(): digest(str(p))
    daemon=args[2]; tracer=args[3]
    for role, owned in [('target',str(pid)),('daemon',daemon),('tracer',tracer)]:
        status=pathlib.Path('/proc/'+owned+'/status').read_text().splitlines()
        for key in ['Uid:','Gid:','CapEff:','CapPrm:','CapBnd:','CapInh:','CapAmb:','NoNewPrivs:','Seccomp:']:
            line=next(line for line in status if line.startswith(key))
            print('OWNED_SECURITY',role,line)
            if role=='target' and key in ['Uid:','Gid:']: assert set(line.split()[1:])=={'20000'}
            if role=='target' and key.startswith('Cap'): assert int(line.split()[1],16)==0
            if role=='target' and key=='NoNewPrivs:': assert line.split()[1]=='1'
        fingerprint_lsm(role,owned,inventory)
        for namespace in ['pid','user','mnt','cgroup','time']:
            print('OWNED_NAMESPACE',role,namespace,os.readlink('/proc/'+owned+'/ns/'+namespace))
    print('KERNEL_BUILD',pathlib.Path('/proc/version').read_text().strip())
    maps=[]
    for p in pathlib.Path('/proc/'+daemon+'/fdinfo').iterdir():
        text=p.read_text()
        if 'map_id:' in text or 'prog_id:' in text:
            print('OWNED_BPF_FDINFO',p.name,text)
            maps.append(text)
    assert any('map_id:' in t and '32768' in t for t in maps)
    assert any('prog_id:' in t for t in maps)


if __name__ == '__main__':
    main(sys.argv[1:])
