package bundle

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
)

func TestVerifySelectedRootDescriptors(t *testing.T) {
	for _, profile := range []wire.Profile{wire.Strict25, wire.Observed25} {
		for _, manifestRoot := range []bool{false, true} {
			kind := "index"
			if manifestRoot {
				kind = "manifest"
			}
			for _, name := range []string{"valid", "same-media", "absent-media", "absent-size", "wrong-size", "second-wrong-size", "opaque-media", "empty-media", "conflicting-media", "conflicting-document-media"} {
				t.Run(string(profile)+"/"+kind+"/"+name, func(t *testing.T) {
					f := newAssetFixture(t)
					var index ociDocument
					must(t, BoundedJSON(filepath.Join(f.store, "index.json"), &index))
					entry := index.Manifests[0]
					if manifestRoot {
						var root ociDocument
						must(t, BoundedJSON(filepath.Join(f.store, "blobs/sha256", f.digest[7:]), &root))
						entry = root.Manifests[0]
						f.digest = entry.Digest
					}
					index.Manifests = []ociDescriptor{entry}
					data, err := json.Marshal(index)
					must(t, err)
					must(t, os.WriteFile(filepath.Join(f.store, "index.json"), data, 0644))
					f.pack(t)
					m := fixtureManifest(t, f)
					if profile == wire.Observed25 {
						m.CollectorVersion, m.WireSchema = "3", 3
						writeManifest(t, f.bundle, m)
					}
					oldPin := manifestDigest(t, f.bundle)
					v, err := Verify(f.bundle, oldPin, profile)
					must(t, err)
					_, err = v.Collector()
					must(t, err)
					changed := entry
					wrongSize := int64(1)
					opaque, empty := "application/vnd.gadget.config.v1+json", ""
					document := "application/vnd.oci.image.manifest.v1+json"
					if manifestRoot {
						document = "application/vnd.oci.image.index.v1+json"
					}
					wantReject := false
					switch name {
					case "valid":
					case "same-media":
						index.Manifests = append(index.Manifests, entry)
					case "absent-media":
						index.Manifests[0].MediaType = nil
					case "absent-size":
						index.Manifests[0].Size = nil
					case "wrong-size":
						index.Manifests[0].Size = &wrongSize
						wantReject = true
					case "second-wrong-size":
						changed.Size = &wrongSize
						index.Manifests = append(index.Manifests, changed)
						wantReject = true
					case "opaque-media":
						index.Manifests[0].MediaType = &opaque
						wantReject = true
					case "empty-media":
						index.Manifests[0].MediaType = &empty
						wantReject = true
					case "conflicting-media", "conflicting-document-media":
						changed.MediaType = &opaque
						if name == "conflicting-document-media" {
							changed.MediaType = &document
						}
						index.Manifests = append(index.Manifests, changed)
						wantReject = true
					}
					data, err = json.Marshal(index)
					must(t, err)
					must(t, os.WriteFile(filepath.Join(f.bundle, "oci/index.json"), data, 0644))
					sum := sha256.Sum256(data)
					m.Files["oci/index.json"] = hex.EncodeToString(sum[:])
					writeManifest(t, f.bundle, m)
					pin := manifestDigest(t, f.bundle)
					if pin != oldPin {
						old, err := Verify(f.bundle, oldPin, profile)
						if err == nil {
							t.Fatal("changed manifest accepted under old pin")
						}
						if _, err := old.Collector(); err == nil {
							t.Fatal("old-pin failure returned usable token")
						}
					}
					v, err = Verify(f.bundle, pin, profile)
					if (err != nil) != wantReject {
						t.Errorf("wantReject=%t, got %v", wantReject, err)
					}
					if _, err := v.Collector(); (err != nil) != wantReject {
						t.Errorf("collector token: wantReject=%t, got %v", wantReject, err)
					}
				})
			}
		}
	}
}
