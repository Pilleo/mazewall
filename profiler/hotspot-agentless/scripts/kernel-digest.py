#!/usr/bin/env python3
"""Explicit read-only AF_ALG qualification, not a production admission provider.

Invoke under an external timeout. Unsupported kernels and permission failures
propagate; there is no userspace fallback or verification-helper trust shortcut.
"""
import argparse
from pathlib import Path
import socket


def digest_file(path: Path, max_bytes: int) -> str:
    if not 0 <= max_bytes <= 512 * 1024 * 1024:
        raise ValueError("invalid hash byte budget")
    total = 0
    with socket.socket(socket.AF_ALG, socket.SOCK_SEQPACKET) as algorithm:
        algorithm.bind(("hash", "sha256"))
        operation, _ = algorithm.accept()
        with operation, path.open("rb") as source:
            while True:
                block = source.read(65536)
                if not block:
                    break
                total += len(block)
                if total > max_bytes:
                    raise ValueError("hash input exceeds byte budget")
                operation.sendall(block, socket.MSG_MORE)
            operation.sendall(b"")
            digest = operation.recv(32)
            if len(digest) != 32:
                raise ValueError("short kernel digest")
            return digest.hex()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path", type=Path)
    parser.add_argument("max_bytes", type=int)
    args = parser.parse_args()
    print(digest_file(args.path, args.max_bytes))
