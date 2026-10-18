package bundle

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"testing"

	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
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
	files := map[string]string{}
	copyOne := func(src, name string, mode fs.FileMode) {
		b, err := os.ReadFile(src)
		if err != nil {
			t.Fatal(err)
		}
		dst := filepath.Join(f.bundle, filepath.FromSlash(name))
		if err := os.MkdirAll(filepath.Dir(dst), 0755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(dst, b, mode); err != nil {
			t.Fatal(err)
		}
		h := sha256.Sum256(b)
		files[name] = hex.EncodeToString(h[:])
	}
	copyOne(f.collector, "mw-hotspot-collector", 0755)
	err := filepath.WalkDir(f.store, func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if entry.IsDir() {
			return nil
		}
		rel, err := filepath.Rel(f.store, path)
		if err != nil {
			return err
		}
		copyOne(path, "oci/"+filepath.ToSlash(rel), 0644)
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	// Synthetic fixture provenance, never native acceptance pins.
	m := Manifest{SchemaVersion: 1, CollectorVersion: "2", CaptureABI: 2, WireSchema: 2,
		Platform: "linux-amd64", GadgetRevision: strings.Repeat("1", 40),
		ProfilerRevision: strings.Repeat("2", 40), PatchSHA256: strings.Repeat("3", 64),
		GadgetDigest: f.digest, Files: files}
	writeManifest(t, f.bundle, m)
}

func writeManifest(t *testing.T, root string, m Manifest) {
	t.Helper()
	b, err := json.Marshal(m)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "manifest.json"), b, 0644); err != nil {
		t.Fatal(err)
	}
}

func manifestDigest(t *testing.T, root string) string {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(root, "manifest.json"))
	if err != nil {
		t.Fatal(err)
	}
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}

func TestVerifyRehashesUnchangedLengthLayer(t *testing.T) {
	f := newAssetFixture(t)
	f.pack(t)
	expected := manifestDigest(t, f.bundle)
	if _, err := Verify(f.bundle, expected, wire.Strict25); err != nil {
		t.Fatal("valid fixture rejected before mutation", err)
	}
	layer := filepath.Join(f.bundle, "oci", "blobs", "sha256", filepath.Base(f.layer))
	info, err := os.Stat(layer)
	if err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(layer)
	if err != nil {
		t.Fatal(err)
	}
	b[0] ^= 1
	if err := os.WriteFile(layer, b, 0644); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(layer, info.ModTime(), info.ModTime()); err != nil {
		t.Fatal(err)
	}
	if _, err := Verify(f.bundle, expected, wire.Strict25); err == nil {
		t.Fatal("same-size transitive mutation admitted")
	}
}

func fixtureManifest(t *testing.T, f assetFixture) Manifest {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(f.bundle, "manifest.json"))
	if err != nil {
		t.Fatal(err)
	}
	var m Manifest
	if err := json.Unmarshal(b, &m); err != nil {
		t.Fatal(err)
	}
	return m
}

func TestVerifyRejectsInvalidAssets(t *testing.T) {
	for _, name := range []string{"missing", "collector", "unlisted-layer", "checksum", "abi", "wire", "version", "schema", "platform", "provenance", "traversal", "absolute", "backslash", "alias", "root-alias", "non-executable", "index", "file-limit", "byte-limit"} {
		t.Run(name, func(t *testing.T) {
			f := newAssetFixture(t)
			f.pack(t)
			if _, err := Verify(f.bundle, manifestDigest(t, f.bundle), wire.Strict25); err != nil {
				t.Fatal("valid fixture rejected before mutation", err)
			}
			m := fixtureManifest(t, f)
			layer := "oci/blobs/sha256/" + filepath.Base(f.layer)
			root := f.bundle
			switch name {
			case "missing":
				must(t, os.Remove(filepath.Join(root, layer)))
			case "collector":
				must(t, os.WriteFile(filepath.Join(root, "mw-hotspot-collector"), []byte("tampered"), 0755))
			case "unlisted-layer":
				delete(m.Files, layer)
			case "checksum":
				m.Files[layer] = "SHA256:bad"
			case "abi":
				m.CaptureABI = 1
			case "wire":
				m.WireSchema = 3
			case "version":
				m.CollectorVersion = "3"
			case "schema":
				m.SchemaVersion = 2
			case "platform":
				m.Platform = "linux-arm64"
			case "provenance":
				m.PatchSHA256 = "bad"
			case "traversal", "absolute", "backslash":
				path := map[string]string{"traversal": "../outside", "absolute": "/outside", "backslash": "a\\b"}[name]
				m.Files[path] = strings.Repeat("0", 64)
			case "alias":
				must(t, os.Remove(filepath.Join(root, layer)))
				must(t, os.Symlink(f.layer, filepath.Join(root, layer)))
			case "root-alias":
				root = filepath.Join(f.root, "alias")
				must(t, os.Symlink(f.bundle, root))
			case "non-executable":
				must(t, os.Chmod(filepath.Join(root, "mw-hotspot-collector"), 0644))
			case "index":
				data := []byte(`{"schemaVersion":2,"manifests":[]}`)
				must(t, os.WriteFile(filepath.Join(root, "oci/index.json"), data, 0644))
				h := sha256.Sum256(data)
				m.Files["oci/index.json"] = hex.EncodeToString(h[:])
			case "file-limit":
				for i := 0; i < 257; i++ {
					m.Files["extra-"+strconv.Itoa(i)] = strings.Repeat("0", 64)
				}
			case "byte-limit":
				file, err := os.OpenFile(filepath.Join(root, layer), os.O_WRONLY, 0)
				must(t, err)
				must(t, file.Truncate(512*1024*1024+1))
				must(t, file.Close())
			}
			writeManifest(t, f.bundle, m)
			if _, err := Verify(root, manifestDigest(t, f.bundle), wire.Strict25); err == nil {
				t.Fatal("invalid asset admitted")
			}
		})
	}
}

