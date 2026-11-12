"""Exec-only launcher for the new owned collector's bounded ptrace oracle."""
import json
import os
import resource
import re
import signal
import sys


def identity(text):
    fields = text.rsplit(') ', 1)[1].split()
    pid = int(text.split(' ', 1)[0])
    start = int(fields[19])
    if pid <= 0 or start <= 0 or fields[0] in ('Z', 'X', 'x'):
        raise ValueError('process is not a live pinned incarnation')
    return pid, start


def main(argv, read_text, setrlimit, execvp):
    if len(argv) != 5:
        raise ValueError('target PID/start/cgroup collector-ID fresh-receipt required')
    target, start = int(argv[0]), int(argv[1])
    expected_cgroup, container, receipt = argv[2:]
    if not (0 < target <= 2147483647 and start > 0):
        raise ValueError('invalid target identity')
    if len(container) != 64 or any(c not in '0123456789abcdef' for c in container):
        raise ValueError('invalid collector container ID')
    if not expected_cgroup or not os.path.isabs(receipt) or os.path.normpath(receipt) != receipt:
        raise ValueError('invalid cgroup or fresh receipt path')
    if identity(read_text(f'/proc/{target}/stat')) != (target, start):
        raise ValueError('target incarnation mismatch')
    if read_text(f'/proc/{target}/cgroup').strip() != expected_cgroup:
        raise ValueError('target cgroup mismatch')
    pid, own_start = identity(read_text('/proc/self/stat'))
    own_cgroup = read_text('/proc/self/cgroup').strip()
    if not re.search(r'(?<![a-f0-9])' + container + r'(?![a-f0-9])', own_cgroup) or pid == target:
        raise ValueError('launcher is not in the exact owned collector')
    os.umask(0o077)
    setrlimit(resource.RLIMIT_FSIZE, (16777216, 16777216))
    signal.signal(signal.SIGXFSZ, signal.SIG_DFL)
    row = dict(pid=pid, startTicks=own_start, cgroup=own_cgroup,
               containerId=container, targetPid=target, targetStartTicks=start)
    data = (json.dumps(row, sort_keys=True) + '\n').encode('ascii')
    if len(data) > 4096:
        raise ValueError('startup receipt exceeds bound')
    temporary = receipt + '.tmp'
    fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        with os.fdopen(fd, 'wb', closefd=False) as out:
            out.write(data)
            out.flush()
            os.fsync(fd)
    finally:
        os.close(fd)
    os.link(temporary, receipt, follow_symlinks=False)
    # Recheck after receipt publication; a failed exec remains a launch failure.
    if identity(read_text(f'/proc/{target}/stat')) != (target, start):
        raise ValueError('target changed before exec')
    if read_text(f'/proc/{target}/cgroup').strip() != expected_cgroup:
        raise ValueError('target cgroup changed before exec')
    args = ['strace', '-f', '-r', '-T', '-s', '256', '-xx', '-e',
            'trace=openat,openat2,close,gettid', '-o',
            '/fixture/evidence/ptrace-open.log', '-p', str(target)]
    execvp('strace', args)


def read_text(path):
    with open(path, encoding='ascii') as source:
        text = source.read(8193)
    if len(text) > 8192:
        raise ValueError('proc identity exceeds bound')
    return text


if __name__ == '__main__':
    main(sys.argv[1:], read_text, resource.setrlimit, os.execvp)
