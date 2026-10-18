package capture

import (
	"bytes"
	"context"
	"encoding/json"
	"reflect"
	"testing"

	"github.com/inspektor-gadget/inspektor-gadget/pkg/operators"
	"io.mazewall/experiments/hotspot-agentless/internal/accounting"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
)

func TestFinalSummaryInventoryUsesNativeSelectedProofOnly(t *testing.T) {
	b := accounting.Binding{Session: "s", PID: 123, Birth: 456, MapID: 7, PossibleCPUs: 2}
	v := operators.SelectedAccountingObservation{Target: operators.SelectedAccountingTarget{Session: "s", PID: 123, Birth: 456}, MapID: 7, PossibleCPUs: 2, Rows: []operators.SelectedCPUCounts{{Attempted: 3, Submitted: 2, ReserveFailed: 1}, {}}, Quiesced: true, Joined: true}
	p := model.EventFinalizationObservation{Phase: "resources_retired", ResourcesRetired: true, ProducersQuiesced: true, ReceiverJoined: true, FinalCounterRead: true, BufferDrained: true}
	for _, kind := range []string{"valid", "zero", "no_expected", "invalid_expected", "no_native", "map", "cpu", "overflow", "contradiction", "nonfinal", "no_parent", "not_retired", "delivery"} {
		t.Run(kind, func(t *testing.T) {
			expected, value, parent := b, v, p
			value.Rows = append([]operators.SelectedCPUCounts(nil), v.Rows...)
			bp, vp, pp := &expected, &value, &parent
			summary := model.Summary{Stats: model.Stats{Received: 2, Resolved: 2}, SelectedAccounting: &model.SelectedAccountingDiagnostic{Complete: true}, TerminalAccountingComplete: true,
				DefinitionStop:              &model.DefinitionStopObservation{ReaderStopped: true, ConsumerStopped: true, BuffersDrained: true, AuxiliaryStopped: true},
				DefinitionReaderObservation: &model.ReaderObservation{Complete: true}, EventLossObservation: &model.LossObservation{Complete: false}}
			switch kind {
			case "zero":
				value.Rows = make([]operators.SelectedCPUCounts, 2)
				summary.Stats = model.Stats{}
			case "no_expected":
				bp = nil
			case "invalid_expected":
				expected = accounting.Binding{}
				value.Target = operators.SelectedAccountingTarget{}
				value.MapID = 0
				value.PossibleCPUs = 0
			case "no_native":
				vp = nil
			case "map":
				value.MapID++
			case "cpu":
				value.Rows = value.Rows[:1]
			case "overflow":
				value.Rows[0].Overflow = 1
			case "contradiction":
				value.Rows[1].Submitted = 1
			case "nonfinal":
				value.Joined = false
			case "no_parent":
				pp = nil
			case "not_retired":
				parent.ResourcesRetired = false
			case "delivery":
				summary.Stats.Resolved = 1
			}
			err := applyCollectionInventory(bp, vp, pp, &summary)
			want := []accounting.Lane{accounting.Definitions, accounting.Enrichment, accounting.Wire, accounting.Retention}
			if kind != "valid" && kind != "zero" {
				want = append([]accounting.Lane{accounting.SelectedEvents}, want...)
			} else if err != nil || summary.SelectedAccounting == nil || !summary.SelectedAccounting.Complete {
				t.Fatal(summary, err)
			}
			if !reflect.DeepEqual(summary.UnprovenCollectionSources, want) || summary.EventLossObservation.Complete || len(summary.Errors) != 0 {
				t.Fatal(summary, err)
			}
			var wire bytes.Buffer
			if err := newProductionSink(&wire).Write(context.Background(), model.Record{Kind: "session_summary", Summary: &summary}); err != nil {
				t.Fatal(err)
			}
			var record struct {
				Summary struct {
					Missing []string `json:"unproven_collection_sources"`
				} `json:"summary"`
			}
			if err := json.Unmarshal(wire.Bytes(), &record); err != nil {
				t.Fatal(err)
			}
			if len(record.Summary.Missing) != len(want) {
				t.Fatal(wire.String())
			}
		})
	}
	raw, err := json.Marshal(model.Summary{})
	if err != nil || bytes.Contains(raw, []byte("unproven_collection_sources")) {
		t.Fatal(string(raw), err)
	}
}
