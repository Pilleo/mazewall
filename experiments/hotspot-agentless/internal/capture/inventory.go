package capture

import (
	"fmt"

	"github.com/inspektor-gadget/inspektor-gadget/pkg/operators"
	"io.mazewall/experiments/hotspot-agentless/internal/accounting"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
)

func applyCollectionInventory(expected *accounting.Binding, value *operators.SelectedAccountingObservation, parent *model.EventFinalizationObservation, summary *model.Summary) error {
	lanes := map[accounting.Lane]accounting.LaneEvidence{}
	var binding accounting.Binding
	var err error
	summary.SelectedAccounting = nil
	if expected == nil || value == nil {
		err = fmt.Errorf("selected prepared identity or final counters missing")
	} else {
		binding = *expected
		summary.SelectedAccounting, err = selectedDiagnostic(binding, *value, parent, summary.Stats)
		if d := summary.SelectedAccounting; d != nil {
			// Only the just-validated native rows, final parent and delivered stats admit this lane.
			lanes[accounting.SelectedEvents] = accounting.LaneEvidence{Binding: binding, Final: d.Complete, Counts: accounting.Counts{
				Known: true, Quiesced: value.Quiesced, Joined: value.Joined,
				Attempted: d.Attempted, Submitted: d.Submitted, ReserveFailed: d.ReserveFailed, Received: d.Received, Terminals: d.Terminals,
			}}
		}
	}
	summary.UnprovenCollectionSources = accounting.MissingEvidence(binding, lanes)
	return err
}
