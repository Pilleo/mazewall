# PR540 failure-fidelity follow-up — local evidence

## Reviewed source and delta

- Task1: `5b9d85e73d0d7f283919a327a6f881d7bdb6a2b9` suppresses failed owned
  schema2/schema3 summaries before existing error wrappers; success/schema1 and
  pending-readout behavior are preserved.
- Task2: `be3987a686dfbe49262b294c7edb253e9e8d2172` adds the approved bounded
  schema3 `Lifecycle.ErrnoCauses` transport/validation/ownership and real native
  JSON-to-SDK regressions. Primary errno/message and original Go causes survive;
  no failed Capture becomes successful. Both task reviews are PASS/APPROVED, not
  maintainer, release or merge approval.
- SDK release delta now includes wire DTO/decoder/copy/tests as well as the prior
  format3 bundle verifier. Historical93837185/452-pass evidence is not relabeled.

## Exact-revision checks

Local receipts live under
`.superpowers/sdd/2026-10-19-pr540-review-next-steps/`, in `task-1-report.md`,
`task-2-report.md`, `task-2-evidence/` and `task-3-evidence/`.

| Check | Actual result / reuse boundary |
|---|---|
| Task1 actual wrappers/JSON/SDK | Both owners pass sole EACCES and cancellation+EACCES controls with empty Capture; injected acquisition and supplied Wait error, not native attach/child Wait. Historical RED retains the failed-summary-first failure. |
| Task2 SDK | `GOWORK=off go test -race ./... -count=1` passes bundle/wire; validates compound causes, malformed inputs, identity binding, deep ownership, unchanged ordinary/schema2 golden bytes. |
| Independently archived SDK outside any repository | `GOWORK=off GOPROXY=off GOTOOLCHAIN=local GOFLAGS=-buildvcs=false go test -race ./... -count=1` passes (bundle1.315s/wire229.749s), Go1.26.8 linux/amd64. |
| Both native owners, distinct existing prepared workspaces | Task2 `go test -race -buildvcs=false ./internal/model ./internal/preflight ./internal/capture ./internal/accounting ./internal/tooling -count=1` passes, final capture15.437s/17.173s. |
| Missing owning identity suite | Task3 `go test -race -buildvcs=false ./internal/identity -count=1 -v` passes all5tests in each owner's separate workspace. No combined identical-ModulePath workspace or GOWORK/-modfile mixing. |
| Archived dependency isolation | Task3 uses the same offline/local-toolchain environment for `go list -deps -test -json ./...`:131entries, only6nonstandard entries (SDK bundle/wire plus test variants), all inside the isolated SDK. |
| Parity / reuse | Task3 checks all28SDK file hashes against the tested archive and committed subtree, all551tracked SDK/native-owner source files against be3987a6, all4fix mirror pairs and44cumulative canonical-changed shared Go/patch pairs byte-for-byte. |
| Original full JVM checkpoint | Task2's unchanged configured hook passed `./gradlew build :portals:runtime:integrationTest :portals:runtime:test --no-configuration-cache --no-parallel` in11m2s. Task3 attempt1 failed in8m30s at `:profiler:integrationTestFreshJvm`, cold process-wide network loopback socket timeout; that commit was aborted. Original raw log/XML remain immutable in `task-3-evidence/`. |
| Separate focused diagnostic | Exactly one unchanged cold-network test passed1/0/0 in2.208s. Investigation located expiry of the5s socket wall budget before direct connect, not worker/child budget expiry. Real socket/directory ENOSYS cleanup errors also occurred; initiating delay remains UNPROVED (`hook-investigation/report.md`). |
| Authorized complete checkpoint | Pending at authoring: exactly one further unchanged full configured commit hook. Its actual outcome/SHA/full XML and custody belong to the local `task-3-report.md` and distinct `task-3-evidence/checkpoint-2/` receipts, not a pre-commit success claim. |

The archived SDK subtree is `c2d85585e636daf56748519264196a00888c0503` (28files);
historical tested tar SHA256 is
`5d98cf307d2804376cefe520284364f748f8f679cc7c115c6b14c8934d634b14`.
It was tested at `/tmp/opencode/pr540-task2-sdk-yvxjbprm/hotspot-bridge`, with no
profiler sibling, symlink or ancestor repository. Task3 rechecks the extracted
bytes and committed inventory rather than repeating valid expensive race suites.
Tar commit metadata may differ; subtree/file equality, not tar-byte identity,
justifies reuse. Prepared native replacements remain the separately owned473acb5a
graph with the local candidate SDK; this is not a hermetic released dependency graph.

## Limits and remaining gates

Native patch/BPF fingerprint remains
`89b3599935df5206ff710292574e4480a09da535f8529b4b0462b8c29a7e0f50`.
These Go-only fixes change collector bytes: old helper hashes, bundle inventories
and native receipts do **not** qualify the changed helper. Strict Kotlin serialized
path/assets did not change; no additional focused Kotlin check was needed beyond
the full hook. No module/sum, native asset, JVM pin or release identity changed.

At that historical checkpoint both fix issues remained open (146active=145open+1deferred;
714resolved). ADK validation passes; the optional orchestrator schema validator's
254proven baseline errors are not a new Task3 failure. Task3's original failed
full hook is not waived by the focused pass or independent broad PASS/APPROVED
review, and is not explained by shared ENOSYS output. The single authorized full
checkpoint is pending at authoring; stop if it fails. Even a later unchanged full
checkpoint pass would not fix the initiating delay, prove physical retirement or
qualify changed Go helper bytes. No bypass or runtime repair is claimed.

No publication, PR mutation, provisioning or Tasks4–6 implementation occurred.
Both owned disposable VM/kind resources are RETIRED; no current Ready environment
is claimed. Actual released SDK origin/sums, rebuilt bundles, native attach/real
child Wait/retirement, provider handoffs and Kubernetes acceptance remain separate
[release](2026-10-19-hotspot-bridge-release.md#ordered-gates) and
[shared ownership](2026-10-18-hotspot-observed-profile-contract.md#shared-contract-and-ownership) gates.

The [subsequent shared-review checkpoint](2026-10-19-hotspot-bridge-release.md#current-shared-review-checkpoint)
records the Go/Kotlin OCI repairs and provider guards, the delegated existing-Apache
check and three locally addressed node defects. All six review issues remain open.
The prior combined configured root/portal source gate passed at its recorded
revision, not for the subsequent selected-root fixes awaiting their final source
gate. Neither that pass nor this older SDK inventory/full-hook receipt qualifies
new helper bytes; actual SDK/storage publication, consumer pins and released-graph/native
qualification remain blocked.
