package tooling

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"unicode/utf8"

	"golang.org/x/sys/unix"
)

const maxManifest = 65536
const maxAssetFiles = 256
const maxAssetBytes = 512 * 1024 * 1024
const acceptedPatch = "962b27903e20422116ca8528b7edbc8f19eafc94f986907461bd8fb1ded9f976"
const gadgetRevision = "7097db144ccd8dab15373f27ec98d2c3d22e4588"
const profilerRevision = "ef6cf29492c8a980f889052903fa2dd0c0765cc0"
const gadgetImagePrefix = "ghcr.io/inspektor-gadget/gadget/mw-hotspot-openat@"

var shaPattern = regexp.MustCompile(`^[a-f0-9]{64}$`)

type assetManifest struct {
	SchemaVersion    int               `json:"schema_version"`
	CollectorVersion string            `json:"collector_version"`
	CaptureABI       int               `json:"capture_abi"`
	WireSchema       int               `json:"wire_schema"`
	Platform         string            `json:"platform"`
	GadgetRevision   string            `json:"gadget_revision"`
	ProfilerRevision string            `json:"profiler_revision"`
	PatchSHA256      string            `json:"patch_sha256"`
	GadgetDigest     string            `json:"gadget_digest"`
	Files            map[string]string `json:"files"`
}

type ociDescriptor struct {
	MediaType *string `json:"mediaType,omitempty"`
	Digest    string  `json:"digest"`
	Size      *int64  `json:"size,omitempty"`
}
type ociDocument struct {
	SchemaVersion int             `json:"schemaVersion"`
	Manifests     []ociDescriptor `json:"manifests,omitempty"`
	Layers        []ociDescriptor `json:"layers,omitempty"`
	Config        *ociDescriptor  `json:"config,omitempty"`
}

type ociIndex struct {
	SchemaVersion int               `json:"schemaVersion"`
	Manifests     []json.RawMessage `json:"manifests"`
}

func ownedAsset(root, name string) (string, error) {
	parts := strings.Split(name, "/")
	info, err := os.Lstat(root)
	if err != nil {
		return "", err
	}
	if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return "", fmt.Errorf("missing/symbolic asset root: %s", root)
	}
	path := root
	for _, part := range parts {
		if part == "" || part == "." || part == ".." || strings.Contains(part, "\\") {
			return "", fmt.Errorf("invalid asset path: %s", name)
		}
		path = filepath.Join(path, part)
		info, err = os.Lstat(path)
		if err != nil {
			return "", err
		}
		if info.Mode()&os.ModeSymlink != 0 {
			return "", fmt.Errorf("symbolic asset path: %s", name)
		}
	}
	if !info.Mode().IsRegular() {
		return "", fmt.Errorf("missing regular asset: %s", name)
	}
	return path, nil
}

func boundedJSON(path string, value any) error {
	input, err := os.Open(path)
	if err != nil {
		return err
	}
	defer input.Close()
	data, err := io.ReadAll(io.LimitReader(input, maxManifest+1))
	if err != nil {
		return err
	}
	if len(data) > maxManifest || !utf8.Valid(data) {
		return fmt.Errorf("invalid/oversized asset JSON: %s", path)
	}
	return json.Unmarshal(data, value)
}

func digestName(digest string) (string, error) {
	if !strings.HasPrefix(digest, "sha256:") || !shaPattern.MatchString(strings.TrimPrefix(digest, "sha256:")) {
		return "", fmt.Errorf("invalid OCI digest: %s", digest)
	}
	return digest[7:], nil
}

func ociGraph(store, digest string) (map[string]string, error) {
	pending := []ociDescriptor{{Digest: digest}}
	found := map[string]string{}
	var total int64
	for len(pending) > 0 {
		descriptor := pending[len(pending)-1]
		pending = pending[:len(pending)-1]
		name, err := digestName(descriptor.Digest)
		if err != nil {
			return nil, err
		}
		path, err := ownedAsset(store, "blobs/sha256/"+name)
		if err != nil {
			return nil, err
		}
		info, err := os.Stat(path)
		if err != nil {
			return nil, err
		}
		if descriptor.Size != nil && *descriptor.Size != info.Size() {
			return nil, fmt.Errorf("OCI descriptor size mismatch: %s", name)
		}
		if _, ok := found[name]; ok {
			continue
		}
		actual, err := fileHash(path)
		if err != nil {
			return nil, err
		}
		if actual != name {
			return nil, fmt.Errorf("OCI blob checksum mismatch: %s", name)
		}
		found[name] = path
		total += info.Size()
		if len(found) > maxAssetFiles || total > maxAssetBytes {
			return nil, fmt.Errorf("OCI graph limit exceeded")
		}
		media := "application/vnd.oci.image.index.v1+json"
		if descriptor.MediaType != nil {
			media = *descriptor.MediaType
		}
		if media == "application/vnd.oci.image.index.v1+json" || media == "application/vnd.oci.image.manifest.v1+json" {
			var data ociDocument
			if err := boundedJSON(path, &data); err != nil {
				return nil, err
			}
			if data.SchemaVersion != 2 {
				return nil, fmt.Errorf("unsupported OCI schema")
			}
			pending = append(pending, data.Manifests...)
			pending = append(pending, data.Layers...)
			if data.Config != nil {
				pending = append(pending, *data.Config)
			}
		}
	}
	return found, nil
}

