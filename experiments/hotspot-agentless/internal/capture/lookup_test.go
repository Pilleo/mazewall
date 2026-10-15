package capture

import (
	"context"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/symbolizer"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
	"testing"
)

func TestLookupRejectsAnotherSessionOrProcessGenerationBeforeProvider(t *testing.T) {
	l := gadgetLookup{symbolizer: &symbolizer.Symbolizer{}, sessionID: "session-A", pid: 123, startTicks: 987}
	for _, change := range []func(*model.StackRef){
		func(r *model.StackRef) { r.SessionID = "session-B" },
		func(r *model.StackRef) { r.ProcessStartTicks = 988 },
		func(r *model.StackRef) { r.PID = 124 },
	} {
		r := fixtureEvent("event", 7).StackRef()
		change(&r)
		if _, err := l.Lookup(context.Background(), r); err == nil {
			t.Fatal("stale reference accepted")
		}
		if _, err := l.RecoverDefinition(context.Background(), r); err == nil {
			t.Fatal("stale recovery reference accepted")
		}
	}
}
