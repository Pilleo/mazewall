package capture

import (
	"context"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
	"testing"
)

type recoveringLookup struct {
	sink       *recordSink
	wrongOrder bool
	cancelled  bool
}

func (r *recoveringLookup) Lookup(context.Context, model.StackRef) (model.StackEvidence, error) {
	return model.Unavailable("definition_unavailable", nil), nil
}
func (r *recoveringLookup) RecoverDefinition(ctx context.Context, ref model.StackRef) (bool, error) {
	rows := r.sink.snapshot()
	if len(rows) != 2 || rows[1].Kind != "enrichment" || rows[1].Evidence.Attribution != "unavailable" {
		r.wrongOrder = true
	}
	if ctx.Err() != nil {
		r.cancelled = true
		return false, ctx.Err()
	}
	return true, nil
}

func TestRecoveryOccursOnlyAfterOriginalTerminalOutcome(t *testing.T) {
	sink := &recordSink{}
	lookup := &recoveringLookup{sink: sink}
	e, err := NewEnricher(context.Background(), fixtureOptions(), lookup, sink, bootClockFunc(func() (uint64, error) { return 100, nil }), nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := e.Submit(fixtureEvent("original", 7)); err != nil {
		t.Fatal(err)
	}
	if err := e.Finish(); err != nil {
		t.Fatal(err)
	}
	rows := sink.snapshot()
	if len(rows) != 3 || rows[2].Kind != "recovery" || !rows[2].Recovery.Invalidated || rows[2].Recovery.CorrelationID != 7 || lookup.wrongOrder || lookup.cancelled {
		t.Fatalf("recovery lost or reordered: %+v", rows)
	}
	if rows[1].Evidence.Attribution != "unavailable" || len(rows[1].Evidence.Frames) != 0 {
		t.Fatal("original terminal result changed")
	}
	if e.Stats().RecoveryAttempts != 1 || e.Stats().RecoveryInvalidations != 1 {
		t.Fatal("missing recovery accounting")
	}
}
