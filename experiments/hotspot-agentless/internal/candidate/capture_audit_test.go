package candidate

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"io.mazewall/experiments/hotspot-agentless/internal/qualification/jitgc"
)

const auditManifestJSON = `{"capture_abi":2,"profiler_revision":"ef6cf29492c8a980f889052903fa2dd0c0765cc0",` +
	`"patch_sha256":"473acb5ab31168cdba2ad390c105ca83a163e91d9443e53ce45cfecd6f30c58b"}`

func TestSourceAuditCannotBecomeRuntimeQualification(t *testing.T) {
	assets, out := t.TempDir(), t.TempDir()
	path := filepath.Join(assets, "manifest.json")
	good := append([]byte(auditManifestJSON), bytes.Repeat([]byte(" "), 65536-len(auditManifestJSON))...)
	if err := os.WriteFile(path, good, 0600); err != nil {
		t.Fatal(err)
	}
	if err := writeCaptureAudit(assets, out); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(filepath.Join(out, "capture-audit.json"))
	if err != nil {
		t.Fatal(err)
	}
	var got jitgc.CaptureAudit
	if err := json.Unmarshal(raw, &got); err != nil {
		t.Fatal(err)
	}
	if got.ProductionQualified || got.EvidenceScope != "reviewed_pinned_source_contract" ||
		got.CandidateScope != "compiled_or_concurrent_gc" ||
		!got.CompileIDCaptured || !got.CompileIDValidated || len(got.Missing) != 3 {
		t.Fatal(got)
	}
	for _, bad := range [][]byte{[]byte(`{"capture_abi":3}`), []byte("{"), {0xff}, append(good, ' ')} {
		if err := os.WriteFile(path, bad, 0600); err != nil {
			t.Fatal(err)
		}
		rejected := t.TempDir()
		if err := writeCaptureAudit(assets, rejected); err == nil {
			t.Fatal("invalid source contract accepted")
		}
		if _, err := os.Stat(filepath.Join(rejected, "capture-audit.json")); !os.IsNotExist(err) {
			t.Fatal("audit written for rejected source contract", err)
		}
	}
}

func TestCandidateVerifiesAssetsBeforeCreatingOutput(t *testing.T) {
	for _, fixture := range []struct{ name, manifest string }{{"missing", ""}, {"source-contract-only", auditManifestJSON}} {
		t.Run(fixture.name, func(t *testing.T) {
			root, assets := t.TempDir(), t.TempDir()
			if fixture.manifest != "" {
				if err := os.WriteFile(filepath.Join(assets, "manifest.json"), []byte(fixture.manifest), 0600); err != nil {
					t.Fatal(err)
				}
			}
			out := filepath.Join(root, "output")
			err := Run(root, []string{"--assets", assets, "--output", out,
				"--docker-host", "unix:///nonexistent-upstream-audit-control.sock"})
			if err == nil {
				t.Fatal("incomplete assets accepted")
			}
			if _, err := os.Stat(out); !os.IsNotExist(err) {
				t.Fatal("output created before common admission", err)
			}
		})
	}
}
