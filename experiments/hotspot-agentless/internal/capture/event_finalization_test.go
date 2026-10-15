package capture

import (
	"bytes"
	"context"
	"encoding/json"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
	"io.mazewall/experiments/hotspot-agentless/internal/output"
	"testing"
)

func TestEventDrainDeliversKnownTailAfterStopRequestCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	for _, drain := range []bool{false, true} {
		var buffer bytes.Buffer
		s := eventsOnlyState{sink: output.NewJSONL(&buffer)}
		e := model.Event{EventID: "tail:1", SessionID: "tail", PID: 123, TID: 456, Sequence: 1}
		err := s.Submit(eventDeliveryContext(ctx, Config{EventsOnly: true, DrainEvents: drain}), e)
		if !drain {
			if err == nil || buffer.Len() != 0 {
				t.Fatal("legacy cancellation ignored")
			}
			continue
		}
		if err != nil {
			t.Fatal("known tail discarded after graceful request", err)
		}
		decoder := json.NewDecoder(&buffer)
		var event, terminal model.Record
		if err := decoder.Decode(&event); err != nil {
			t.Fatal(err)
		}
		if err := decoder.Decode(&terminal); err != nil {
			t.Fatal(err)
		}
		if event.EventID != e.EventID || terminal.EventID != e.EventID || terminal.Evidence.Attribution != "skipped" || terminal.Evidence.Reason != "unwind_disabled" || s.stats.Received != 1 || s.stats.Skipped != 1 {
			t.Fatal("tail identity or outcome fabricated", event, terminal, s.stats)
		}
	}
}