func must(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
}

func TestVerifyManifestTrustAndJSONBoundaries(t *testing.T) {
	for _, data := range [][]byte{[]byte("{"), {0xff}, []byte(`{} {}`), []byte(strings.Repeat(" ", 65537))} {
		f := newAssetFixture(t)
		f.pack(t)
		must(t, os.WriteFile(filepath.Join(f.bundle, "manifest.json"), data, 0644))
		if _, err := Verify(f.bundle, manifestDigest(t, f.bundle), wire.Strict25); err == nil {
			t.Fatal("invalid JSON admitted")
		}
	}
	f := newAssetFixture(t)
	f.pack(t)
	for _, digest := range []string{"", strings.Repeat("0", 64), "sha256:" + manifestDigest(t, f.bundle)} {
		if _, err := Verify(f.bundle, digest, wire.Strict25); err == nil {
			t.Fatal("untrusted manifest admitted")
		}
	}
	for _, profile := range []wire.Profile{"", "unknown", wire.Observed25} {
		if _, err := Verify(f.bundle, manifestDigest(t, f.bundle), profile); err == nil {
			t.Fatal("unadmitted profile accepted")
		}
	}
	expected := manifestDigest(t, f.bundle)
	m := fixtureManifest(t, f)
	m.GadgetRevision = strings.Repeat("4", 40)
	writeManifest(t, f.bundle, m)
	if _, err := Verify(f.bundle, expected, wire.Strict25); err == nil {
		t.Fatal("changed manifest accepted under old trust pin")
	}
}

func TestVerifiedBoundaryOwnsMetadataAndNeverInvokesHelper(t *testing.T) {
	var zero Verified
	_, collectorErr := zero.Collector()
	_, storeErr := zero.Store()
	_, imageErr := zero.Image()
	_, expectedErr := zero.Expected(wire.Target{})
	_, manifestErr := zero.Manifest()
	if collectorErr == nil || storeErr == nil || imageErr == nil || expectedErr == nil || manifestErr == nil {
		t.Fatal("zero verification token usable")
	}
	f := newAssetFixture(t)
	sentinel := filepath.Join(f.root, "invoked")
	must(t, os.WriteFile(f.collector, []byte("#!/bin/sh\ntouch '"+sentinel+"'\n"), 0755))
	f.pack(t)
	v, err := Verify(f.bundle, manifestDigest(t, f.bundle), wire.Strict25)
	must(t, err)
	collector, err := v.Collector()
	must(t, err)
	store, err := v.Store()
	must(t, err)
	image, err := v.Image()
	must(t, err)
	if collector != filepath.Join(f.bundle, "mw-hotspot-collector") || store != filepath.Join(f.bundle, "oci") || image != "ghcr.io/inspektor-gadget/gadget/mw-hotspot-openat@"+f.digest {
		t.Fatal("verified launch paths/identity differ")
	}
	target := wire.Target{ContainerID: "container", ImageID: "image", PID: 7, StartTicks: 11, CgroupID: 13, MountNS: 17}
	e, err := v.Expected(target)
	must(t, err)
	if e.Target != target || e.Schema != 2 || e.Profile != wire.Strict25 || e.Provider != (wire.Provider{Name: "otel-ebpf-profiler-via-gadget", GadgetRevision: strings.Repeat("1", 40), ProfilerRevision: strings.Repeat("2", 40), PatchSHA256: strings.Repeat("3", 64)}) {
		t.Fatal("expected consumer binding differs")
	}
	m, err := v.Manifest()
	must(t, err)
	delete(m.Files, "mw-hotspot-collector")
	m.GadgetRevision = "changed"
	second, err := v.Manifest()
	must(t, err)
	if second.Files["mw-hotspot-collector"] == "" || second.GadgetRevision != strings.Repeat("1", 40) {
		t.Fatal("getter leaked mutable verified metadata")
	}
	if _, err := os.Stat(sentinel); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("verifier invoked helper", err)
	}
}

