package bundle

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"maps"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"unicode/utf8"

	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
)

const maxManifest = 65536
const maxAssetFiles = 256
const maxAssetBytes = 512 * 1024 * 1024
const gadgetImagePrefix = "ghcr.io/inspektor-gadget/gadget/mw-hotspot-openat@"

var shaPattern = regexp.MustCompile(`^[a-f0-9]{64}$`)
var revisionPattern = regexp.MustCompile(`^[a-f0-9]{40}$`)

// Verified is an opaque result, valid only after Verify succeeds. Assets must
// remain operator-owned and read-only between verification and execution.
type Verified struct {
	root, collector, store, image string
	manifest                      Manifest
	profile                       wire.Profile
	valid                         bool
}

func (v Verified) Collector() (string, error) {
	if !v.valid {
		return "", errors.New("unverified collector")
	}
	return v.collector, nil
}

func (v Verified) Store() (string, error) {
	if !v.valid {
		return "", errors.New("unverified store")
	}
	return v.store, nil
}

func (v Verified) Image() (string, error) {
	if !v.valid {
		return "", errors.New("unverified image")
	}
	return v.image, nil
}

func (v Verified) Expected(target wire.Target) (wire.Expected, error) {
	if !v.valid {
		return wire.Expected{}, errors.New("unverified expected identity")
	}
	return wire.Expected{Target: target, Profile: v.profile, Schema: v.manifest.WireSchema,
		Provider: wire.Provider{Name: "otel-ebpf-profiler-via-gadget", GadgetRevision: v.manifest.GadgetRevision,
			ProfilerRevision: v.manifest.ProfilerRevision, PatchSHA256: v.manifest.PatchSHA256}}, nil
}

