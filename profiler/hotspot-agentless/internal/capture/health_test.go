package capture

import (
	"go.opentelemetry.io/ebpf-profiler/tracer"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
	"testing"
)

type traceHealth struct{ known bool }

func (t traceHealth) TraceReaderHealth() (tracer.TraceReaderObservation, bool) {
	return tracer.TraceReaderObservation{Lost: 5, NoData: 2, ReadError: 1, BufferBytesPerCPU: 524288}, t.known
}

func TestTraceReaderObservationKeepsDefinitionLossUnknown(t *testing.T) {
	h := reportedDefinitionHealth(traceHealth{known: true})
	if h == nil || h.Lost != 5 || h.NoData != 2 || h.ReadError != 1 || h.Complete || h.BufferBytesPerCPU != 524288 {
		t.Fatalf("incorrect trace-reader observation: %+v", h)
	}
	if reportedDefinitionHealth(traceHealth{}) != nil || reportedDefinitionHealth(nil) != nil {
		t.Fatal("unknown reader exposed zeros")
	}
}

type lossCount uint64

func (c *lossCount) ReportedLoss() uint64 { return uint64(*c) }

func TestReportedLossSnapshotNeverClaimsCompleteCollection(t *testing.T) {
	var count lossCount = 7
	health := reportedEventHealth(&count)
	if health.Observed == nil || *health.Observed != 7 || health.Complete || health.Semantics != "forwarded_reports_lower_bound" {
		t.Fatalf("loss accounting falsely complete: %+v", health)
	}
	count = 9
	if *health.Observed != 7 {
		t.Fatal("snapshot mutated after read")
	}
	unknown := reportedEventHealth(nil)
	if unknown.Observed != nil || unknown.Complete {
		t.Fatal("unsupported counters replaced with zero")
	}
	s := model.Summary{}
	applyCollectionHealth(&s, health)
	if s.CollectionHealth != "partial" || s.EventLoss != nil || s.EventLossObservation == nil {
		t.Fatal("partial observation became final event loss")
	}
}
