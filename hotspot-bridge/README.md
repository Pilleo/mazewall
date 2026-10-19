# HotSpot bridge

Module `github.com/Pilleo/mazewall/hotspot-bridge` uses Go 1.26 and only the
standard library. It supplies bundle verification, plain wire DTOs, bounded
schema2/3 consumption and canonical observed-stack content IDs. The **v0.2.0 source**
adds format3 bundle negotiation. Published v0.1.0 stays immutable at
`1a6d58d2c73f5b089588add7364bf8d7913c5604`; its verifier does not gain this behavior.
Neither DTO/decoder nor bundle support admits a native runtime profile.

`bundle.Verify(root, expectedManifestSHA, wire.Strict25)` checks the actual
manifest bytes against an independently trusted SHA256 before parsing, hashes
every listed file, and verifies the selected transitive local OCI graph. It does
not run the collector or trust helper self-verification. Limits remain 65,536
JSON bytes, 256 files and 512 MiB. The local candidate also accepts
`wire.Observed25` with collector-version3/wire3 and capture ABI2, and binds that
profile in `Verified.Expected`. Strict2 and observed3 bundles refuse cross-profile
verification. Published v0.1.0 rejects Observed25 and maps Expected to Strict25.

The opaque `Verified` result has checked `Collector`, `Store`, `Image`,
`Expected(target)` and `Manifest` getters. Zero/failed results are unusable.
`Manifest` deep-copies its file inventory for native policy and installation.
The token records verification, not ownership of descriptors or an immutable
filesystem snapshot: assets must stay operator-owned/read-only through execution.
Consumers must preserve actual execution errors. Portable mode-bit checks do not
establish effective ACL/mount execute permission; native tooling retains X_OK
checks and its original exact provenance pins.

The exported `OwnedAsset`, `BoundedJSON`, `DigestName`, `FileHash` and `OCIGraph`
helpers serve existing native copying, OCI selection/packing, preparation and
distribution callers. They do not create verified tokens; there is one shared
business validator. Hash/JSON readers retain joined read and close error causes.
`ManifestSHA256` uses the bounded reader for the legacy native packaging wrapper;
its local checksum is not an independently trusted deployment pin.

For candidate behavior, use an **explicit local workspace** with this module and
one native owner, plus the owner's actual prepared replacements. Canonical and
experimental owners share a module path and require separate workspaces. Follow
the governing plan's setup, setting `GOWORK` to the generated `go.work` after
`go work init` and before `go work edit`.
Never combine workspace mode with `-modfile`. Native modules pin real v0.1.0;
GOWORK=off selects those released bytes, not this candidate's new bundle behavior.
No absolute replacement is committed in release module declarations.
Native source distributions include the selected bridge source from that graph.

## Wire consumer

`wire.NewDecoder(Expected)` binds an independently supplied schema/profile,
provider and raw-tick target identity. Feed one JSON object per `Accept(line)`,
**without its line-ending delimiter**. `Ready` returns an owned lifecycle copy.
`Finish(waitErr)` is the input owner's declaration that it reached actual EOF,
joined readers and performed child Wait. It checks the final summary and logical
counts; it cannot observe OS EOF or physical retirement itself. A future process
supervisor must prove those facts before calling it. No time/TID-based joins occur.

Schema3 requires explicit `policy_eligibility:false` on every row and ready's
`upstream_generation_checks`, cgroup and mount namespace. Schema2 absence remains
absent. Schema2 lifecycle cannot assert cgroup/mount identity: events still require
the independently expected cgroup, so an event-bearing session needs it supplied.
Start may omit process birth and JVM IDs; ready must bind them.
Strict schema2 correlated success additionally requires a native-valid stable
captured HotSpot epoch and matching checked terminal epoch/profile; zero is valid.
Unavailable/skipped outcomes and schema3 unknown observations do not gain that claim.
Required raw payload/frame/recovery/observation members must be present and nonnull;
optional locations and standalone DTO round trips retain their original semantics.
Per-CPU conservation is validated by the native producer, not replayed from this aggregate-only wire.
Selected loss and unavailable/skipped/partial stacks can complete logically while
definition/enrichment/wire/retention loss lanes remain unproven. No completed
capture proves global collection completeness or policy eligibility.