// Manifest returns an owned copy for native admission policy and install inventory.
func (v Verified) Manifest() (Manifest, error) {
	if !v.valid {
		return Manifest{}, errors.New("unverified manifest")
	}
	m := v.manifest
	m.Files = maps.Clone(m.Files)
	return m, nil
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

// OwnedAsset resolves a regular non-symlink member for native copying/distribution.
func OwnedAsset(root, name string) (string, error) {
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

func readBounded(input io.ReadCloser) (data []byte, failure error) {
	defer func() { failure = errors.Join(failure, input.Close()) }()
	data, err := io.ReadAll(io.LimitReader(input, maxManifest+1))
	if err != nil {
		return nil, err
	}
	if len(data) > maxManifest || !utf8.Valid(data) {
		return nil, errors.New("invalid/oversized asset JSON")
	}
	return data, nil
}

func readJSONBytes(path string) ([]byte, error) {
	input, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	data, err := readBounded(input)
	if err != nil {
		return nil, fmt.Errorf("asset JSON %s: %w", path, err)
	}
	return data, nil
}

// ManifestSHA256 bounds and hashes a local packaging manifest. This is not an
// independent deployment trust pin and does not verify any assets.
func ManifestSHA256(root string) (string, error) {
	path, err := OwnedAsset(root, "manifest.json")
	if err != nil {
		return "", err
	}
	data, err := readJSONBytes(path)
	if err != nil {
		return "", err
	}
	hash := sha256.Sum256(data)
	return hex.EncodeToString(hash[:]), nil
}

// BoundedJSON shares the verifier's JSON limits with native OCI selection.
func BoundedJSON(path string, value any) error {
	data, err := readJSONBytes(path)
	if err != nil {
		return err
	}
	return json.Unmarshal(data, value)
}

func hashInput(input io.ReadCloser) (digest string, failure error) {
	defer func() { failure = errors.Join(failure, input.Close()) }()
	hash := sha256.New()
	if _, err := io.Copy(hash, input); err != nil {
		return "", err
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}

// FileHash rehashes every byte for native preparation and distribution receipts.
func FileHash(path string) (string, error) {
	input, err := os.Open(path)
	if err != nil {
		return "", err
	}
	return hashInput(input)
}

// DigestName validates the digest selected by native OCI packing/CLI code.
func DigestName(digest string) (string, error) {
	if !strings.HasPrefix(digest, "sha256:") || !shaPattern.MatchString(strings.TrimPrefix(digest, "sha256:")) {
		return "", fmt.Errorf("invalid OCI digest: %s", digest)
	}
	return digest[7:], nil
}

// OCIGraph verifies the complete selected graph for native packing and Verify.
func OCIGraph(store, digest string) (map[string]string, error) {
	return ociGraph(store, []ociDescriptor{{Digest: digest}})
}

func ociGraph(store string, pending []ociDescriptor) (map[string]string, error) {
	found := map[string]string{}
	mediaByDigest := map[string]string{}
	var total int64
	for len(pending) > 0 {
		descriptor := pending[len(pending)-1]
		pending = pending[:len(pending)-1]
		name, err := DigestName(descriptor.Digest)
		if err != nil {
			return nil, err
		}
		path, err := OwnedAsset(store, "blobs/sha256/"+name)
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
		media := "application/vnd.oci.image.index.v1+json"
		if descriptor.MediaType != nil {
			media = *descriptor.MediaType
		}
		if prior, ok := mediaByDigest[name]; ok && prior != media {
			return nil, fmt.Errorf("conflicting OCI media type: %s", name)
		}
		if _, ok := found[name]; ok {
			continue
		}
		total += info.Size()
		if len(found) >= maxAssetFiles || total > maxAssetBytes {
			return nil, fmt.Errorf("OCI graph limit exceeded")
		}
		actual, err := FileHash(path)
		if err != nil {
			return nil, err
		}
		if actual != name {
			return nil, fmt.Errorf("OCI blob checksum mismatch: %s", name)
		}
		found[name] = path
		mediaByDigest[name] = media
		if media == "application/vnd.oci.image.index.v1+json" || media == "application/vnd.oci.image.manifest.v1+json" {
			var data ociDocument
			if err := BoundedJSON(path, &data); err != nil {
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

// Verify binds the actual manifest bytes to an independently trusted SHA256,
// then rehashes every listed asset and reachable OCI blob. It never executes a
// helper. Native consumers must additionally enforce effective execute access.
func Verify(root, expectedManifestSHA string, profile wire.Profile) (Verified, error) {
	var invalid Verified
	version := 2
	switch profile {
	case wire.Strict25:
	case wire.Observed25:
		version = 3
	default:
		return invalid, fmt.Errorf("unsupported asset profile: %s", profile)
	}
	if !shaPattern.MatchString(expectedManifestSHA) {
		return invalid, errors.New("invalid trusted manifest checksum")
	}
	root, err := filepath.Abs(root)
	if err != nil {
		return invalid, err
	}
	path, err := OwnedAsset(root, "manifest.json")
	if err != nil {
		return invalid, err
	}
	data, err := readJSONBytes(path)
	if err != nil {
		return invalid, err
	}
	hash := sha256.Sum256(data)
	if hex.EncodeToString(hash[:]) != expectedManifestSHA {
		return invalid, errors.New("asset manifest checksum mismatch")
	}
	var manifest Manifest
	if err := json.Unmarshal(data, &manifest); err != nil {
		return invalid, err
	}
	if manifest.SchemaVersion != 1 || manifest.CollectorVersion != fmt.Sprint(version) || manifest.CaptureABI != 2 || manifest.WireSchema != version || manifest.Platform != "linux-amd64" || !revisionPattern.MatchString(manifest.GadgetRevision) || !revisionPattern.MatchString(manifest.ProfilerRevision) || !shaPattern.MatchString(manifest.PatchSHA256) {
		return invalid, fmt.Errorf("unsupported asset version/ABI/provenance")
	}
	if len(manifest.Files) == 0 || len(manifest.Files) > maxAssetFiles {
		return invalid, fmt.Errorf("asset file limit exceeded")
	}
	digest, err := DigestName(manifest.GadgetDigest)
	if err != nil {
		return invalid, err
	}
	for _, name := range []string{"mw-hotspot-collector", "oci/index.json", "oci/oci-layout", "oci/blobs/sha256/" + digest} {
		if _, ok := manifest.Files[name]; !ok {
			return invalid, fmt.Errorf("incomplete asset manifest: %s", name)
		}
	}
	var total int64
	for name, expected := range manifest.Files {
		if !shaPattern.MatchString(expected) {
			return invalid, fmt.Errorf("invalid asset checksum: %s", name)
		}
		path, err := OwnedAsset(root, name)
		if err != nil {
			return invalid, err
		}
		info, err := os.Stat(path)
		if err != nil {
			return invalid, err
		}
		total += info.Size()
		if total > maxAssetBytes {
			return invalid, fmt.Errorf("asset size limit exceeded")
		}
		actual, err := FileHash(path)
		if err != nil {
			return invalid, err
		}
		if actual != expected {
			return invalid, fmt.Errorf("asset checksum mismatch: %s", name)
		}
	}
	collector := filepath.Join(root, "mw-hotspot-collector")
	info, err := os.Stat(collector)
	if err != nil {
		return invalid, err
	}
	if info.Mode().Perm()&0111 == 0 {
		return invalid, fmt.Errorf("collector has no executable mode: %w", os.ErrPermission)
	}
	store := filepath.Join(root, "oci")
	var index ociDocument
	indexPath, err := OwnedAsset(store, "index.json")
	if err != nil {
		return invalid, err
	}
	if err := BoundedJSON(indexPath, &index); err != nil {
		return invalid, err
	}
	var roots []ociDescriptor
	for _, entry := range index.Manifests {
		if entry.Digest == manifest.GadgetDigest {
			if entry.MediaType != nil && *entry.MediaType != "application/vnd.oci.image.index.v1+json" && *entry.MediaType != "application/vnd.oci.image.manifest.v1+json" {
				return invalid, fmt.Errorf("unsupported selected OCI root media type")
			}
			roots = append(roots, entry)
		}
	}
	if index.SchemaVersion != 2 || len(roots) == 0 {
		return invalid, fmt.Errorf("gadget digest absent from OCI index")
	}
	blobs, err := ociGraph(store, roots)
	if err != nil {
		return invalid, err
	}
	for name := range blobs {
		if _, ok := manifest.Files["oci/blobs/sha256/"+name]; !ok {
			return invalid, fmt.Errorf("unlisted reachable OCI blob: %s", name)
		}
	}
	return Verified{root: root, collector: collector, store: store, image: gadgetImagePrefix + manifest.GadgetDigest, manifest: manifest, profile: profile, valid: true}, nil
}
