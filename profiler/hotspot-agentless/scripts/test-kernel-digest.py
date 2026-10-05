#!/usr/bin/env python3
"""Real kernel known answers and refusal/ownership tests; no fake successful digest."""
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("kernel_digest", Path(__file__).with_name("kernel-digest.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class KernelDigestTest(unittest.TestCase):
    def test_known_answers_boundaries_and_size_refusal(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "input"
            for length in [0, 3, 55, 56, 63, 64, 65, 65535, 65536, 65537, 1048576]:
                data = bytes((i * 17 + 3) % 256 for i in range(length))
                path.write_bytes(data)
                self.assertEqual(module.digest_file(path, length), hashlib.sha256(data).hexdigest())
                self.assertEqual(path.read_bytes(), data)
                if length:
                    with self.assertRaises(ValueError): module.digest_file(path, length - 1)
            path.write_bytes(b"abc")
            self.assertEqual(module.digest_file(path, 3), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

    def test_permission_failure_is_original_and_has_no_fallback(self):
        failure = PermissionError(1, "denied")
        with patch.object(module.socket, "socket", side_effect=failure):
            with self.assertRaises(PermissionError) as caught: module.digest_file(Path("unused"), 10)
        self.assertIs(caught.exception, failure)

    def test_owned_sockets_close_on_input_budget_failure(self):
        created = []
        actual = module.socket.socket
        class Tracked:
            def __init__(self, socket): self.socket = socket; created.append(socket)
            def __enter__(self): return self
            def __exit__(self, *args): self.socket.close()
            def bind(self, *args): return self.socket.bind(*args)
            def accept(self):
                owned, address = self.socket.accept()
                return Tracked(owned), address
            def sendall(self, *args): return self.socket.sendall(*args)
            def recv(self, *args): return self.socket.recv(*args)
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "input"; path.write_bytes(b"abc")
            factory = SimpleNamespace(AF_ALG=module.socket.AF_ALG, SOCK_SEQPACKET=module.socket.SOCK_SEQPACKET,
                                      MSG_MORE=module.socket.MSG_MORE, socket=lambda *args: Tracked(actual(*args)))
            with patch.object(module, "socket", factory):
                with self.assertRaises(ValueError): module.digest_file(path, 2)
        self.assertEqual(len(created), 2)
        self.assertTrue(all(value.fileno() == -1 for value in created))


if __name__ == "__main__": unittest.main()
