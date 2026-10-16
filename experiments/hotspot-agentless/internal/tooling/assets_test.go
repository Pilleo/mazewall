package tooling

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

type assetFixture struct{ root, store, collector, bundle, layer, digest string }

func newAssetFixture(t *testing.T) assetFixture {
	t.Helper()
	f := assetFixture{root: t.TempDir()}
	f.store = filepath.Join(f.root, "source")
	f.collector = filepath.Join(f.root, "collector")
	f.bundle = filepath.Join(f.root, "bundle")
	if err := os.MkdirAll(filepath.Join(f.store, "blobs", "sha256"), 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(f.collector, []byte("ELF fixture"), 0755); err != nil {
		t.Fatal(err)
	}
	push := func(data []byte, media string) map[string]any {
		sum := sha256.Sum256(data)
		hash := hex.EncodeToString(sum[:])
		if err := os.WriteFile(filepath.Join(f.store, "blobs", "sha256", hash), data, 0644); err != nil {
			t.Fatal(err)
		}
		return map[string]any{"mediaType": media, "digest": "sha256:" + hash, "size": len(data)}
	}
	encode := func(value any) []byte {
		data, err := json.Marshal(value)
		if err != nil {
			t.Fatal(err)
		}
		return data
	}
	layer := push([]byte("native BPF fixture"), "application/vnd.gadget.ebpf.program.v1+binary")
	f.layer = filepath.Join(f.store, "blobs", "sha256", layer["digest"].(string)[7:])
	config := push([]byte("{}"), "application/vnd.gadget.config.v1+json")
	manifest := push(encode(map[string]any{"schemaVersion": 2, "config": config, "layers": []any{layer}}), "application/vnd.oci.image.manifest.v1+json")
	manifest["platform"] = map[string]string{"os": "linux", "architecture": "amd64"}
	top := push(encode(map[string]any{"schemaVersion": 2, "manifests": []any{manifest}}), "application/vnd.oci.image.index.v1+json")
	f.digest = top["digest"].(string)
	if err := os.WriteFile(filepath.Join(f.store, "index.json"), encode(map[string]any{"schemaVersion": 2, "manifests": []any{top}}), 0644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(f.store, "oci-layout"), []byte(`{"imageLayoutVersion":"1.0.0"}`), 0644); err != nil {
		t.Fatal(err)
	}
	return f
}

func (f assetFixture) pack(t *testing.T) {
	t.Helper()
	if err := packAssets(f.collector, f.store, "ghcr.io/inspektor-gadget/gadget/mw-hotspot-openat@"+f.digest, f.bundle); err != nil {
		t.Fatal(err)
	}
}

func TestAssetsCompleteGraphAndExclusiveInstall(t *testing.T) {
	f := newAssetFixture(t)
	f.pack(t)
	manifest, err := verifyAssets(f.bundle)
	if err != nil {
		t.Fatal(err)
	}
	if manifest.GadgetDigest != f.digest {
		t.Fatal("bundle changed selected OCI identity")
	}
	if _, err := os.Stat(filepath.Join(f.bundle, "oci", "blobs", "sha256", filepath.Base(f.layer))); err != nil {
		t.Fatal(err)
	}
	destination := filepath.Join(f.root, "installed")
	if err := installAssets(f.bundle, destination); err != nil {
		t.Fatal(err)
	}
	if _, err := verifyAssets(destination); err != nil {
		t.Fatal(err)
	}
	if err := installAssets(f.bundle, destination); err == nil {
		t.Fatal("existing installation replaced")
	}
}

func TestAssetsMissingCorruptAndTamperedBytesNeverPublish(t *testing.T) {
	for _, missing := range []bool{false, true} {
		f := newAssetFixture(t)
		if missing {
			if err := os.Remove(f.layer); err != nil {
				t.Fatal(err)
			}
		} else {
			if err := os.WriteFile(f.layer, []byte("corrupt"), 0644); err != nil {
				t.Fatal(err)
			}
		}
		if err := packAssets(f.collector, f.store, "ghcr.io/inspektor-gadget/gadget/mw-hotspot-openat@"+f.digest, f.bundle); err == nil {
			t.Fatal("invalid transitive BPF blob admitted")
		}
		if _, err := os.Lstat(f.bundle); !os.IsNotExist(err) {
			t.Fatal("failed package was published")
		}
	}
	f := newAssetFixture(t)
	f.pack(t)
	if err := os.WriteFile(filepath.Join(f.bundle, "mw-hotspot-collector"), []byte("tampered"), 0755); err != nil {
		t.Fatal(err)
	}
	destination := filepath.Join(f.root, "installed")
	if err := installAssets(f.bundle, destination); err == nil {
		t.Fatal("tampered helper installed")
	}
	if _, err := os.Lstat(destination); !os.IsNotExist(err) {
		t.Fatal("failed install was published")
	}
}

func TestAssetsRefuseWrongABIUnlistedReachableBlobAndAliases(t *testing.T) {
	f := newAssetFixture(t)
	f.pack(t)
	path := filepath.Join(f.bundle, "manifest.json")
	manifest, err := jsonFile(path)
	if err != nil {
		t.Fatal(err)
	}
	manifest["capture_abi"] = float64(1)
	if err := writeJSON(path, manifest); err != nil {
		t.Fatal(err)
	}
	if _, err := verifyAssets(f.bundle); err == nil {
		t.Fatal("unsupported ABI admitted")
	}
	manifest["capture_abi"] = float64(2)
	delete(manifest["files"].(map[string]any), "oci/blobs/sha256/"+filepath.Base(f.layer))
	if err := writeJSON(path, manifest); err != nil {
		t.Fatal(err)
	}
	if _, err := verifyAssets(f.bundle); err == nil {
		t.Fatal("unlisted reachable layer admitted")
	}
	f = newAssetFixture(t)
	linked := filepath.Join(f.root, "linked")
	if err := os.Symlink(f.collector, linked); err != nil {
		t.Fatal(err)
	}
	if err := packAssets(linked, f.store, "ghcr.io/inspektor-gadget/gadget/mw-hotspot-openat@"+f.digest, f.bundle); err == nil {
		t.Fatal("symbolic helper admitted")
	}
	for _, name := range []string{"../outside", "/outside", "a//b", "a/./b", "a\\b"} {
		if _, err := ownedAsset(f.store, name); err == nil {
			t.Fatalf("escaping path admitted: %s", name)
		}
	}
}

func TestAssetPublicationPreservesDestinationCreatedDuringPopulation(t *testing.T) {
	f := newAssetFixture(t)
	f.pack(t)
	destination := filepath.Join(f.root, "raced-install")
	var acquired os.FileInfo
	err := publishAssets(destination, func(staged string) error {
		manifest, err := verifyAssets(f.bundle)
		if err != nil {
			return err
		}
		for name := range manifest.Files {
			if err := copyAsset(f.bundle, staged, name); err != nil {
				return err
			}
		}
		if err := copyAsset(f.bundle, staged, "manifest.json"); err != nil {
			return err
		}
		if err := os.Mkdir(destination, 0755); err != nil {
			return err
		}
		acquired, err = os.Stat(destination)
		return err
	})
	if err == nil {
		t.Fatal("unknown raced empty directory replaced")
	}
	actual, err := os.Stat(destination)
	if err != nil {
		t.Fatal(err)
	}
	if !os.SameFile(acquired, actual) {
		t.Fatal("unknown destination identity changed")
	}
}
