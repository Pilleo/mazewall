package accounting

import (
	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"sort"
)

type Lane = wire.Lane

const (
	SelectedEvents Lane = wire.SelectedEvents
	Definitions    Lane = wire.Definitions
	Enrichment     Lane = wire.Enrichment
	Wire           Lane = wire.Wire
	Retention      Lane = wire.Retention
)

// LaneEvidence is a scoped model, not proof that native admission was observed.
type LaneEvidence struct {
	Binding Binding
	Final   bool
	Counts  Counts
}

func MissingEvidence(expected Binding, lanes map[Lane]LaneEvidence) []Lane {
	missing := []Lane{}
	valid := expected.Session != "" && expected.PID != 0 && expected.Birth != 0 && expected.MapID != 0 && expected.PossibleCPUs > 0
	for _, lane := range []Lane{SelectedEvents, Definitions, Enrichment, Wire, Retention} {
		evidence, ok := lanes[lane]
		if !valid || !ok || evidence.Binding != expected || !evidence.Final || !Evaluate(evidence.Counts).Complete {
			missing = append(missing, lane)
		}
	}
	var unknown []Lane
	for lane := range lanes {
		switch lane {
		case SelectedEvents, Definitions, Enrichment, Wire, Retention:
		default:
			unknown = append(unknown, Lane("unrecognized:"+string(lane)))
		}
	}
	sort.Slice(unknown, func(i, j int) bool { return unknown[i] < unknown[j] })
	return append(missing, unknown...)
}
