# Vibe and Antigravity Configuration Contract

## Decision

Vibe and Antigravity use a split contract:

- **Operator-visible:** non-secret execution policy that can safely be shown and
  validated by Paperclip's agent-configuration UI.
- **Deployment-only:** host commands, credentials, secret references, and any
  provider-specific field not exposed by the authoritative adapter schema. These
  values are owned by platform deployment and must not be inferred or copied into
  a UI default.

This document records the contract as observed on 2026-08-29. It deliberately
does not manufacture provider fields while the adapter schemas are inaccessible
to the assigned agent identity.

## Evidence and current state

Both current agent records have `adapterConfig: {}` and `runtimeConfig: {}`:

| Provider | Agent | Adapter type | Current provider fields |
|---|---|---|---|
| Vibe ACP Developer | `d159bcf4-4a01-4fd8-9007-bad4aababfeb` | `vibe` | none |
| Antigravity ACP Developer | `9b698c64-d601-4aa4-ac45-9249883c0be0` | `antigravity` | none |

The authenticated agent API permits reading the agent records but rejects
`/api/adapters/{type}/config-schema` with `Board access required` and rejects
agent configuration/revision reads with `Missing permission:
agents:suggest-changes`. Therefore the currently configured provider-field
inventory is complete (empty), but the adapter's available-field inventory is
not authoritatively available in this execution context.

## Field inventory and classification

| Field | Providers | Visibility | Owner | Validation/default | Migration disposition |
|---|---|---|---|---|---|
| `adapterType` | both | operator-visible | Paperclip board | enum: `vibe` or `antigravity`; no implicit provider switch | retain as the selected adapter |
| `adapterConfig` provider fields | both | schema-dependent | adapter maintainer + board | must be derived from `/api/adapters/{type}/config-schema`; no inferred defaults | currently empty; add only after schema export is reviewed |
| `runtimeConfig` provider fields | both | schema-dependent | runtime/deployment owner | must be derived from the adapter schema; secret-bearing values are deployment-only | currently empty; do not migrate guessed keys |
| ACP executable/arguments | both | deployment-only | host/platform operator | exact installed command; fail closed if unavailable | Vibe resolves `vibe-acp`; Antigravity resolves `agy --acp` in `AcpChatModel` |
| CLI/API credentials and secret references | both | deployment-only | secrets/platform operator | secret reference only; no plaintext UI default | do not copy, infer, or migrate into configuration fields |
| repository checkout and workspace access | both | deployment-only | Paperclip environment owner | environment must provide the checked-out workspace | preserve environment ownership |
| agent identity, title, capabilities, reporting line, budget, status | both | operator-visible | Paperclip board | validated by the generic agent PATCH schema; explicit existing values | unchanged; outside provider configuration |

## Operator-visible policy fields implemented outside provider configuration

The hybrid supervisor has independent operator-visible controls. They are not
Vibe/Antigravity adapter fields, but must not be confused with deployment
configuration:

| Field | Validation | Default |
|---|---|---|
| `PAPERCLIP_COMPONENT_ROUTES` | comma-separated `component=urlKey` mappings | no overrides |
| `PAPERCLIP_EXTRA_LOOP_ADAPTERS` | explicit adapter-type allow-list | empty (Vibe/Jules policy only) |
| `PAPERCLIP_MAX_DISPATCH` | positive integer | `1` |
| `PAPERCLIP_TICK_SECONDS` | positive integer | `30` |
| `PAPERCLIP_CI_STUCK_MINUTES` | positive integer | `15` |
| `PAPERCLIP_FORCE_IDENTIFIER` | existing issue identifier | unset |

`PAPERCLIP_API_KEY`, provider credentials, and host command paths are
deployment-only even though they are environment variables. They have no
defaults in this contract.

## Required board-side completion

The board owner must export the authoritative config schemas for `vibe` and
`antigravity` (or grant this issue identity read access to them). For every
schema field, the board-side change must record: field name, type, requiredness,
visibility, owner, validation, explicit default if operator-visible, and
migration disposition. Any field that can select an executable, transmit a
credential, or alter workspace/network authority remains deployment-only unless
the adapter schema and security owner explicitly approve UI exposure.

## References

- `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/AcpChatModel.kt`
- `tools/orchestrator/src/main/kotlin/io/mazewall/orchestrator/HybridSupervisor.kt`
- `tools/orchestrator/README.md`
- `docs/internals/designs/tools/paperclip-hybrid-migration.md`
