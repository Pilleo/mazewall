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
