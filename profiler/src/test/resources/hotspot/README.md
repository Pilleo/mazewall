# HotSpot replay fixture provenance

`measured-event-projection.jsonl` copies the event and provider metadata from
`experiments/hotspot-agentless/build/benchmark.ab5812c8/full-0/events.jsonl`,
occurrence `e600a9611e1c4b50a2929989785c1399:8:61`, measured token0/route0.
The provider bundle is
`e908bb2689936481f8a696dd2347457cc44b6344c1fa78ca65b37772032703eb`.

The original provider sequence has25 frames. This fixture deliberately retains
only its first two `BenchmarkFixture` application frames, in their original order,
with unchanged field values. It is a compact fidelity projection, not full-stack
or live-collector acceptance. The one-occurrence summary is synthetic and omits
physical drain claims; it allows finite replay counter checking without claiming
the original session received only one event. Unit-generated packets cover hostile
ordering, generation, field/domain, bounds and failure cases separately.

`production-lifecycle-projection.jsonl` retains the unmodified schema-2
`session_started` and `collector_ready` records from
`experiments/hotspot-agentless/build/capture.O5KKc5/events.jsonl`. It is a lifecycle-only
projection: the original 400 event/terminal pairs and final summary are omitted.
EOF of this projection is not session completion. The start marker contains the
requested profile; only the later ready record contains admitted JVM build and
process-generation identity. Native readiness does not claim first-event useful
attribution. See `docs/plans/2026-10-04-mazewall-hotspot-protocol-milestone.md`.
