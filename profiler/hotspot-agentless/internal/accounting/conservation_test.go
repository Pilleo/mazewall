package accounting

import (
	"math"
	"testing"
)

func TestKnownReserveAndTransportLoss(t *testing.T) {
	p := Evaluate(Counts{Known: true, Quiesced: true, Joined: true, Attempted: 10, Submitted: 8, ReserveFailed: 2, Received: 7, Terminals: 7})
	if !p.Complete || p.Lost != 3 {
		t.Fatal(p)
	}
}
func TestMissingTailAndUnknownCountersNeverComplete(t *testing.T) {
	for _, c := range []Counts{{Known: false, Quiesced: true, Joined: true}, {Known: true, Quiesced: false, Joined: true}, {Known: true, Quiesced: true, Joined: true, Attempted: 10, Submitted: 8, ReserveFailed: 1}, {Known: true, Quiesced: true, Joined: true, Attempted: 2, Submitted: 2, Received: 3, Terminals: 3}} {
		if Evaluate(c).Complete {
			t.Fatal(c)
		}
	}
}
func TestCPUCardinalityGenerationAndOverflow(t *testing.T) {
	for _, values := range [][]CPUCounts{{{Attempted: math.MaxUint64, Submitted: math.MaxUint64}, {Attempted: 1, Submitted: 1}}, {{Overflow: 1}}} {
		if _, err := Aggregate(values, len(values), true); err == nil {
			t.Fatal("overflow known", values)
		}
	}
	if _, err := Aggregate([]CPUCounts{{}}, 2, true); err == nil {
		t.Fatal("missing CPU known")
	}
	if _, err := Aggregate([]CPUCounts{{}}, 1, false); err == nil {
		t.Fatal("changed generation known")
	}
}

func TestCPUTuplesCannotCancelContradictions(t *testing.T) {
	for _, rows := range [][]CPUCounts{
		{{Attempted: 1, Submitted: 2}, {Attempted: 1}},
		{{Attempted: 1}},
		{{Attempted: 1, Submitted: 1, ReserveFailed: 1}},
		{{Submitted: math.MaxUint64, ReserveFailed: 1}},
		{{Attempted: math.MaxUint64, Submitted: math.MaxUint64, ReserveFailed: 1}},
	} {
		if counts, err := Aggregate(rows, len(rows), true); err == nil {
			t.Errorf("impossible per-CPU conservation became known: %+v", counts)
		}
	}
}

func TestValidPerCPUBoundariesRemainKnown(t *testing.T) {
	for _, test := range []struct {
		rows                          []CPUCounts
		attempted, submitted, reserve uint64
	}{
		{[]CPUCounts{{Attempted: 7, Submitted: 7}, {Attempted: 3, ReserveFailed: 3}}, 10, 7, 3},
		{[]CPUCounts{{Attempted: math.MaxUint64, Submitted: 1, ReserveFailed: math.MaxUint64 - 1}}, math.MaxUint64, 1, math.MaxUint64 - 1},
		{[]CPUCounts{{Attempted: math.MaxUint64, Submitted: math.MaxUint64}}, math.MaxUint64, math.MaxUint64, 0},
	} {
		counts, err := Aggregate(test.rows, len(test.rows), true)
		if err != nil || !counts.Known || counts.Attempted != test.attempted || counts.Submitted != test.submitted || counts.ReserveFailed != test.reserve {
			t.Fatalf("valid per-CPU boundary lost: %+v %v", counts, err)
		}
	}
}
func TestMissingDefinitionProofBlocksCompleteness(t *testing.T) {
	i := Inventory{"selected_events": true, "enrichment": true, "wire": true, "retention": true}
	missing := Missing(i)
	if len(missing) != 1 || missing[0] != "definitions" {
		t.Fatal(missing)
	}
	i["definitions"] = true
	if len(Missing(i)) != 0 {
		t.Fatal("known proof rejected")
	}
	i["extra_producer"] = true
	if len(Missing(i)) == 0 {
		t.Fatal("new producer bypassed inventory")
	}
}
