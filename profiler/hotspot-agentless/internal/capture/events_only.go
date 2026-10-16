package capture

import (
	"context"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
)

type eventsOnlyState struct {
	sink  model.Sink
	stats model.Stats
}

func (s *eventsOnlyState) Submit(ctx context.Context, e model.Event) error {
	if err := s.sink.Write(ctx, model.Record{SchemaVersion: 1, Kind: "event", SessionID: e.SessionID, EventID: e.EventID, Event: &e}); err != nil {
		return err
	}
	s.stats.Received++
	v := model.StackEvidence{Attribution: "skipped", Walk: "unknown", ScopeLookup: "unknown", Reason: "unwind_disabled", Frames: []model.Frame{}}
	if err := s.sink.Write(ctx, model.Record{SchemaVersion: 1, Kind: "enrichment", SessionID: e.SessionID, EventID: e.EventID, Evidence: &v}); err != nil {
		return err
	}
	s.stats.Skipped++
	return nil
}
