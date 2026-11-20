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

func TestInventoryPublishesUnprovenDefinitionSources(t *testing.T) {
	var summary model.Summary
	p := projectDefinitionSources(nil)
	_ = applyCollectionInventory(nil, nil, nil, &summary, p)
	var got definitionSourceConservation
	if err := json.Unmarshal(summary.Extensions["definition_source_conservation"], &got); err != nil {
		t.Fatal(err)
	}
	if len(got.Rows) != 26 || got.Disposition != definitionSourceUnknown {
		t.Fatalf("missing diagnostic rows: %+v", got)
	}
	found := false
	for _, lane := range summary.UnprovenCollectionSources {
		if lane == accounting.Definitions { found = true }
	}
	if !found { t.Fatal("Definitions missing entry was removed") }
}

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
			err := applyCollectionInventory(bp, vp, pp, &summary, projectDefinitionSources(nil))
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

func TestInventoryEnrichmentReceiptIntegration(t *testing.T) {
	b := accounting.Binding{Session: "s", PID: 123, Birth: 456, MapID: 7, PossibleCPUs: 2}
	v := operators.SelectedAccountingObservation{Target: operators.SelectedAccountingTarget{Session: "s", PID: 123, Birth: 456}, MapID: 7, PossibleCPUs: 2, Rows: []operators.SelectedCPUCounts{{Attempted: 3, Submitted: 2, ReserveFailed: 1}, {}}, Quiesced: true, Joined: true}
	p := model.EventFinalizationObservation{Phase: "resources_retired", ResourcesRetired: true, ProducersQuiesced: true, ReceiverJoined: true, FinalCounterRead: true, BufferDrained: true}

	t.Run("valid_enrichment_admitted", func(t *testing.T) {
		summary := model.Summary{Stats: model.Stats{Received: 2, Resolved: 2}, SelectedAccounting: &model.SelectedAccountingDiagnostic{Complete: true}, TerminalAccountingComplete: true}
		enrichCounts := &accounting.Counts{Known: true, Quiesced: true, Joined: true, Attempted: 2, Submitted: 2, Received: 2, Terminals: 2}
		err := applyCollectionInventory(&b, &v, &p, &summary, projectDefinitionSources(nil), enrichCounts)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}
		want := []accounting.Lane{accounting.Definitions, accounting.Wire, accounting.Retention}
		if !reflect.DeepEqual(summary.UnprovenCollectionSources, want) {
			t.Fatalf("expected enrichment admitted, got unproven: %v", summary.UnprovenCollectionSources)
		}
	})

	t.Run("incomplete_enrichment_rejected", func(t *testing.T) {
		summary := model.Summary{Stats: model.Stats{Received: 2, Resolved: 2}, SelectedAccounting: &model.SelectedAccountingDiagnostic{Complete: true}, TerminalAccountingComplete: true}
		enrichCounts := &accounting.Counts{Known: true, Quiesced: true, Joined: true, Attempted: 2, Submitted: 2, Received: 2, Terminals: 1}
		err := applyCollectionInventory(&b, &v, &p, &summary, projectDefinitionSources(nil), enrichCounts)
		if err == nil {
			t.Fatal("expected error for incomplete enrichment accounting")
		}
		want := []accounting.Lane{accounting.Definitions, accounting.Enrichment, accounting.Wire, accounting.Retention}
		if !reflect.DeepEqual(summary.UnprovenCollectionSources, want) {
			t.Fatalf("expected enrichment unproven, got: %v", summary.UnprovenCollectionSources)
		}
	})

	t.Run("reconciliation_mismatch_rejected", func(t *testing.T) {
		summary := model.Summary{Stats: model.Stats{Received: 2, Resolved: 2}, SelectedAccounting: &model.SelectedAccountingDiagnostic{Complete: true}, TerminalAccountingComplete: true}
		enrichCounts := &accounting.Counts{Known: true, Quiesced: true, Joined: true, Attempted: 3, Submitted: 3, Received: 3, Terminals: 3}
		err := applyCollectionInventory(&b, &v, &p, &summary, projectDefinitionSources(nil), enrichCounts)
		if err == nil {
			t.Fatal("expected error for enrichment delivery count mismatch with selected events")
		}
		want := []accounting.Lane{accounting.Definitions, accounting.Enrichment, accounting.Wire, accounting.Retention}
		if !reflect.DeepEqual(summary.UnprovenCollectionSources, want) {
			t.Fatalf("expected enrichment unproven, got: %v", summary.UnprovenCollectionSources)
		}
	})
	t.Run("contradictory_selected_rows_rejects_enrichment", func(t *testing.T) {
		summary := model.Summary{Stats: model.Stats{Received: 2, Resolved: 2}}
		contradictoryV := operators.SelectedAccountingObservation{
			Target:       operators.SelectedAccountingTarget{Session: "s", PID: 123, Birth: 456},
			MapID:        7,
			PossibleCPUs: 2,
			Rows:         []operators.SelectedCPUCounts{{Attempted: 1, Submitted: 1, ReserveFailed: 0}, {}},
			Quiesced:     true,
			Joined:       true,
		}
		enrichCounts := &accounting.Counts{Known: true, Quiesced: true, Joined: true, Attempted: 2, Submitted: 2, Received: 2, Terminals: 2}
		err := applyCollectionInventory(&b, &contradictoryV, &p, &summary, projectDefinitionSources(nil), enrichCounts)
		if err == nil {
			t.Fatal("expected error when selected rows contradict received count, got nil")
		}
		want := []accounting.Lane{accounting.SelectedEvents, accounting.Definitions, accounting.Enrichment, accounting.Wire, accounting.Retention}
		if !reflect.DeepEqual(summary.UnprovenCollectionSources, want) {
			t.Fatalf("expected enrichment and selected events unproven, got: %v", summary.UnprovenCollectionSources)
		}
	})
}