func verifyAssets(bundle string) (assetManifest, error) {
	var manifest assetManifest
	bundle, err := filepath.Abs(bundle)
	if err != nil {
		return manifest, err
	}
	path, err := ownedAsset(bundle, "manifest.json")
	if err != nil {
		return manifest, err
	}
	if err := boundedJSON(path, &manifest); err != nil {
		return manifest, err
	}
	if manifest.SchemaVersion != 1 || manifest.CollectorVersion != "2" || manifest.CaptureABI != 2 || manifest.WireSchema != 2 || manifest.Platform != "linux-amd64" || manifest.GadgetRevision != gadgetRevision || manifest.ProfilerRevision != profilerRevision || manifest.PatchSHA256 != acceptedPatch {
		return manifest, fmt.Errorf("unsupported asset version/ABI/provenance")
	}
	if len(manifest.Files) == 0 || len(manifest.Files) > maxAssetFiles {
		return manifest, fmt.Errorf("asset file limit exceeded")
	}
	digest, err := digestName(manifest.GadgetDigest)
	if err != nil {
		return manifest, err
	}
	for _, name := range []string{"mw-hotspot-collector", "oci/index.json", "oci/oci-layout", "oci/blobs/sha256/" + digest} {
		if _, ok := manifest.Files[name]; !ok {
			return manifest, fmt.Errorf("incomplete asset manifest: %s", name)
		}
	}
	var total int64
	for name, expected := range manifest.Files {
		if !shaPattern.MatchString(expected) {
			return manifest, fmt.Errorf("invalid asset checksum: %s", name)
		}
		path, err := ownedAsset(bundle, name)
		if err != nil {
			return manifest, err
		}
		info, err := os.Stat(path)
		if err != nil {
			return manifest, err
		}
		total += info.Size()
		if total > maxAssetBytes {
			return manifest, fmt.Errorf("asset size limit exceeded")
		}
		actual, err := fileHash(path)
		if err != nil {
			return manifest, err
		}
		if actual != expected {
			return manifest, fmt.Errorf("asset checksum mismatch: %s", name)
		}
	}
	if err := unix.Access(filepath.Join(bundle, "mw-hotspot-collector"), unix.X_OK); err != nil {
		return manifest, fmt.Errorf("collector is not executable: %w", err)
	}
	blobs, err := ociGraph(filepath.Join(bundle, "oci"), manifest.GadgetDigest)
	if err != nil {
		return manifest, err
	}
	for name := range blobs {
		if _, ok := manifest.Files["oci/blobs/sha256/"+name]; !ok {
			return manifest, fmt.Errorf("unlisted reachable OCI blob: %s", name)
		}
	}
	var index ociDocument
	indexPath, err := ownedAsset(filepath.Join(bundle, "oci"), "index.json")
	if err != nil {
		return manifest, err
	}
	if err := boundedJSON(indexPath, &index); err != nil {
		return manifest, err
	}
	found := false
	for _, entry := range index.Manifests {
		if entry.Digest == manifest.GadgetDigest {
			found = true
		}
	}
	if index.SchemaVersion != 2 || !found {
		return manifest, fmt.Errorf("gadget digest absent from OCI index")
	}
	return manifest, nil
}

func publishAssets(output string, populate func(string) error) (failure error) {
	if output == "" {
		return fmt.Errorf("explicit destination required")
	}
	output, err := filepath.Abs(output)
	if err != nil {
		return err
	}
	if filepath.Base(output) == string(filepath.Separator) {
		return fmt.Errorf("explicit destination required")
	}
	if _, err := os.Lstat(output); err == nil {
		return fmt.Errorf("destination exists: %s: %w", output, os.ErrExist)
	} else if !os.IsNotExist(err) {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(output), 0755); err != nil {
		return err
	}
	temp, err := os.MkdirTemp(filepath.Dir(output), ".mw-assets-")
	if err != nil {
		return err
	}
	defer func() { failure = errors.Join(failure, os.RemoveAll(temp)) }()
	staged := filepath.Join(temp, "bundle")
	if err := os.Mkdir(staged, 0755); err != nil {
		return err
	}
	if err := populate(staged); err != nil {
		return err
	}
	if _, err := verifyAssets(staged); err != nil {
		return err
	}
	// Linux no-replace publication also preserves a raced empty operator directory.
	return unix.Renameat2(unix.AT_FDCWD, staged, unix.AT_FDCWD, output, unix.RENAME_NOREPLACE)
}