func TestVerifyDescriptorSizeAndTransitiveDigest(t *testing.T) {
	for _, sizeMismatch := range []bool{false, true} {
		f := newAssetFixture(t)
		path := filepath.Join(f.store, "blobs/sha256", f.digest[7:])
		data, err := os.ReadFile(path)
		must(t, err)
		var top map[string]any
		must(t, json.Unmarshal(data, &top))
		descriptor := top["manifests"].([]any)[0].(map[string]any)
		if sizeMismatch {
			descriptor["size"] = float64(1)
		} else {
			descriptor["digest"] = "sha256:" + strings.Repeat("0", 64)
		}
		data, err = json.Marshal(top)
		must(t, err)
		h := sha256.Sum256(data)
		f.digest = "sha256:" + hex.EncodeToString(h[:])
		must(t, os.WriteFile(filepath.Join(f.store, "blobs/sha256", f.digest[7:]), data, 0644))
		index, err := json.Marshal(map[string]any{"schemaVersion": 2, "manifests": []any{map[string]any{"digest": f.digest}}})
		must(t, err)
		must(t, os.WriteFile(filepath.Join(f.store, "index.json"), index, 0644))
		f.pack(t)
		if _, err := Verify(f.bundle, manifestDigest(t, f.bundle), wire.Strict25); err == nil {
			t.Fatal("invalid transitive descriptor admitted")
		}
	}
}

type failingInput struct {
	io.Reader
	readErr, closeErr error
}

func (f failingInput) Read(p []byte) (int, error) {
	if f.readErr != nil {
		return 0, f.readErr
	}
	return f.Reader.Read(p)
}

func (f failingInput) Close() error { return f.closeErr }

func TestReadAndHashRetainReadAndCloseCauses(t *testing.T) {
	for _, readErr := range []error{nil, syscall.EACCES, syscall.EPERM} {
		input := func() failingInput {
			return failingInput{Reader: strings.NewReader("{}"), readErr: readErr, closeErr: syscall.EIO}
		}
		_, readFailure := readBounded(input())
		_, hashFailure := hashInput(input())
		for _, err := range []error{readFailure, hashFailure} {
			if !errors.Is(err, syscall.EIO) || (readErr != nil && !errors.Is(err, readErr)) {
				t.Fatal("read/close cause lost", err)
			}
		}
	}
}

func TestVerifyRehashesGraphEvenWithUpdatedInventory(t *testing.T) {
	f := newAssetFixture(t)
	f.pack(t)
	if _, err := Verify(f.bundle, manifestDigest(t, f.bundle), wire.Strict25); err != nil {
		t.Fatal("valid fixture rejected before mutation", err)
	}
	m := fixtureManifest(t, f)
	name := "oci/blobs/sha256/" + filepath.Base(f.layer)
	data, err := os.ReadFile(filepath.Join(f.bundle, name))
	must(t, err)
	data[0] ^= 1
	must(t, os.WriteFile(filepath.Join(f.bundle, name), data, 0644))
	h := sha256.Sum256(data)
	m.Files[name] = hex.EncodeToString(h[:])
	writeManifest(t, f.bundle, m)
	v, err := Verify(f.bundle, manifestDigest(t, f.bundle), wire.Strict25)
	if err == nil || !strings.Contains(err.Error(), "OCI blob checksum mismatch") {
		t.Fatal("updated inventory bypassed OCI content-address validation", err)
	}
	if _, err := v.Collector(); err == nil {
		t.Fatal("failed verification returned a usable token")
	}
}

func TestLocalManifestHashIsBoundedAndHashesExactBytes(t *testing.T) {
	f := newAssetFixture(t)
	f.pack(t)
	path := filepath.Join(f.bundle, "manifest.json")
	data, err := os.ReadFile(path)
	must(t, err)
	must(t, os.WriteFile(path, append(data, '\n'), 0644))
	digest, err := ManifestSHA256(f.bundle)
	must(t, err)
	if digest != manifestDigest(t, f.bundle) {
		t.Fatal("local packaging hash lost original manifest bytes")
	}
	for _, bad := range [][]byte{[]byte(strings.Repeat(" ", 65537)), {0xff}} {
		must(t, os.WriteFile(path, bad, 0644))
		if _, err := ManifestSHA256(f.bundle); err == nil {
			t.Fatal("local hash bypassed manifest bounds")
		}
	}
}

func TestFilesystemFailuresRetainOriginalCause(t *testing.T) {
	root := t.TempDir()
	missing := filepath.Join(root, "missing")
	_, hashErr := FileHash(missing)
	jsonErr := BoundedJSON(missing, new(Manifest))
	_, pathErr := OwnedAsset(root, "missing")
	_, manifestErr := ManifestSHA256(root)
	_, verifyErr := Verify(root, strings.Repeat("0", 64), wire.Strict25)
	for _, err := range []error{hashErr, jsonErr, pathErr, manifestErr, verifyErr} {
		if !errors.Is(err, os.ErrNotExist) {
			t.Fatal("filesystem error cause lost", err)
		}
	}
}
