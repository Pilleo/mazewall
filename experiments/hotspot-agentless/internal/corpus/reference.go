package corpus

import "fmt"

type ReferenceReport struct {
	Reference        string `json:"reference"`
	Expected         int    `json:"expected"`
	Observed         int    `json:"observed"`
	Wrong            int    `json:"wrong"`
	Unavailable      int    `json:"unavailable_segments"`
	BranchA          int    `json:"branch_a_segments"`
	BranchB          int    `json:"branch_b_segments"`
	CoverageComplete bool   `json:"coverage_complete"`
	Checksum         int64  `json:"checksum"`
}
type DifferentialReport struct {
	SchemaVersion        int      `json:"schema_version"`
	IndependentRuns      bool     `json:"independent_runs"`
	BCIEqualityVerified  bool     `json:"bci_equality_verified"`
	ApplicationSegment   []string `json:"application_segment"`
	CandidateResolved    int      `json:"candidate_resolved_segments"`
	CandidateUnavailable int      `json:"candidate_unavailable_segments"`
	ReferenceResolved    int      `json:"reference_resolved_segments"`
}

func CompareReference(candidate Report, reference ReferenceReport) (DifferentialReport, error) {
	d := DifferentialReport{SchemaVersion: 1, IndependentRuns: true, ApplicationSegment: []string{"sharedLeaf", "pathA or pathB"}, CandidateResolved: candidate.ResolvedSegments, CandidateUnavailable: candidate.UnavailableSegments, ReferenceResolved: reference.BranchA + reference.BranchB}
	if candidate.Expected <= 0 || candidate.Expected%2 != 0 || candidate.Observed != candidate.Expected || candidate.Unobserved != 0 || candidate.WrongAttribution != 0 || len(candidate.Failures) != 0 || candidate.ResolvedSegments+candidate.UnavailableSegments != candidate.Expected {
		return d, fmt.Errorf("candidate corpus failed")
	}
	if reference.Reference != "USER_NOTIF" || reference.Expected != candidate.Expected || reference.Observed != reference.Expected || reference.Wrong != 0 || reference.Unavailable != 0 || !reference.CoverageComplete || reference.BranchA != reference.Expected/2 || reference.BranchB != reference.Expected/2 || reference.Checksum != int64(reference.Expected/2*3) {
		return d, fmt.Errorf("independent USER_NOTIF reference failed")
	}
	return d, nil
}
