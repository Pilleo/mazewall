package capture

import (
	"context"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
	"testing"
)

type eventsTestSink struct{ records []model.Record }

func (s *eventsTestSink) Write(_ context.Context, r model.Record) error {
	s.records = append(s.records, r)
	return nil
}
func TestEventsOnlyPreservesOccurrenceAndExplicitSkippedTerminal(t *testing.T) {
	s := &eventsTestSink{}
	e := model.Event{SessionID: "s", EventID: "s:1:2", PID: 123, TID: 456, Sequence: 2}
	state := eventsOnlyState{sink: s}
	if err := state.Submit(context.Background(), e); err != nil {
		t.Fatal(err)
	}
	if len(s.records) != 2 || s.records[0].Event.EventID != e.EventID || s.records[1].Evidence.Attribution != "skipped" || s.records[1].Evidence.Reason != "unwind_disabled" || state.stats.Received != 1 || state.stats.Skipped != 1 {
		t.Fatal("collection-only result fabricated enrichment", s.records, state.stats)
	}
}
