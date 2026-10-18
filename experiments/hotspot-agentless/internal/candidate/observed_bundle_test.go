package candidate

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Pilleo/mazewall/hotspot-bridge/bundle"
	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
)

func TestObservedActualBundleProfiles(t *testing.T) {
	root := os.Getenv("MW_OBSERVED_BUNDLES")
	if root == "" {
		t.Skip("explicit actual bundles not selected")
	}
	for _, test := range []struct {
		name           string
		profile, wrong wire.Profile
	}{
		{"assets-strict", wire.Strict25, wire.Observed25},
		{"assets-observed", wire.Observed25, wire.Strict25},
	} {
		t.Run(test.name, func(t *testing.T) {
			path := filepath.Join(root, test.name)
			hash, err := bundle.ManifestSHA256(path)
			if err != nil {
				t.Fatal(err)
			}
			if _, err := bundle.Verify(path, hash, test.profile); err != nil {
				t.Fatal("actual full-byte/OCI positive", err)
			}
			if _, err := bundle.Verify(path, hash, test.wrong); err == nil {
				t.Fatal("actual wrong profile admitted")
			}
			if _, err := bundle.Verify(path, strings.Repeat("0", 64), test.profile); err == nil {
				t.Fatal("actual wrong manifest pin admitted")
			}
		})
	}
}
