package jitgc

import "fmt"

type CaptureAudit struct {
	CaptureABI          uint32   `json:"capture_abi"`
	ProfilerRevision    string   `json:"profiler_revision"`
	PatchSHA256         string   `json:"patch_sha256"`
	EvidenceScope       string   `json:"evidence_scope"`
	CandidateScope      string   `json:"candidate_scope"`
	CompileIDCaptured   bool     `json:"compile_id_captured_in_reviewed_source"`
	CompileIDValidated  bool     `json:"compile_id_validated_in_reviewed_source"`
	Missing             []string `json:"unqualified_lifetime_requirements"`
	ProductionQualified bool     `json:"production_qualified"`
}

func AuditCapture(abi uint32, profilerRevision string, patchSHA256 string) (CaptureAudit, error) {
	if abi != 2 || profilerRevision != "ef6cf29492c8a980f889052903fa2dd0c0765cc0" ||
		patchSHA256 != "962b27903e20422116ca8528b7edbc8f19eafc94f986907461bd8fb1ded9f976" {
		return CaptureAudit{}, fmt.Errorf("unreviewed capture ABI/profiler source")
	}
	return CaptureAudit{
		CaptureABI: abi, ProfilerRevision: profilerRevision, PatchSHA256: patchSHA256,
		EvidenceScope:     "reviewed_pinned_source_contract",
		CandidateScope:    "compiled_or_concurrent_gc",
		CompileIDCaptured: true, CompileIDValidated: true,
		Missing:             []string{"metadata_stability_through_decode", "code_lifetime_through_decode", "concurrent_reclamation_exclusion"},
		ProductionQualified: false,
	}, nil
}
