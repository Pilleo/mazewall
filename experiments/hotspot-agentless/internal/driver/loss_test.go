package driver

import (
	"os"
	"path/filepath"
	"testing"
)

func TestLossSourceOnlyReusesExactPatchedBytesAndPreservesUnknownOutput(t *testing.T) {
	root := t.TempDir()
	for _, directory := range []string{"patches/faults", "gadgets/openat-stack", "build"} {
		if err := os.MkdirAll(filepath.Join(root, directory), 0755); err != nil {
			t.Fatal(err)
		}
	}
	patch := []byte("--- a/program.bpf.c\n+++ b/program.bpf.c\n@@ -1 +1 @@\n-original\n+drop sequence 42\n")
	for path, data := range map[string][]byte{"patches/faults/drop-marked-event.patch": patch, "gadgets/openat-stack/program.bpf.c": []byte("original\n"), "gadgets/openat-stack/gadget.yaml": []byte("name: test\n")} {
		if err := os.WriteFile(filepath.Join(root, path), data, 0644); err != nil {
			t.Fatal(err)
		}
	}
	output, err := PrepareLossSource(root)
	if err != nil {
		t.Fatal(err)
	}
	again, err := PrepareLossSource(root)
	if err != nil || again != output {
		t.Fatalf("unchanged prepared source not reusable: %s %v", again, err)
	}
	program := filepath.Join(output, "program.bpf.c")
	if err := os.WriteFile(program, []byte("unknown output"), 0644); err != nil {
		t.Fatal(err)
	}
	if _, err := PrepareLossSource(root); err == nil {
		t.Fatal("changed prepared source reused")
	}
	data, err := os.ReadFile(program)
	if err != nil || string(data) != "unknown output" {
		t.Fatal("unknown output overwritten")
	}
	if err := os.WriteFile(filepath.Join(root, "gadgets/openat-stack/gadget.yaml"), []byte("name: changed\n"), 0644); err != nil {
		t.Fatal(err)
	}
	changed, err := PrepareLossSource(root)
	if err != nil || changed == output {
		t.Fatalf("source change did not invalidate preparation: %s %v", changed, err)
	}
}
