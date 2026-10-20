package accounting

import (
	"math"
	"testing"
)

func TestWrongMapOrUnjoinedSnapshotCannotProveConservation(t *testing.T) {
	b := Binding{Session: "s", PID: 123, Birth: 456, MapID: 7, PossibleCPUs: 2}
	s := Snapshot{Binding: b, Rows: []CPUCounts{{Attempted: 3, Submitted: 2, ReserveFailed: 1}, {}}, Quiesced: true, Joined: true, Received: 2, Terminals: 2}
	counts, proof, err := BindAndEvaluate(b, s)
	want := Counts{Known: true, Quiesced: true, Joined: true, Attempted: 3, Submitted: 2, ReserveFailed: 1, Received: 2, Terminals: 2}
	if err != nil || counts != want || !proof.Complete || proof.Lost != 1 {
		t.Fatal(counts, proof, err)
	}
	for _, kind := range []string{"map", "session", "pid", "birth", "possible_cpus", "cpu", "extra_cpu", "join", "quiesce"} {
		t.Run(kind, func(t *testing.T) {
			bad := s
			switch kind {
			case "map":
				bad.MapID++
			case "session":
				bad.Session = "other"
			case "pid":
				bad.PID++
			case "birth":
				bad.Birth++
			case "possible_cpus":
				bad.PossibleCPUs++
			case "cpu":
				bad.Rows = bad.Rows[:1]
			case "extra_cpu":
				bad.Rows = append(append([]CPUCounts(nil), bad.Rows...), CPUCounts{})
			case "join":
				bad.Joined = false
			case "quiesce":
				bad.Quiesced = false
			}
			counts, proof, err := BindAndEvaluate(b, bad)
			if kind == "join" || kind == "quiesce" {
				if err != nil || proof.Complete || proof.Reason != "source not final" || counts.Quiesced != bad.Quiesced || counts.Joined != bad.Joined {
					t.Fatal(counts, proof, err)
				}
			} else if err == nil || counts != (Counts{}) || proof != (Proof{}) {
				t.Fatal("foreign/cardinality snapshot accepted", counts, proof, err)
			}
		})
	}
}

func TestBindingRequiresKnownExpectedIdentity(t *testing.T) {
	valid := Binding{Session: "s", PID: 123, Birth: 456, MapID: 7, PossibleCPUs: 1}
	for _, kind := range []string{"session", "pid", "birth", "map", "zero_cpus", "negative_cpus"} {
		t.Run(kind, func(t *testing.T) {
			b := valid
			switch kind {
			case "session":
				b.Session = ""
			case "pid":
				b.PID = 0
			case "birth":
				b.Birth = 0
			case "map":
				b.MapID = 0
			case "zero_cpus":
				b.PossibleCPUs = 0
			case "negative_cpus":
				b.PossibleCPUs = -1
			}
			s := Snapshot{Binding: b, Rows: []CPUCounts{{}}, Quiesced: true, Joined: true}
			if counts, proof, err := BindAndEvaluate(b, s); err == nil || counts != (Counts{}) || proof != (Proof{}) {
				t.Fatal("matching unknown identity accepted", counts, proof, err)
			}
		})
	}
}

func TestBindingZeroCountsRequireExplicitFinalityAndRows(t *testing.T) {
	b := Binding{Session: "s", PID: 123, Birth: 456, MapID: 7, PossibleCPUs: 2}
	for _, quiesced := range []bool{false, true} {
		for _, joined := range []bool{false, true} {
			s := Snapshot{Binding: b, Rows: []CPUCounts{{}, {}}, Quiesced: quiesced, Joined: joined}
			counts, proof, err := BindAndEvaluate(b, s)
			if err != nil || !counts.Known || proof.Complete != (quiesced && joined) || proof.Lost != 0 {
				t.Fatal(counts, proof, err)
			}
		}
	}
	for _, rows := range [][]CPUCounts{nil, {}, {{}}} {
		s := Snapshot{Binding: b, Rows: rows, Quiesced: true, Joined: true}
		if counts, proof, err := BindAndEvaluate(b, s); err == nil || counts != (Counts{}) || proof != (Proof{}) {
			t.Fatal("missing zero-count slots accepted", counts, proof, err)
		}
	}
}

func TestBindingRejectsPerCPUContradictionsAndOverflow(t *testing.T) {
	for _, rows := range [][]CPUCounts{
		{{Attempted: 1, Submitted: 2}, {Attempted: 1}},
		{{Attempted: 1}},
		{{Attempted: 1, Submitted: 1, ReserveFailed: 1}},
		{{Overflow: 1}},
		{{Attempted: math.MaxUint64, Submitted: math.MaxUint64}, {Attempted: 1, Submitted: 1}},
	} {
		b := Binding{Session: "s", PID: 123, Birth: 456, MapID: 7, PossibleCPUs: len(rows)}
		s := Snapshot{Binding: b, Rows: rows, Quiesced: true, Joined: true}
		if counts, proof, err := BindAndEvaluate(b, s); err == nil || counts != (Counts{}) || proof != (Proof{}) {
			t.Fatal("invalid counters accepted", rows, counts, proof, err)
		}
	}
}

func TestBindingDeliveryConservationAndValueOwnership(t *testing.T) {
	b := Binding{Session: "s", PID: 123, Birth: 456, MapID: 7, PossibleCPUs: 2}
	rows := []CPUCounts{{Attempted: 7, Submitted: 7}, {Attempted: 3, ReserveFailed: 3}}
	s := Snapshot{Binding: b, Rows: rows, Quiesced: true, Joined: true, Received: 6, Terminals: 6}
	counts, proof, err := BindAndEvaluate(b, s)
	if err != nil || counts.Attempted != 10 || counts.Submitted != 7 || counts.ReserveFailed != 3 || counts.Received != 6 || counts.Terminals != 6 || !proof.Complete || proof.Lost != 4 {
		t.Fatal(counts, proof, err)
	}
	if rows[0] != (CPUCounts{Attempted: 7, Submitted: 7}) || rows[1] != (CPUCounts{Attempted: 3, ReserveFailed: 3}) {
		t.Fatal("evaluation mutated caller rows", rows)
	}
	for _, delivered := range []struct{ received, terminals uint64 }{{8, 8}, {6, 5}, {6, 7}} {
		bad := s
		bad.Received, bad.Terminals = delivered.received, delivered.terminals
		c, p, err := BindAndEvaluate(b, bad)
		if err != nil || !c.Known || p.Complete || p.Reason != "conservation mismatch" {
			t.Fatal(c, p, err)
		}
	}
	rows[0] = CPUCounts{}
	s.Joined = false
	if counts.Attempted != 10 || !counts.Joined || !proof.Complete || proof.Lost != 4 {
		t.Fatal("returned values changed with caller snapshot", counts, proof)
	}
}
