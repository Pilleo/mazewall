package tooling

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

func TestReleaseFootprintChecksRealELFAndLinkedProvenance(t *testing.T) {
	root := t.TempDir()
	directory := filepath.Join(root, "cmd/mw-hotspot-collector")
	if err := os.MkdirAll(directory, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "go.mod"), []byte("module io.mazewall/experiments/hotspot-agentless\n\ngo 1.26\n"), 0644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(directory, "main.go"), []byte("package main\nimport \"fmt\"\nvar patch string\nfunc main(){fmt.Println(patch)}\n"), 0644); err != nil {
		t.Fatal(err)
	}
	build := func(name, identity string, strip bool) string {
		t.Helper()
		output := filepath.Join(root, name)
		flags := "-X main.patch=" + identity
		if strip {
			flags = "-s -w " + flags
		}
		child := exec.Command("go", "build", "-trimpath", "-buildvcs=false", "-ldflags", flags, "-o", output, "./cmd/mw-hotspot-collector")
		child.Dir = root
		child.Env = append(os.Environ(), "GOWORK=off", "CGO_ENABLED=0", "GOOS=linux", "GOARCH=amd64")
		if data, err := child.CombinedOutput(); err != nil {
			t.Fatalf("real Go ELF build: %v\n%s", err, data)
		}
		return output
	}
	const identity = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	baseline := build("baseline", identity, false)
	candidate := build("candidate", identity, true)
	if _, err := verifyReleaseFootprint(baseline, candidate, identity); err != nil {
		t.Fatal(err)
	}
	if _, err := verifyReleaseFootprint(candidate, baseline, identity); err == nil {
		t.Fatal("unstripped/larger ELF admitted")
	}
	data, err := os.ReadFile(baseline)
	if err != nil {
		t.Fatal(err)
	}
	larger := filepath.Join(root, "larger-baseline")
	if err := os.WriteFile(larger, append(data, make([]byte, 4096)...), 0755); err != nil {
		t.Fatal(err)
	}
	if _, err := verifyReleaseFootprint(larger, baseline, identity); err == nil || !strings.Contains(err.Error(), "debug/symbol section") {
		t.Fatalf("smaller unstripped binary did not fail section admission: %v", err)
	}
	if _, err := verifyReleaseFootprint(baseline, candidate, "0000000000000000000000000000000000000000000000000000000000000000"); err == nil {
		t.Fatal("wrong linked preparation identity admitted")
	}
}
