# Tier E marker v2 pre-admission proof, 2026-09-23

An agent-authoritative startup file records the native agent's **selected** marker ABI,
session tag, and Linux process start time. A registered v2 collector opens that bounded
file through a no-follow descriptor under the configured artifact root and checks the
selection and process incarnation **before** creating the BPF engine or replying `OK`
to `ATTACH`. A symbol or versioned USDT note remains a separate attachment-ABI check,
not proof of runtime emission selection.

`rootful-five-scenarios.tar.zst` retains target/collector logs, proof artifacts, terminal
reports, and coverage manifests for the following non-root target / privileged collector
cases. `manifest.json` binds the run to source diff, kernel, image and native/JAR hashes;
`SHA256SUMS` binds both files.

| Scenario | Result |
|---|---|
| v2 agent, explicit uprobe, 72 paths | 72/72; complete terminal evidence |
| v2 agent, USDT, ring-drop fault | All singletons recovered from map; ring transport incomplete |
| v1-emitting agent, v2 collector | `MARKER_VERSION_MISMATCH` before workload; zero resolved events |
| v2 agent without startup proof | `MARKER_VERSION_MISMATCH` before workload; zero resolved events |
| v1 agent and v1 registration | 72/72; complete terminal evidence |

The agent-produced file is diagnostic producer self-attestation, **not** a cryptographic
attestation or an ACE boundary against a malicious target UID that can modify its own
artifact volume. Tier E remains `policy_eligibility=false`. Missing, stale, malformed,
symlinked, or conflicting proofs fail attachment; a mid-session process-incarnation
change still fails terminal lifecycle admission.
