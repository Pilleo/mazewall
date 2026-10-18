package tooling

import (
	"os"
	"path/filepath"
	"testing"
)

func TestPreparationIdentityMatchesAcceptedSource(t *testing.T) {
	root := filepath.Join("..", "..")
	id, err := patchID(root, standard)
	if err != nil {
		t.Fatal(err)
	}
	if id != "962b27903e20422116ca8528b7edbc8f19eafc94f986907461bd8fb1ded9f976" {
		t.Fatalf("accepted preparation identity changed: %s", id)
	}
	identity, err := patchID(root, identityObservations)
	if err != nil {
		t.Fatal(err)
	}
	recovery, err := patchID(root, dropFirstDefinition)
	if err != nil {
		t.Fatal(err)
	}
	if identity == id || recovery == id || identity == recovery {
		t.Fatal("different preparation modes must not reuse provenance")
	}
}

func TestIncompatiblePreparationModesFailBeforeAcquisition(t *testing.T) {
	if err := Run(t.TempDir(), []string{"prepare", "--drop-first-definition", "--identity-observations"}); err == nil {
		t.Fatal("incompatible preparation modes admitted")
	}
}

func TestPreparedProvenanceRequiresEveryExpectedField(t *testing.T) {
	path := filepath.Join(t.TempDir(), "marker.json")
	expected := map[string]any{"base_sum": "h1:known", "capture_abi": float64(2), "fault_injection": nil}
	if err := os.WriteFile(path, []byte(`{"base_sum":"h1:known","capture_abi":2,"fault_injection":null}`), 0644); err != nil {
		t.Fatal(err)
	}
	if ok, err := markerMatches(path, expected); err != nil || !ok {
		t.Fatalf("valid marker: %v %v", ok, err)
	}
	if err := os.WriteFile(path, []byte(`{"base_sum":"h1:known","capture_abi":2}`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := markerMatches(path, expected); err == nil {
		t.Fatal("missing provenance field admitted")
	}
	if err := os.WriteFile(path, []byte(`{"base_sum":"h1:changed","capture_abi":2,"fault_injection":null}`), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := markerMatches(path, expected); err == nil {
		t.Fatal("changed module provenance admitted")
	}
}

func TestEpochArtifactsRequireCompleteUnchangedNativeBytes(t *testing.T) {
	root := t.TempDir()
	files := map[string]string{}
	for _, name := range []string{"support/types.go", "support/ebpf/tracer.ebpf.amd64", "support/ebpf/tracer.ebpf.arm64"} {
		if err := os.MkdirAll(filepath.Dir(filepath.Join(root, name)), 0755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(root, name), []byte("a"), 0644); err != nil {
			t.Fatal(err)
		}
		files[name] = "ca978112ca1bbdcafac231b39a23dc4da786eff8147c4e72b9807785afee48bb"
	}
	if err := writeJSON(filepath.Join(root, ".mw-epoch-artifacts.json"), files); err != nil {
		t.Fatal(err)
	}
	if err := verifyEpochArtifacts(root); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "support/types.go"), []byte("tampered"), 0644); err != nil {
		t.Fatal(err)
	}
	if err := verifyEpochArtifacts(root); err == nil {
		t.Fatal("changed generated native bytes admitted")
	}
	if err := writeJSON(filepath.Join(root, ".mw-epoch-artifacts.json"), map[string]string{}); err != nil {
		t.Fatal(err)
	}
	if err := verifyEpochArtifacts(root); err == nil {
		t.Fatal("empty native inventory admitted")
	}
}

func TestPreparationIdentityReadsActualBytesAndRefusesMissingInputs(t *testing.T) {
	root := t.TempDir()
	paths, err := preparationInputs(filepath.Join("..", ".."), standard)
	if err != nil {
		t.Fatal(err)
	}
	for _, name := range paths {
		if err := os.MkdirAll(filepath.Dir(filepath.Join(root, name)), 0755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(root, name), []byte("first"), 0644); err != nil {
			t.Fatal(err)
		}
	}
	before, err := patchID(root, standard)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, paths[0]), []byte("changed"), 0644); err != nil {
		t.Fatal(err)
	}
	after, err := patchID(root, standard)
	if err != nil {
		t.Fatal(err)
	}
	if before == after {
		t.Fatal("changed patch bytes reused preparation identity")
	}
	if err := os.Remove(filepath.Join(root, paths[0])); err != nil {
		t.Fatal(err)
	}
	if _, err := patchID(root, standard); err == nil {
		t.Fatal("missing patch input admitted")
	}
}
