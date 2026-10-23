package tooling

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestPreparedContainerdGraphUsesPatchedV2Client(t *testing.T) {
	root, err := filepath.Abs(filepath.Join("..", ".."))
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	cmd := exec.CommandContext(ctx, "go", "list", "-mod=readonly", "-modfile="+filepath.Join(root, "build", "experiment.go.mod"), "-deps", "-f", "{{if .Module}}{{.Module.Path}} {{.Module.Version}}{{end}}", "./...", "github.com/inspektor-gadget/inspektor-gadget/pkg/container-utils/testutils", "github.com/inspektor-gadget/inspektor-gadget/cmd/ig/utils")
	cmd.Dir = root
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("selected dependency graph: %v\n%s", err, out)
	}
	found := false
	for _, line := range strings.Split(string(out), "\n") {
		fields := strings.Fields(line)
		if len(fields) == 0 {
			continue
		}
		switch fields[0] {
		case "github.com/containerd/containerd":
			t.Fatal("prepared collector/tools still import the containerd v1 client")
		case "github.com/containerd/containerd/v2":
			if len(fields) != 2 || fields[1] != "v2.3.7" {
				t.Fatalf("unexpected containerd v2 selection: %q", line)
			}
			found = true
		}
	}
	if !found {
		t.Fatal("prepared collector/tools do not use the patched containerd v2 client")
	}
}

func TestPreparationIdentityMatchesAcceptedSource(t *testing.T) {
	root := filepath.Join("..", "..")
	id, err := patchID(root, standard)
	if err != nil {
		t.Fatal(err)
	}
	if id != "b9a9d70db7e37bba07293a635cac94cc540688b85e1f3aa31c2e9ad25cfeecf3" {
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
