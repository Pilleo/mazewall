package corpus

import "testing"

func TestIndependentReferenceComparisonDoesNotClaimSameOccurrencesOrBCI(t *testing.T) {
	candidate := Report{Expected: 2000, Observed: 2000, ResolvedSegments: 1900, UnavailableSegments: 100, CompiledSegments: map[string]int{"A": 950, "B": 950}}
	reference := ReferenceReport{Reference: "USER_NOTIF", Expected: 2000, Observed: 2000, BranchA: 1000, BranchB: 1000, CoverageComplete: true, Checksum: 3000}
	d, err := CompareReference(candidate, reference)
	if err != nil || !d.IndependentRuns || d.BCIEqualityVerified || d.CandidateResolved != 1900 || d.ReferenceResolved != 2000 {
		t.Fatalf("comparison falsely complete: %+v %v", d, err)
	}
	for _, bad := range []ReferenceReport{{Reference: "USER_NOTIF", Expected: 2000, Observed: 1999}, {Reference: "USER_NOTIF", Expected: 2000, Observed: 2000, Wrong: 1}, {Reference: "CPU_SAMPLE", Expected: 2000, Observed: 2000}} {
		if _, err := CompareReference(candidate, bad); err == nil {
			t.Fatal("invalid reference accepted")
		}
	}
}
