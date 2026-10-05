// Package accounting proves scoped conservation; it cannot authorize policy conversion.
package accounting

import (
	"fmt"
	"math/bits"
)

type Counts struct {
	Known, Quiesced, Joined                                  bool
	Attempted, Submitted, ReserveFailed, Received, Terminals uint64
}
type Proof struct {
	Complete bool
	Lost     uint64
	Reason   string
}

func Evaluate(c Counts) Proof {
	if !c.Known || !c.Quiesced || !c.Joined {
		return Proof{Reason: "source not final"}
	}
	if c.Submitted > c.Attempted || c.ReserveFailed != c.Attempted-c.Submitted || c.Received > c.Submitted || c.Terminals != c.Received {
		return Proof{Reason: "conservation mismatch"}
	}
	return Proof{Complete: true, Lost: c.Attempted - c.Received}
}

// Matches the candidate native per-CPU32byte layout; Overflow is sticky.
type CPUCounts struct{ Attempted, Submitted, ReserveFailed, Overflow uint64 }

func Aggregate(rows []CPUCounts, possibleCPUs int, generationMatched bool) (Counts, error) {
	if !generationMatched || possibleCPUs <= 0 || len(rows) != possibleCPUs {
		return Counts{}, fmt.Errorf("counter generation/cardinality unknown")
	}
	total := Counts{Known: true}
	for _, row := range rows {
		if row.Overflow != 0 {
			return Counts{}, fmt.Errorf("native counter overflow")
		}
		for _, pair := range []struct {
			target *uint64
			value  uint64
		}{{&total.Attempted, row.Attempted}, {&total.Submitted, row.Submitted}, {&total.ReserveFailed, row.ReserveFailed}} {
			value, carry := bits.Add64(*pair.target, pair.value, 0)
			if carry != 0 {
				return Counts{}, fmt.Errorf("aggregate counter overflow")
			}
			*pair.target = value
		}
	}
	return total, nil
}