Limits: 1 MiB/line, nesting64, 65,536 rows, 32,768 tracked occurrences,
4,096 frames/terminal and at most4,096 retained pairs/32 MiB of retained wire
payload, including raw metadata. One maximum-sized summary row is reserved.
Retention drops whole pairs; all received/discarded occurrences, terminals and
recoveries still reconcile. Hard parse/state/identity/physical-finality failures
are sticky and return no publishable Capture; Ready retains diagnostic identity.

Unknown values are owned `json.RawMessage` entries in DTO `Extensions` maps.
`Capture.RawJSONL` additionally preserves original retained rows, including unknown
outer/provider fields and whitespace. Its rows can omit discarded pairs/recoveries;
it is not necessarily a complete replay stream. `RawSHA256` covers **all accepted
input rows plus one LF per row**, even discarded rows. Typed uint64 values never
pass through floating point. Invalid UTF8, duplicate keys and trailing data fail.
Returned frames, identity/mapping/source pointers, raw bytes and summary slices/
pointers are independent copies. Public DTO JSON methods preserve extensions;
the stricter stream/trust-boundary checks belong to Decoder.

Use `errors.Is` with `ErrJSON`, `ErrLimit`, `ErrIdentity`, `ErrOrder`, `ErrPairing`,
`ErrAccounting`, `ErrFinality`, `ErrCollector` and `ErrProcess`. Collector messages
and errno causes, and the original Wait error, are retained rather than downgraded.
`ParseStartTicks` is the same pure field22 parser used by native preflight.

`CanonicalStackID(Scope, []Frame)` hashes ordered **typed observable content** with
image/JVM/profile/provider scope. It excludes occurrence/session/PID/TID/correlation
identity, unknown raw extensions and absolute Mapping.Start/End. Mapping file
identity/offset and every other typed frame/quality/BCI field remain significant.
It rejects empty/error-frame stacks and never mutates caller frames or mappings.
It groups provider observations, not universal class-loader/method identities.

The v0.1.0 module Sum is `h1:tWCzIDVBquWo6tWwuqIT5oKlfRPP+XMshdJ8B7hFcIs=`;
GoModSum is `h1:FEiVroH+pQOmKfe6YXTFkvkpAF3z799E4hFDPza6e/s=`.
Hermetic consumers require the actual `hotspot-bridge/v0.2.0` release and its
remotely resolved origin/checksums before selecting format3 behavior. Release
preparation is not proof that this tag is already published: require a release
receipt with the actual remote tag, commit and checksums. Local workspace checks
supply neither a published dependency nor native runtime qualification.

Standalone checks: `GOWORK=off go test -race ./... -count=1`.
Native checks: with the selected explicit `GOWORK`, run
`go test -race -buildvcs=false ./internal/model ./internal/preflight ./internal/capture ./internal/accounting ./internal/tooling -count=1`
in each owner.

## Release isolation check

Before publication, test the committed module alone in a fresh directory with no
`profiler` sibling. All SDK test inputs, including the historical lifecycle rows
at the start of `wire/testdata/legacy-serialized.jsonl`, ship inside this module.
From the repository root, using the installed Go toolchain:

```bash
set -eu
isolated=$(mktemp -d)
git archive HEAD hotspot-bridge | tar -x -C "$isolated"
(
    cd "$isolated/hotspot-bridge"
    GOWORK=off GOPROXY=off GOTOOLCHAIN=local GOFLAGS=-buildvcs=false go test -race ./... -count=1
)
```

Keep the source inventory and test receipts. For uncommitted release preparation,
test an exact owned copy of the SDK working-tree inventory first, then compare the
new commit's archive inventory with those tested bytes. This is a local packaging
check; SDK tag/push/publication still requires separate operator authorization.
