"""Export the stopped fixture's opened file; only actual ENOENT gets reserved status 44."""
import os
import subprocess
import sys

try:
    fd = os.open(sys.argv[1], os.O_RDONLY | os.O_NOFOLLOW)
except FileNotFoundError:
    sys.exit(44)
try:
    result = subprocess.run(['base64', '-w0', f'/proc/self/fd/{fd}'], pass_fds=(fd,), check=False)
    sys.exit(result.returncode)
finally:
    os.close(fd)
