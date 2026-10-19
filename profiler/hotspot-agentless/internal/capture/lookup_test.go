package capture

import (
	"context"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/symbolizer"
	"io.mazewall/experiments/hotspot-agentless/internal/identity"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
	"strings"
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

func TestLegacyLookupAndRecoveryKeepConcreteNilCollectorBarrier(t *testing.T) {
	// Match Run's assignment from its nullable concrete owner, not a nil interface.
	var execBarrier *identity.ExecBarrier
	l := gadgetLookup{symbolizer: &symbolizer.Symbolizer{}, sessionID: "session-A", pid: 123, startTicks: 987, execBarrier: execBarrier}
	ref := fixtureEvent("event", 7).StackRef()
	ref.SessionID = "stale-session"
	for _, recovery := range []bool{false, true} {
		name := "lookup"
		if recovery {
			name = "recovery"
		}
		t.Run(name, func(t *testing.T) {
			defer func() {
				if p := recover(); p != nil {
					t.Fatalf("legacy concrete-nil owner panicked before reference handling: %v", p)
				}
			}()
			var err error
			if recovery {
				_, err = l.RecoverDefinition(context.Background(), ref)
			} else {
				_, err = l.Lookup(context.Background(), ref)
			}
			if err == nil || !strings.Contains(err.Error(), "reference session/process generation mismatch") {
				t.Fatal("did not reach normal reference handling", err)
			}
		})
	}
}

func TestOwnedLookupAndRecoveryStillCheckRealRejectingBarrier(t *testing.T) {
	barrier := &identity.ExecBarrier{}
	if err := barrier.Close(); err != nil {
		t.Fatal(err)
	}
	for _, strict := range []bool{false, true} {
		l := gadgetLookup{strictEpoch: strict, execBarrier: barrier}
		ref := fixtureEvent("event", 7).StackRef()
		if _, err := l.Lookup(context.Background(), ref); err == nil || err.Error() != "exec barrier closed" {
			t.Fatal("owned lookup bypassed real rejecting barrier", strict, err)
		}
		if _, err := l.RecoverDefinition(context.Background(), ref); err == nil || err.Error() != "exec barrier closed" {
			t.Fatal("owned recovery bypassed real rejecting barrier", strict, err)
		}
	}
}
