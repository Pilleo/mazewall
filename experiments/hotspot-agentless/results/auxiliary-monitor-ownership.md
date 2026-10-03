# Auxiliary profiler cancellation and join checkpoint

Targeted source and behavioral review after `44451cfd`. The checked metadata
watermark fix remains in place. This checkpoint identifies a separate auxiliary
worker lifecycle gap; it is not independent approval or a remediation claim.

## Actual owned work outside definition completion

The pinned profiler `ef6cf29492c8` has these additional paths:

- `Tracer.StartPIDEventProcessor` launches `processPIDEvents` without a completion
  acknowledgment. That worker synchronizes process metadata and runs PID cleanup.
- `StartMapMonitors` starts a PID-map periodic/manual callback, sends discovered
  PIDs to `t.pidEvents` without selecting context cancellation, and discards the
  returned periodic stop function.
- A second map-monitor periodic callback reads native metrics, again without a
  join acknowledgment.
- The report-event perf reader, process-manager periodic metric collector and
  kernel-symbol monitor have separate lifetimes from trace-definition readout.

The strict resolver Close path asks the already-activated definition drain owner
to finish. That branch does not invoke the cancellation used by the ordinary
StopDefinitions path, and then calls tracer Close. Successful definition reader/
consumer completion therefore is not proof that these auxiliary callbacks stopped.
The collector eventually cancels its owned context, but cancellation alone is not
a callback join. Native hook retirement and Gadget retirement cannot be advertised
as a universal, acknowledged auxiliary-worker teardown.

Source boundaries: profiler `tracer/events.go:40-57`, `tracer/tracer.go:1165-1200`,
`periodiccaller/periodiccaller.go:42-64`, `processmanager/manager.go:143-184`; Gadget
resolver `Close` and the local `shutdown.go.txt` definition-owner branch. This
review does not assert that tracer Close itself closes every map/program FD.

## Deterministic characterization

The upstream periodic stop function only stops a ticker. Tests against the exact
pinned implementation prove:

1. After the returned stop function finishes, an explicit manual trigger still
   invokes the callback. The test uses a one-hour ticker to exclude timer ticks.
2. An explicitly entered callback remains held after both context cancellation
   and ticker stop return. Only releasing its gate allows completion.

No timing sleeps or native fault suppression are used. One-second waits bound test
failure/cleanup; callback ordering is established by entry and release channels.
These tests characterize the upstream primitive used by the actual PID callback,
not a claimed live native use-after-close or an implemented fix.

```bash
go test -race -modfile build/experiment.go.mod \
  go.opentelemetry.io/ebpf-profiler/periodiccaller -count=1 -v
```

Both new tests and the existing periodiccaller suite pass. They are now included
in `make verify`, which passes with the existing host/ownership/epoch suites.
The repository `./gradlew build` gate also passes. Characterization bundle:
`971bec67edca3640af90fde00369d82cd8980e0aa0ce78f9da91d1092c70b93f`.

## Next scoped remedy

Track separate auxiliary-worker ownership and stop admission, make PID sends
cancellation-aware, and acknowledge periodic/PID/report-reader completion before
retiring borrowed native resources. Definition draining, final consumer handoff
and enrichment must retain their independent lifetime until complete. If a callback
cannot join within the existing remaining stop budget, retain its resources and
report an incomplete shutdown rather than claiming retirement. Include native
reader/close error propagation and compatibility tests.

Tracked in [auxiliary ownership issue](../../../docs/internals/backlog/issue-20261003-132317-306-join-agentless-auxiliary-profiler-monitors-before-.md).
Complete loss, broader JVM lifetime and independent review remain distinct gates.
