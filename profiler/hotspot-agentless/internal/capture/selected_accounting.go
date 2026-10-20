package capture

import (
	"fmt"
	"math/bits"

	"github.com/inspektor-gadget/inspektor-gadget/pkg/operators"
	"io.mazewall/experiments/hotspot-agentless/internal/accounting"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
)

type selectedBindingProvider interface {
	SelectedAccountingBinding() (operators.SelectedAccountingObservation, bool)
}

func selectedExpected(gc operators.GadgetContext, session string, pid uint32, birth uint64) (accounting.Binding, error) {
	raw, ok := gc.GetVar("ebpfInstance")
	if !ok {
		return accounting.Binding{}, fmt.Errorf("selected owner missing")
	}
	owner, ok := raw.(selectedBindingProvider)
	if !ok {
		return accounting.Binding{}, fmt.Errorf("selected binding capability missing")
	}
	value, ok := owner.SelectedAccountingBinding()
	if !ok || value.Target.Session != session || value.Target.PID != pid || value.Target.Birth != birth || value.MapID == 0 || value.PossibleCPUs <= 0 {
		return accounting.Binding{}, fmt.Errorf("selected prepared owner identity mismatch")
	}
	return accounting.Binding{Session: session, PID: pid, Birth: birth, MapID: value.MapID, PossibleCPUs: value.PossibleCPUs}, nil
}

func selectedDiagnostic(expected accounting.Binding, value operators.SelectedAccountingObservation, parent *model.EventFinalizationObservation, stats model.Stats) (*model.SelectedAccountingDiagnostic, error) {
	if parent == nil || parent.Phase != "resources_retired" || !parent.ResourcesRetired || !parent.ProducersQuiesced || !parent.ReceiverJoined || !parent.FinalCounterRead || !parent.BufferDrained || parent.Error != "" {
		return nil, fmt.Errorf("selected source owner not final")
	}
	terminals, carry := bits.Add64(stats.Resolved, stats.Unavailable, 0)
	terminals, last := bits.Add64(terminals, stats.Skipped, 0)
	if carry != 0 || last != 0 {
		return nil, fmt.Errorf("terminal count overflow")
	}
	rows := make([]accounting.CPUCounts, len(value.Rows))
	for n, row := range value.Rows {
		rows[n] = accounting.CPUCounts{Attempted: row.Attempted, Submitted: row.Submitted, ReserveFailed: row.ReserveFailed, Overflow: row.Overflow}
	}
	actual := accounting.Binding{Session: value.Target.Session, PID: value.Target.PID, Birth: value.Target.Birth, MapID: value.MapID, PossibleCPUs: value.PossibleCPUs}
	counts, proof, err := accounting.BindAndEvaluate(expected, accounting.Snapshot{Binding: actual, Rows: rows, Quiesced: value.Quiesced, Joined: value.Joined, Received: stats.Received, Terminals: terminals})
	if err != nil {
		return nil, err
	}
	return &model.SelectedAccountingDiagnostic{SchemaVersion: 1, Scope: "owned_openat_handler_entry", SessionID: expected.Session, PID: expected.PID, Birth: expected.Birth, MapID: expected.MapID, PossibleCPUs: expected.PossibleCPUs, Attempted: counts.Attempted, Submitted: counts.Submitted, ReserveFailed: counts.ReserveFailed, Received: counts.Received, Terminals: counts.Terminals, Complete: proof.Complete, Reason: proof.Reason}, nil
}

func selectedFinal(gc operators.GadgetContext) (*operators.SelectedAccountingObservation, error) {
	raw, ok := gc.GetVar("ebpfInstance")
	if !ok {
		return nil, fmt.Errorf("selected final owner missing")
	}
	owner, ok := raw.(eventFinalizationObserver)
	if !ok {
		return nil, fmt.Errorf("selected final capability missing")
	}
	value, active := owner.EventFinalization()
	if !active || value.SelectedAccounting == nil {
		return nil, fmt.Errorf("selected final counters missing")
	}
	return value.SelectedAccounting, nil
}