// TestCollectorInventoryNeverAdmitsWireOrRetention verifies that Wire, Retention,
// and Definitions lanes remain in UnprovenCollectionSources (UNKNOWN status).
// Definitions requires independent frozen transfer and tracer-local snapshots are
// not inputs to applyCollectionInventory.
func TestCollectorInventoryNeverAdmitsWireOrRetention(t *testing.T) {
	b := accounting.Binding{Session: "s", PID: 123, Birth: 456, MapID: 7, PossibleCPUs: 2}
	v := operators.SelectedAccountingObservation{
		Target:       operators.SelectedAccountingTarget{Session: "s", PID: 123, Birth: 456},
		MapID:        7,
		PossibleCPUs: 2,
		Rows:         []operators.SelectedCPUCounts{{Attempted: 3, Submitted: 2, ReserveFailed: 1}, {}},
		Quiesced:     true,
		Joined:       true,
	}
	p := model.EventFinalizationObservation{Phase: "resources_retired", ResourcesRetired: true, ProducersQuiesced: true, ReceiverJoined: true, FinalCounterRead: true, BufferDrained: true}
	summary := model.Summary{Stats: model.Stats{Received: 2, Resolved: 2}, SelectedAccounting: &model.SelectedAccountingDiagnostic{Complete: true}, TerminalAccountingComplete: true}
	enrichCounts := &accounting.Counts{Known: true, Quiesced: true, Joined: true, Attempted: 2, Submitted: 2, Received: 2, Terminals: 2}

	err := applyCollectionInventory(&b, &v, &p, &summary, projectDefinitionSources(nil), enrichCounts)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}

	for _, req := range []accounting.Lane{accounting.Wire, accounting.Retention, accounting.Definitions} {
		found := false
		for _, unproven := range summary.UnprovenCollectionSources {
			if unproven == req {
				found = true
				break
			}
		}
		if !found {
			t.Fatalf("expected lane %v to remain unproven in collector inventory", req)
		}
	}
}
