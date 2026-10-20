package tooling

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"

	"github.com/Pilleo/mazewall/hotspot-bridge/bundle"
	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"golang.org/x/sys/unix"
)

const acceptedPatch = "89b3599935df5206ff710292574e4480a09da535f8529b4b0462b8c29a7e0f50"
const gadgetRevision = "7097db144ccd8dab15373f27ec98d2c3d22e4588"
const profilerRevision = "ef6cf29492c8a980f889052903fa2dd0c0765cc0"
const gadgetImagePrefix = "ghcr.io/inspektor-gadget/gadget/mw-hotspot-openat@"

var shaPattern = regexp.MustCompile(`^[a-f0-9]{64}$`)

type assetManifest = bundle.Manifest

type ociDescriptor struct {
	Digest string `json:"digest"`
}

type ociIndex struct {
	SchemaVersion int               `json:"schemaVersion"`
	Manifests     []json.RawMessage `json:"manifests"`
}

func ownedAsset(root, name string) (string, error) { return bundle.OwnedAsset(root, name) }
func boundedJSON(path string, value any) error     { return bundle.BoundedJSON(path, value) }
func digestName(digest string) (string, error)     { return bundle.DigestName(digest) }

func verifyAssets(root string) (assetManifest, error) {
	return verifyAssetsWithAccess(root, unix.Access)
}

func verifyAssetsWithAccess(root string, access func(string, uint32) error) (assetManifest, error) {
	return verifyProfileAssets(root, wire.Strict25, access)
}

func verifyProfileAssets(root string, profile wire.Profile, access func(string, uint32) error) (assetManifest, error) {
	var manifest assetManifest
	// Legacy tooling pins native provenance; deployment callers supply their own trusted SHA.
	manifestSHA, err := bundle.ManifestSHA256(root)
	if err != nil {
		return manifest, err
	}
	collector, err := bundle.OwnedAsset(root, "mw-hotspot-collector")
	if err != nil {
		return manifest, err
	}
	// Mode bits in the portable verifier do not establish effective ACL/mount access.
	if err := access(collector, unix.X_OK); err != nil {
		return manifest, fmt.Errorf("collector is not executable: %w", err)
	}
	verified, err := bundle.Verify(root, manifestSHA, profile)
	if err != nil {
		return manifest, err
	}
	manifest, err = verified.Manifest()
	if err != nil {
		return manifest, err
	}
	if manifest.GadgetRevision != gadgetRevision || manifest.ProfilerRevision != profilerRevision || manifest.PatchSHA256 != acceptedPatch {
		return manifest, fmt.Errorf("unsupported asset version/ABI/provenance")
	}
	return manifest, nil
}

func publishAssets(output string, populate func(string) error) (failure error) {
	return publishProfileAssets(output, wire.Strict25, populate)
}

func publishProfileAssets(output string, profile wire.Profile, populate func(string) error) (failure error) {
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
	if _, err := verifyProfileAssets(staged, profile, unix.Access); err != nil {
		return err
	}
	// Linux no-replace publication also preserves a raced empty operator directory.
	return unix.Renameat2(unix.AT_FDCWD, staged, unix.AT_FDCWD, output, unix.RENAME_NOREPLACE)
}

func copyAsset(source, destination, name string) error {
	path, err := bundle.OwnedAsset(source, name)
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
	return packProfileAssets(collector, store, image, output, wire.Strict25)
}

func packProfileAssets(collector, store, image, output string, profile wire.Profile) error {
	version := 2
	switch profile {
	case wire.Strict25:
	case wire.Observed25:
		version = 3
	default:
		return fmt.Errorf("unsupported bundle profile")
	}
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
	blobs, err := bundle.OCIGraph(store, digest)
	if err != nil {
		return err
	}
	indexPath, err := bundle.OwnedAsset(store, "index.json")
	if err != nil {
		return err
	}
	var index ociIndex
	if err := bundle.BoundedJSON(indexPath, &index); err != nil {
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
	return publishProfileAssets(output, profile, func(staged string) error {
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
			hash, err := bundle.FileHash(path)
			if err != nil {
				return err
			}
			files[filepath.ToSlash(name)] = hash
			return nil
		})
		if err != nil {
			return err
		}
		manifest := assetManifest{SchemaVersion: 1, CollectorVersion: fmt.Sprint(version), CaptureABI: 2, WireSchema: version, Platform: "linux-amd64", GadgetRevision: gadgetRevision, ProfilerRevision: profilerRevision, PatchSHA256: acceptedPatch, GadgetDigest: digest, Files: files}
		return writeJSON(filepath.Join(staged, "manifest.json"), manifest)
	})
}

func installAssets(root, output string) error {
	root, err := filepath.Abs(root)
	if err != nil {
		return err
	}
	manifest, err := verifyAssets(root)
	if err != nil {
		return err
	}
	return publishAssets(output, func(staged string) error {
		for name := range manifest.Files {
			if err := copyAsset(root, staged, name); err != nil {
				return err
			}
		}
		return copyAsset(root, staged, "manifest.json")
	})
}
