---
document_type: execution_plan
base_revision: eacd0081
status: qualification_complete_native_activation_requires_review
---

# HotSpot Admission Acceleration Qualification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Do not delegate without operator selection.

**Goal:** Qualify Linux AF_ALG SHA256 as an accelerator for full-byte asset hashing and produce a concrete, reviewed native-provider integration contract.

**Architecture:** Build a developer-only read-only hash probe with the Python standard library, compare real assets against hashlib and the pinned interpreter baseline, then select a production bridge only after native ownership/permission review. This plan intentionally leaves production Java hashing unchanged; qualification is the independently testable deliverable before a new native/public interface.

**Tech Stack:** Python3 standard-library socket/hashlib, existing Kotlin performance probe, Linux AF_ALG; future bridge uses existing NativeEngine networking/fileSystem traits.

**Spec:** `docs/plans/2026-10-05-hotspot-performance-capability-design.md`, admission section; performance issue `docs/internals/backlog/issue-20261004-193054-174-bound-interpreter-self-profiling-asset-validation-.md`.

## Global Constraints

- JDK25 idioms; library floor JDK22; Linux-amd64.
- Production pinned Temurin25.0.4.1+1-LTS `-Xint -XX:+UseSerialGC` synchronous openat unchanged.
- Readiness<=10s/session<=60s/stop<=2 remaining seconds/event deadline1s; Kotlin4096 occurrences/33,554,432 bytes and native budgets unchanged.
- Rehash every byte and complete OCI graph; no digest cache, helper self-verification or automatic fallback.
- No production FFM/API/dependency changes in this qualification; subsequent bridge needs explicit approval, confined arenas/immediate errno/owned FDs.
- EPERM/EACCES propagate; unknown algorithms/providers are unsupported, not successful zero hashes.
- No registry/compiler/sudo on library startup; preserve unrelated work and scoped local commits.
- Coverage stays incomplete; no GITHUB_TOKEN handling or seccomp/JVM-floor changes.

---

## File Structure

|File|Responsibility|
|---|---|
|Create `profiler/hotspot-agentless/scripts/kernel-digest.py`|Bounded AF_ALG file hash probe/CLI|
|Create `profiler/hotspot-agentless/scripts/test-kernel-digest.py`|Real known answers, short-read/size and permission refusal|
|Create `docs/plans/2026-10-05-hotspot-kernel-digest-evidence.md`|Kernel availability/performance/provider decision|
|Modify performance backlog issue|Qualification result and approved next integration boundary|

### Task1: Bounded real kernel digest probe

**Files:** Create the two scripts above. No production edits.
**Interfaces:** `digest_file(path: Path, max_bytes: int) -> str`; CLI `kernel-digest.py <file> <max-bytes>` prints lowercase64hex or exits nonzero. No fallback/provider switching.

- [ ] Write failing tests, loading the hyphenated module with importlib:

```python
import importlib.util, pathlib, tempfile, unittest
spec = importlib.util.spec_from_file_location("kernel_digest", pathlib.Path(__file__).with_name("kernel-digest.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class KernelDigestTest(unittest.TestCase):
    def test_known_answers_and_size_refusal(self):
        with tempfile.TemporaryDirectory() as temp:
            path = pathlib.Path(temp) / "input"
            path.write_bytes(b"abc")
            self.assertEqual(module.digest_file(path, 3), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
            with self.assertRaises(ValueError): module.digest_file(path, 2)
            path.write_bytes(b"")
            self.assertEqual(module.digest_file(path, 0), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")

if __name__ == "__main__": unittest.main()
```

Also monkeypatch `module.socket.socket` to raise `PermissionError(1, "denied")`
and assert the identical error propagates; do not mock successful kernel hashing.
- [ ] Run `python3 scripts/test-kernel-digest.py` in the canonical engine. Expect missing module/function before implementation; after implementation unsupported AF_ALG is an explicit failed qualification, not an unconditional test skip.
- [ ] Implement the probe:

