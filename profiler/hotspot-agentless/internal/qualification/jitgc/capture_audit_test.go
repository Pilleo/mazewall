package jitgc

import (
	"reflect"
	"testing"
)

func TestCompileIDPresenceDoesNotQualifyLifetime(t *testing.T) {
	const revision = "ef6cf29492c8a980f889052903fa2dd0c0765cc0"
	const patch = "618c3d67d71e86dfb0befb13f73498402af6110f1f2fceb3784c53785b0da00c"
	got, err := AuditCapture(2, revision, patch)
	want := []string{"metadata_stability_through_decode", "code_lifetime_through_decode", "concurrent_reclamation_exclusion"}
	if err != nil || got.CaptureABI != 2 || got.ProfilerRevision != revision || got.PatchSHA256 != patch ||
		got.EvidenceScope != "reviewed_pinned_source_contract" ||
		got.CandidateScope != "compiled_or_concurrent_gc" ||
		!got.CompileIDCaptured || !got.CompileIDValidated || got.ProductionQualified ||
		!reflect.DeepEqual(got.Missing, want) {
		t.Fatal(got, err)
	}
	got.Missing[0] = "changed"
	again, err := AuditCapture(2, revision, patch)
	if err != nil || !reflect.DeepEqual(again.Missing, want) {
		t.Fatal(again, err)
	}
	for _, abi := range []uint32{0, 1, 3} {
		if _, err := AuditCapture(abi, revision, patch); err == nil {
			t.Fatal("unreviewed ABI accepted", abi)
		}
	}
	if _, err := AuditCapture(2, "36f875fd7d625a80de939a9325e1cde8fd43a9f6", patch); err == nil {
		t.Fatal("comparison main treated as reviewed production source")
	}
	if _, err := AuditCapture(2, revision, "0000000000000000000000000000000000000000000000000000000000000000"); err == nil {
		t.Fatal("unreviewed local overlay treated as reviewed source")
	}
}
