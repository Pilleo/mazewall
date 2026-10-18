package tooling

import (
	"github.com/Pilleo/mazewall/hotspot-bridge/bundle"
	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"testing"
)

func TestPackObservedSeparateFormatPreservesStrictPolicy(t *testing.T) {
	f := newAssetFixture(t)
	if err := Run(f.root, []string{"pack", "--runtime-profile=otel_hotspot25_observed_v1", "--collector=" + f.collector, "--store=" + f.store, "--image=" + gadgetImagePrefix + f.digest, "--output=" + f.bundle}); err != nil {
		t.Fatal("valid synthetic observed pack", err)
	}
	sha, err := bundle.ManifestSHA256(f.bundle)
	if err != nil {
		t.Fatal(err)
	}
	v, err := bundle.Verify(f.bundle, sha, wire.Observed25)
	if err != nil {
		t.Fatal(err)
	}
	m, err := v.Manifest()
	if err != nil || m.CollectorVersion != "3" || m.WireSchema != 3 || m.CaptureABI != 2 {
		t.Fatal(m, err)
	}
	if _, err := verifyAssets(f.bundle); err == nil {
		t.Fatal("observed admitted by strict tooling")
	}
	f = newAssetFixture(t)
	f.pack(t)
	sha, err = bundle.ManifestSHA256(f.bundle)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := bundle.Verify(f.bundle, sha, wire.Observed25); err == nil {
		t.Fatal("strict bundle admitted as observed")
	}
}
