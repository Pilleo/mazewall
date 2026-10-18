# HotSpot bridge

Module `github.com/Pilleo/mazewall/hotspot-bridge` uses Go 1.26 and only the
standard library. This source checkpoint supplies bundle verification and the
minimal profile/target/provider vocabulary. It is unpublished; the wire decoder
and observed-profile bundle format are subsequent tasks.

`bundle.Verify(root, expectedManifestSHA, wire.Strict25)` checks the actual
manifest bytes against an independently trusted SHA256 before parsing, hashes
every listed file, and verifies the selected transitive local OCI graph. It does
not run the collector or trust helper self-verification. Limits remain 65,536
JSON bytes, 256 files and 512 MiB. Observed25 is vocabulary only and is rejected.

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

Before publication, use an **explicit local workspace** with this module and
one native owner, plus the owner's actual prepared replacements. Canonical and
experimental owners share a module path and require separate workspaces. Follow
the governing plan's setup, setting `GOWORK` to the generated `go.work` after
`go work init` and before `go work edit`.
Never combine workspace mode with `-modfile`. No registry version or absolute
replacement has been added to release module declarations.
Native source distributions include the selected bridge source from that graph.

Standalone checks: `GOWORK=off go test -race ./bundle ./wire -count=1`.
Native checks: with the selected explicit `GOWORK`, run
`go test -race -buildvcs=false ./internal/tooling -count=1` in each owner.
