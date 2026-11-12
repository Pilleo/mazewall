"""Test-fixture-only pinned signaling; unsupported or mismatching identity fails closed."""
import os
import signal
import sys


def stop_owned(pid, expected_start, expected_cgroup):
    fd = os.pidfd_open(pid, 0)
    try:
        with open(f'/proc/{pid}/stat') as source:
            fields = source.read().rsplit(') ', 1)[1].split()
        if fields[0] in ('Z', 'X', 'x') or int(fields[19]) != expected_start:
            raise RuntimeError('pinned collector incarnation mismatch; no signal sent')
        with open(f'/proc/{pid}/cgroup') as source:
            actual_cgroup = source.read().strip()
        if not expected_cgroup or actual_cgroup != expected_cgroup:
            raise RuntimeError('pinned collector owned cgroup mismatch; no signal sent')
        signal.pidfd_send_signal(fd, signal.SIGTERM)
        print(f'pinned SIGTERM requested pid={pid} start={expected_start} cgroup={actual_cgroup}', flush=True)
    finally:
        os.close(fd)


if __name__ == '__main__':
    stop_owned(int(sys.argv[1]), int(sys.argv[2]), sys.argv[3])
