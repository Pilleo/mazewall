package accounting

import "fmt"

// Binding identifies a counter owner; values alone are not native acquisition receipts.
type Binding struct {
	Session      string
	PID          uint32
	Birth        uint64
	MapID        uint32
	PossibleCPUs int
}

// Snapshot carries owner-supplied finality, counter rows and delivered counts.
type Snapshot struct {
	Binding
	Rows                []CPUCounts
	Quiesced, Joined    bool
	Received, Terminals uint64
}

func BindAndEvaluate(expected Binding, s Snapshot) (Counts, Proof, error) {
	if expected.Session == "" || expected.PID == 0 || expected.Birth == 0 || expected.MapID == 0 || expected.PossibleCPUs <= 0 || expected != s.Binding {
		return Counts{}, Proof{}, fmt.Errorf("counter owner identity unknown or changed")
	}
	rows := append([]CPUCounts(nil), s.Rows...)
	counts, err := Aggregate(rows, expected.PossibleCPUs, true)
	if err != nil {
		return Counts{}, Proof{}, err
	}
	counts.Quiesced, counts.Joined = s.Quiesced, s.Joined
	counts.Received, counts.Terminals = s.Received, s.Terminals
	return counts, Evaluate(counts), nil
}
