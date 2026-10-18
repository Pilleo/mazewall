# Wire fixture provenance

`legacy-serialized.jsonl` is the original native model's JSON serialization at
Task 2 BASE `7a6056622c2a86cd7f3d398ff803628ef195af8b`, before DTO migration.
SHA256: `78d4678c84eee76a376b7fb11db86cc1f6a709910f34d6be5c06b85cd75198e7`.
The original command/output receipts are in `build/kubescape-dev/task-2/`.

Rows 1–2 are the unmodified real schema2 lifecycle projection from
`profiler/src/test/resources/hotspot/production-lifecycle-projection.jsonl`
(SHA256 `2eeec0ccd14257e8bb4328280f4ed7e4cede6b6d543e6bc92c4082fc2dcb3735`).
They originally came from `experiments/hotspot-agentless/build/capture.O5KKc5/events.jsonl`.
The historical e908 provider hash is intentionally unchanged. These two rows alone
are not a complete session and cannot pass Finish.

Rows 3–5 serialize `profiler/src/test/resources/hotspot/measured-event-projection.jsonl`
(SHA256 `f8f1674326e7fc08cb9de310a631462d29bf964aaa430d20e94602854e5e6720`).
Its event and two retained application frames are real; its one-occurrence summary
is synthetic. Native serialization fills the original non-omitempty zero counters;
this does not change or relabel the original projection. See that directory's
README for the full original capture provenance and limitations.

The complete schema3 session and adversarial cases are **synthetic**, constructed
as DTOs in `decoder_test.go` and `decoder_boundaries_test.go`. PID123/birth456/
cgroup789/mount42, JVM/provider hex values, methods and image/container names are
test values, not admitted native assets or an observed runtime result.