func copyAsset(source, destination, name string) error {
	path, err := ownedAsset(source, name)
	if err != nil {
		return err
	}
	target := filepath.Join(destination, filepath.FromSlash(name))
	if err := os.MkdirAll(filepath.Dir(target), 0755); err != nil {
		return err
	}
	if err := copyFile(path, target); err != nil {
		return err
	}
	info, err := os.Stat(path)
	if err != nil {
		return err
	}
	return os.Chmod(target, info.Mode().Perm())
}

func packAssets(collector, store, image, output string) error {
	collector, err := filepath.Abs(collector)
	if err != nil {
		return err
	}
	store, err = filepath.Abs(store)
	if err != nil {
		return err
	}
	info, err := os.Lstat(collector)
	if err != nil {
		return err
	}
	if !info.Mode().IsRegular() {
		return fmt.Errorf("invalid collector executable")
	}
	if err := unix.Access(collector, unix.X_OK); err != nil {
		return err
	}
	if !strings.HasPrefix(image, gadgetImagePrefix) {
		return fmt.Errorf("unexpected gadget image")
	}
	digest := strings.TrimPrefix(image, gadgetImagePrefix)
	blobs, err := ociGraph(store, digest)
	if err != nil {
		return err
	}
	indexPath, err := ownedAsset(store, "index.json")
	if err != nil {
		return err
	}
	var index ociIndex
	if err := boundedJSON(indexPath, &index); err != nil {
		return err
	}
	var selected json.RawMessage
	for _, raw := range index.Manifests {
		var descriptor ociDescriptor
		if err := json.Unmarshal(raw, &descriptor); err != nil {
			return err
		}
		if descriptor.Digest == digest {
			selected = raw
			break
		}
	}
	if selected == nil {
		return fmt.Errorf("gadget absent from source OCI index")
	}
	return publishAssets(output, func(staged string) error {
		if err := copyAsset(filepath.Dir(collector), staged, filepath.Base(collector)); err != nil {
			return err
		}
		if filepath.Base(collector) != "mw-hotspot-collector" {
			if err := os.Rename(filepath.Join(staged, filepath.Base(collector)), filepath.Join(staged, "mw-hotspot-collector")); err != nil {
				return err
			}
		}
		oci := filepath.Join(staged, "oci")
		if err := os.MkdirAll(filepath.Join(oci, "blobs", "sha256"), 0755); err != nil {
			return err
		}
		if err := copyAsset(store, oci, "oci-layout"); err != nil {
			return err
		}
		if err := writeJSON(filepath.Join(oci, "index.json"), ociIndex{SchemaVersion: 2, Manifests: []json.RawMessage{selected}}); err != nil {
			return err
		}
		for name := range blobs {
			if err := copyAsset(store, oci, "blobs/sha256/"+name); err != nil {
				return err
			}
		}
		files := map[string]string{}
		err := filepath.WalkDir(staged, func(path string, entry os.DirEntry, err error) error {
			if err != nil {
				return err
			}
			if entry.IsDir() {
				return nil
			}
			name, err := filepath.Rel(staged, path)
			if err != nil {
				return err
			}
			hash, err := fileHash(path)
			if err != nil {
				return err
			}
			files[filepath.ToSlash(name)] = hash
			return nil
		})
		if err != nil {
			return err
		}
		manifest := assetManifest{SchemaVersion: 1, CollectorVersion: "2", CaptureABI: 2, WireSchema: 2, Platform: "linux-amd64", GadgetRevision: gadgetRevision, ProfilerRevision: profilerRevision, PatchSHA256: acceptedPatch, GadgetDigest: digest, Files: files}
		return writeJSON(filepath.Join(staged, "manifest.json"), manifest)
	})
}

func installAssets(bundle, output string) error {
	bundle, err := filepath.Abs(bundle)
	if err != nil {
		return err
	}
	manifest, err := verifyAssets(bundle)
	if err != nil {
		return err
	}
	return publishAssets(output, func(staged string) error {
		for name := range manifest.Files {
			if err := copyAsset(bundle, staged, name); err != nil {
				return err
			}
		}
		return copyAsset(bundle, staged, "manifest.json")
	})
}
