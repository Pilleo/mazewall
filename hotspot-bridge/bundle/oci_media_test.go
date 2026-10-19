package bundle

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"testing"

	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
)

func TestOCIMediaAndSharedChildren(t *testing.T) {
	for _, conflicting := range []bool{false, true} {
		for _, reverse := range []bool{false, true} {
			for _, removeDisk := range []bool{false, true} {
				for _, unlist := range []bool{false, true} {
					t.Run(fmt.Sprintf("conflict=%t/reverse=%t/removeDisk=%t/unlist=%t", conflicting, reverse, removeDisk, unlist), func(t *testing.T) {
						f := newAssetFixture(t)
						var root ociDocument
						must(t, BoundedJSON(filepath.Join(f.store, "blobs/sha256", f.digest[7:]), &root))
						first, second := root.Manifests[0], root.Manifests[0]
						if conflicting {
							opaque := "application/vnd.gadget.config.v1+json"
							second.MediaType = &opaque
						}
						root.Manifests = []ociDescriptor{first, second}
						if reverse {
							root.Manifests[0], root.Manifests[1] = second, first
						}
						data, err := json.Marshal(root)
						must(t, err)
						sum := sha256.Sum256(data)
						f.digest = "sha256:" + hex.EncodeToString(sum[:])
						must(t, os.WriteFile(filepath.Join(f.store, "blobs/sha256", f.digest[7:]), data, 0644))
						index, err := json.Marshal(ociDocument{SchemaVersion: 2,
							Manifests: []ociDescriptor{{Digest: f.digest}}})
						must(t, err)
						must(t, os.WriteFile(filepath.Join(f.store, "index.json"), index, 0644))
						f.pack(t)
						layer := "oci/blobs/sha256/" + filepath.Base(f.layer)
						if removeDisk {
							must(t, os.Remove(filepath.Join(f.bundle, layer)))
						}
						if unlist {
							m := fixtureManifest(t, f)
							delete(m.Files, layer)
							writeManifest(t, f.bundle, m)
						}
						v, err := Verify(f.bundle, manifestDigest(t, f.bundle), wire.Strict25)
						wantReject := conflicting || removeDisk || unlist
						if (err != nil) != wantReject {
							t.Fatalf("wantReject=%v: %v", wantReject, err)
						}
						if wantReject {
							if _, e := v.Collector(); e == nil {
								t.Fatal("failed token usable")
							}
						}
					})
				}
			}
		}
	}
}