```python
import socket
from pathlib import Path

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
                if not block: break
                total += len(block)
                if total > max_bytes: raise ValueError("hash input exceeds byte budget")
                operation.sendall(block, socket.MSG_MORE)
            operation.sendall(b"")
            digest = operation.recv(32)
            if len(digest) != 32: raise ValueError("short kernel digest")
            return digest.hex()
```

The optional-tool kernel call can block: the caller process must have a finite
external timeout, and no production deadline guarantee is inferred from this code.
Use argparse to define the exact2args and print only `digest_file` result.
- [ ] Add tests at55/56/63/64/65bytes,64KiB boundary and1MiB deterministic bytes,
comparing each actual result to `hashlib.sha256(data).hexdigest()`. Assert original
file bytes unchanged and every socket/source closes on size failure. Run tests.
- [ ] Commit only the scripts: `test(profiler): qualify kernel asset digest probe`.

### Task2: Actual asset comparison and native integration decision

**Files:** Evidence and backlog issue. Existing production code is inspected, not modified.
**Interfaces:** Consume Task1 `digest_file`, existing manifest/files and `HotSpotPerformanceMain assets`; produce evidence with hashes, byte counts, timing rows, kernel failures and proposed native bridge contract.

- [ ] Run real candidate helper and all listed OCI blobs, compare every digest:

```bash
cd profiler/hotspot-agentless
timeout 15s python3 scripts/kernel-digest.py build/footprint-candidate-installed/mw-hotspot-collector 536870912
python3 scripts/assets.py verify --bundle build/footprint-candidate-installed
python3 scripts/test-kernel-digest.py
```

Use a Python loop over `manifest["files"]`, assert `digest_file(root/name, limit)==expected`, and record actual consumed byte count from stat plus the probe's streaming budget check. Changing size/mtime/inode must never eliminate hashing.
- [ ] Repeat3serial fresh-process kernel/Java-interpreter pairs on the same readonly
bundle. Use the exact pinned JVM command from the footprint plan; compare these
measurements only, not normal-JIT live admission. Record timeouts/permission failures.
- [ ] Write native bridge design with these exact requirements:

|Native operation|Contract|
|---|---|
|socket|AF_ALG38/SOCK_SEQPACKET5/SOCK_CLOEXEC0x80000; receipt via existing getFdOrThrow with Generic role|
|bind|sockaddr_alg88bytes: ushort family offset0, type14bytes offset2, feat/mask uint32 offsets16/20, name64bytes offset24; type hash/name sha256|
|accept4|CLOEXEC operation receipt, typed live address/length segments in one confined arena|
|sendmsg|Managed msghdr/iovec, MSG_MORE0x8000, checked short-send loop, finite polling/cancellation design; final zero-length send closes hash input|
|recv|Exactly32bytes, negative errno captured immediately; no short result accepted|
|close|Operation then algorithm; preserve primary failure and suppress independent close failures; no invented integers|

Place a future bridge in `platform/src/main/kotlin/io/mazewall/ffi/crypto/KernelSha256.kt`
behind existing NativeEngine. An approved public `@MazewallInternal` bridge and
explicit HotSpotOptions opt-in must preserve original constructors/default descriptors.
Default Java remains available as operator choice, not automatic fallback after
EPERM/EACCES. Read ffm_safety/containment design and run blast-radius before that
separate implementation. This decision is the gate, not permission to write FFM now.
- [ ] Record whether AF_ALG improves real bytes and is available on the actual
supported host. If not, retain rejection and current Java provider; do not claim the
admission issue resolved. Validate links/backlog/whitespace and commit evidence.

## Completion gate

Execution:8867e15c kernel probe/tests; actual whole9file bundle hashes match;
three kernel/interpreter pairs and explicit bridge/deadline/ABI decision retained.
Production Java hashing remains unchanged. See
[evidence](../../plans/2026-10-05-hotspot-kernel-digest-evidence.md).

Known answers and whole real asset digests agree; bounded streams/cleanup and
permission refusal pass; three measured pairs and an explicit reviewed provider
contract exist. Production acceleration requires approval of that native/API
contract and its own host/kernel/ABI/public acceptance before activation.
