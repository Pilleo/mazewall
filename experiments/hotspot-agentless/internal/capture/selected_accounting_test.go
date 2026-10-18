package capture

import (
	"context"
	"encoding/json"
	"math"
	"testing"

	gadgetcontext "github.com/inspektor-gadget/inspektor-gadget/pkg/gadget-context"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/operators"
	"io.mazewall/experiments/hotspot-agentless/internal/accounting"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
)

func TestSelectedDiagnosticRequiresIndependentIdentityFinalOwnerAndConservation(t *testing.T) {
	expected := accounting.Binding{Session: "s", PID: 123, Birth: 456, MapID: 7, PossibleCPUs: 2}
	value := operators.SelectedAccountingObservation{Target: operators.SelectedAccountingTarget{Session: "s", PID: 123, Birth: 456}, MapID: 7, PossibleCPUs: 2, Rows: []operators.SelectedCPUCounts{{Attempted: 3, Submitted: 2, ReserveFailed: 1}, {}}, Quiesced: true, Joined: true}
	parent := model.EventFinalizationObservation{Phase: "resources_retired", ResourcesRetired: true, ProducersQuiesced: true, ReceiverJoined: true, FinalCounterRead: true, BufferDrained: true}
	stats := model.Stats{Received: 2, Resolved: 1, Unavailable: 1}
	got, err := selectedDiagnostic(expected, value, &parent, stats)
	if err != nil || !got.Complete || got.Attempted != 3 || got.Submitted != 2 || got.ReserveFailed != 1 || got.Terminals != 2 {
		t.Fatal(got, err)
	}
	for _, kind := range []string{"map", "birth", "session", "cpu", "overflow", "contradiction", "terminal_overflow", "retired", "phase", "counter", "drained", "parent_error"} {
		t.Run(kind, func(t *testing.T) {
			v, p, s := value, parent, stats
			v.Rows = append([]operators.SelectedCPUCounts(nil), value.Rows...)
			switch kind {
			case "map":
				v.MapID++
			case "birth":
				v.Target.Birth++
			case "session":
				v.Target.Session = "other"
			case "cpu":
				v.Rows = v.Rows[:1]
			case "overflow":
				v.Rows[0].Overflow = 1
			case "contradiction":
				v.Rows[1].Submitted = 1
			case "terminal_overflow":
				s.Resolved = math.MaxUint64
			case "retired":
				p.ResourcesRetired = false
			case "phase":
				p.Phase = "receiver_joined"
			case "counter":
				p.FinalCounterRead = false
			case "drained":
				p.BufferDrained = false
			case "parent_error":
				p.Error = "native failure"
			}
			if got, err := selectedDiagnostic(expected, v, &p, s); err == nil || got != nil {
				t.Fatal("invalid selected proof emitted", got, err)
			}
		})
	}
	if got, err := selectedDiagnostic(expected, value, nil, stats); err == nil || got != nil {
		t.Fatal("missing parent certified")
	}
	summary := model.Summary{SelectedAccounting: got}
	applyCollectionHealth(&summary, model.LossObservation{Complete: false})
	if summary.EventLossObservation.Complete {
		t.Fatal("selected proof promoted global loss")
	}
	raw, err := json.Marshal(model.Summary{})
	if err != nil {
		t.Fatal(err)
	}
	var absent map[string]any
	if err := json.Unmarshal(raw, &absent); err != nil {
		t.Fatal(err)
	}
	if _, ok := absent["selected_event_accounting"]; ok {
		t.Fatal("legacy summary acquired selected capability")
	}
}

type selectedOwnerFixture struct {
	value operators.SelectedAccountingObservation
}

func (o selectedOwnerFixture) SelectedAccountingBinding() (operators.SelectedAccountingObservation, bool) {
	return o.value, true
}
func (o selectedOwnerFixture) EventFinalization() (operators.EventFinalizationObservation, bool) {
	return operators.EventFinalizationObservation{SelectedAccounting: &o.value}, true
}

func TestSelectedExpectedRejectsMissingOrForeignPreparedOwner(t *testing.T) {
	gc := gadgetcontext.New(context.Background(), "selected-expected")
	defer gc.Cancel()
	if _, err := selectedExpected(gc, "s", 123, 456); err == nil {
		t.Fatal("missing prepared owner accepted")
	}
	gc.SetVar("ebpfInstance", struct{}{})
	if _, err := selectedExpected(gc, "s", 123, 456); err == nil {
		t.Fatal("missing prepared capability accepted")
	}
	valid := operators.SelectedAccountingObservation{Target: operators.SelectedAccountingTarget{Session: "s", PID: 123, Birth: 456}, MapID: 7, PossibleCPUs: 2}
	for _, kind := range []string{"valid", "session", "pid", "birth", "map", "cpus"} {
		value := valid
		switch kind {
		case "session":
			value.Target.Session = "foreign"
		case "pid":
			value.Target.PID++
		case "birth":
			value.Target.Birth++
		case "map":
			value.MapID = 0
		case "cpus":
			value.PossibleCPUs = 0
		}
		gc.SetVar("ebpfInstance", selectedOwnerFixture{value: value})
		binding, err := selectedExpected(gc, "s", 123, 456)
		if kind != "valid" {
			if err == nil {
				t.Fatal("foreign prepared owner accepted", kind)
			}
			continue
		}
		if err != nil || binding.MapID != 7 || binding.PossibleCPUs != 2 {
			t.Fatal(binding, err)
		}
		final, err := selectedFinal(gc)
		if err != nil || final.MapID != 7 {
			t.Fatal(final, err)
		}
		value.MapID = 99
		if binding.MapID != 7 {
			t.Fatal("prepared identity aliases later observation")
		}
	}
}
