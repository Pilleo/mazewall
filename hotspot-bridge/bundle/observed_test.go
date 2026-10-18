package bundle

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
)

// Synthetic asset graph: this tests format negotiation, not native qualification.
func TestObservedManifestExpectedAndProfileIsolation(t *testing.T) {
	f := newAssetFixture(t)
	f.pack(t)
	path := filepath.Join(f.bundle, "manifest.json")
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var m Manifest
	if err := json.Unmarshal(b, &m); err != nil {
		t.Fatal(err)
	}
	if _, err := Verify(f.bundle, manifestDigest(t, f.bundle), wire.Observed25); err == nil {
		t.Fatal("strict bundle accepted as observed")
	}
	m.CollectorVersion, m.WireSchema = "3", 3
	write := func() {
		t.Helper()
		b, err := json.Marshal(m)
		if err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, b, 0644); err != nil {
			t.Fatal(err)
		}
	}
	write()
	v, err := Verify(f.bundle, manifestDigest(t, f.bundle), wire.Observed25)
	if err != nil {
		t.Fatal("valid synthetic schema3 graph", err)
	}
	target := wire.Target{PID: 123, StartTicks: 456, CgroupID: 789, MountNS: 42}
	x, err := v.Expected(target)
	if err != nil || x.Profile != wire.Observed25 || x.Schema != 3 || x.Target != target || x.Provider.PatchSHA256 != m.PatchSHA256 {
		t.Fatal(x, err)
	}
	x.Target.PID++
	x2, err := v.Expected(target)
	if err != nil || x2.Target != target {
		t.Fatal("Expected not caller-owned", x2, err)
	}
	if _, err := Verify(f.bundle, manifestDigest(t, f.bundle), wire.Strict25); err == nil {
		t.Fatal("observed bundle accepted as strict")
	}
	for _, mutate := range []func(){func() { m.CollectorVersion = "2" }, func() { m.CollectorVersion = "3"; m.CaptureABI = 3 }, func() { m.CaptureABI = 2; m.WireSchema = 2 }} {
		mutate()
		write()
		if _, err := Verify(f.bundle, manifestDigest(t, f.bundle), wire.Observed25); err == nil {
			t.Fatal("cross-format manifest admitted", m)
		}
	}
}
