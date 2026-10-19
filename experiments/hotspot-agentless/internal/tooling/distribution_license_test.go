package tooling

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestSelectedBridgeLicenseIsPackagedAndAbsenceRejects(t *testing.T) {
	root, err := filepath.Abs("../..")
	if err != nil {
		t.Fatal(err)
	}
	graph, err := moduleGraph(root)
	if err != nil {
		t.Fatal(err)
	}
	dir, err := bridgeSource(graph)
	if err != nil {
		t.Fatal(err)
	}
	const module = "github.com/Pilleo/mazewall/hotspot-bridge"
	names, err := selectedModuleLicenses(module, dir)
	if err != nil || len(names) == 0 {
		t.Fatal("actual selected bridge notice missing", dir, err)
	}
	copy := filepath.Join(t.TempDir(), "sdk")
	if err := copySourceTree(dir, copy, true); err != nil {
		t.Fatal(err)
	}
	for _, name := range names {
		if err := os.Remove(filepath.Join(copy, filepath.FromSlash(name))); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := selectedModuleLicenses(module, copy); err == nil ||
		!strings.Contains(err.Error(), "missing selected-module license: "+module) {
		t.Fatal("unlicensed selected bridge was accepted", err)
	}
}
