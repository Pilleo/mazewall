package tooling

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"unicode/utf8"

	"github.com/Pilleo/mazewall/hotspot-bridge/bundle"
)

type preparationMode uint8

const (
	standard preparationMode = iota
	dropFirstDefinition
	identityObservations
)

var gadgetPatches = []string{"gadget-structured.patch", "gadget-health.patch", "gadget-epoch.patch", "gadget-shutdown.patch", "gadget-event-loss.patch", "gadget-finalization.patch", "gadget-definition-drain.patch", "gadget-auxiliary-ownership.patch", "gadget-containerd-v2.patch"}
var profilerPatches = []string{"profiler-health.patch", "profiler-quality.patch", "profiler-identity.patch", "profiler-epoch-capture.patch", "profiler-epoch-memory.patch", "profiler-epoch-handler.patch", "profiler-shutdown.patch", "profiler-definition-drain.patch", "profiler-metadata-retirement.patch", "profiler-auxiliary-ownership.patch"}

func additions(root, directory string) ([]string, error) {
	var paths []string
	err := filepath.WalkDir(filepath.Join(root, directory), func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if !entry.IsDir() && strings.HasSuffix(path, ".go.txt") {
			name, err := filepath.Rel(root, path)
			if err != nil {
				return err
			}
			paths = append(paths, filepath.ToSlash(name))
		}
		return nil
	})
	sort.Strings(paths)
	return paths, err
}

func preparationInputs(root string, mode preparationMode) ([]string, error) {
	var paths []string
	for _, name := range gadgetPatches {
		paths = append(paths, "patches/"+name)
	}
	gadget, err := additions(root, "patches/gadget-additions")
	if err != nil {
		return nil, err
	}
	paths = append(paths, gadget...)
	for _, name := range profilerPatches {
		paths = append(paths, "patches/"+name)
	}
	paths = append(paths, "scripts/rebuild-profiler-ebpf.sh")
	profiler, err := additions(root, "patches/profiler-additions")
	if err != nil {
		return nil, err
	}
	paths = append(paths, profiler...)
	switch mode {
	case standard:
	case dropFirstDefinition:
		paths = append(paths, "patches/faults/drop-first-definition.patch")
	case identityObservations:
		paths = append(paths, "patches/diagnostics/identity-observations.patch")
		extra, err := additions(root, "patches/diagnostic-additions")
		if err != nil {
			return nil, err
		}
		paths = append(paths, extra...)
	default:
		return nil, fmt.Errorf("invalid preparation mode: %d", mode)
	}
	return paths, nil
}

func patchID(root string, mode preparationMode) (string, error) {
	paths, err := preparationInputs(root, mode)
	if err != nil {
		return "", err
	}
	digest := sha256.New()
	for _, name := range paths {
		data, err := os.ReadFile(filepath.Join(root, name))
		if err != nil {
			return "", err
		}
		_, _ = digest.Write([]byte(name))
		_, _ = digest.Write([]byte{0})
		_, _ = digest.Write(data)
	}
	return hex.EncodeToString(digest.Sum(nil)), nil
}

type modulePin struct{ Module, Replacement, Version, Revision, Sum string }
type dependencyLock struct {
	Gadget, Profiler modulePin
	Images           map[string]string
}
type moduleDownload struct{ Dir, Sum string }

func command(root string, args ...string) error {
	child := exec.Command(args[0], args[1:]...)
	child.Dir = root
	child.Stdout = os.Stdout
	child.Stderr = os.Stderr
	if err := child.Run(); err != nil {
		return fmt.Errorf("%v: %w", args, err)
	}
	return nil
}

func download(root string, pin modulePin, module string) (moduleDownload, error) {
	child := exec.Command("go", "mod", "download", "-json", module+"@"+pin.Version)
	child.Dir = root
	child.Stderr = os.Stderr
	data, err := child.Output()
	if err != nil {
		return moduleDownload{}, err
	}
	var info moduleDownload
	if err := json.Unmarshal(data, &info); err != nil {
		return info, err
	}
	if info.Sum != pin.Sum || info.Dir == "" {
		return info, fmt.Errorf("locked module checksum/identity mismatch: %s", module)
	}
	return info, nil
}

func jsonFile(path string) (map[string]any, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	if !utf8.Valid(data) {
		return nil, fmt.Errorf("invalid UTF8 JSON: %s", path)
	}
	var value map[string]any
	err = json.Unmarshal(data, &value)
	return value, err
}

func writeJSON(path string, value any) error {
	data, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, append(data, '\n'), 0644)
}

func copyFile(source, destination string) error {
	input, err := os.Open(source)
	if err != nil {
		return err
	}
	defer input.Close()
	output, err := os.OpenFile(destination, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0644)
	if err != nil {
		return err
	}
	_, copyErr := io.Copy(output, input)
	closeErr := output.Close()
	return errors.Join(copyErr, closeErr)
}

func copyTree(source, destination string) error {
	if err := os.Mkdir(destination, 0755); err != nil {
		return err
	}
	return filepath.WalkDir(source, func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		relative, err := filepath.Rel(source, path)
		if err != nil {
			return err
		}
		if relative == "." {
			return nil
		}
		target := filepath.Join(destination, relative)
		if entry.IsDir() {
			return os.Mkdir(target, 0755)
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		if !info.Mode().IsRegular() {
			return fmt.Errorf("unsupported module source member: %s", path)
		}
		return copyFile(path, target)
	})
}

func applyPatches(root, destination string, names []string) error {
	for _, name := range names {
		if err := command(destination, "patch", "--batch", "--forward", "--fuzz=0", "-p1", "-i", filepath.Join(root, "patches", name)); err != nil {
			return err
		}
	}
	return nil
}

func copyAdditions(root, destination, directory string) error {
	paths, err := additions(root, directory)
	if err != nil {
		return err
	}
	for _, name := range paths {
		relative, err := filepath.Rel(directory, name)
		if err != nil {
			return err
		}
		target := filepath.Join(destination, strings.TrimSuffix(relative, ".txt"))
		if err := os.MkdirAll(filepath.Dir(target), 0755); err != nil {
			return err
		}
		content, err := os.ReadFile(filepath.Join(root, name))
		if err != nil {
			return err
		}
		if err := os.WriteFile(target, content, 0644); err != nil {
			return err
		}
	}
	return nil
}

func markerMatches(path string, expected map[string]any) (bool, error) {
	actual, err := jsonFile(path)
	if os.IsNotExist(err) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	if !reflect.DeepEqual(actual, expected) {
		return false, fmt.Errorf("generated dependency provenance mismatch: %s", path)
	}
	return true, nil
}

func fileHash(path string) (string, error) {
	return bundle.FileHash(path)
}

func verifyEpochArtifacts(directory string) error {
	artifacts, err := jsonFile(filepath.Join(directory, ".mw-epoch-artifacts.json"))
	if err != nil {
		return err
	}
	names := []string{"support/types.go", "support/ebpf/tracer.ebpf.amd64", "support/ebpf/tracer.ebpf.arm64"}
	if len(artifacts) != len(names) {
		return fmt.Errorf("incomplete generated epoch artifact inventory")
	}
	for _, name := range names {
		expected, ok := artifacts[name].(string)
		if !ok {
			return fmt.Errorf("missing generated epoch artifact: %s", name)
		}
		actual, err := fileHash(filepath.Join(directory, name))
		if err != nil {
			return err
		}
		if actual != expected {
			return fmt.Errorf("generated epoch artifact mismatch: %s", name)
		}
	}
	return nil
}

func prepare(root string, mode preparationMode) error {
	root, err := filepath.Abs(root)
	if err != nil {
		return err
	}
	data, err := os.ReadFile(filepath.Join(root, "dependencies.lock.json"))
	if err != nil {
		return err
	}
	var lock dependencyLock
	if err := json.Unmarshal(data, &lock); err != nil {
		return err
	}
	if err := command(root, "go", "mod", "verify"); err != nil {
		return err
	}
	gadget, err := download(root, lock.Gadget, lock.Gadget.Module)
	if err != nil {
		return err
	}
	profiler, err := download(root, lock.Profiler, lock.Profiler.Replacement)
	if err != nil {
		return err
	}
	id, err := patchID(root, mode)
	if err != nil {
		return err
	}
	manifest := map[string]any{"base_revision": lock.Gadget.Revision, "base_sum": gadget.Sum, "patch_sha256": id, "submitted_upstream": false, "fault_injection": nil, "profiler_base_revision": lock.Profiler.Revision, "profiler_base_sum": profiler.Sum, "capture_abi": float64(2), "bpf_builder": lock.Images["gadget_builder"]}
	name := "experiment"
	switch mode {
	case standard:
	case dropFirstDefinition:
		name = "recovery-test"
		manifest["fault_injection"] = "drop_first_definition"
	case identityObservations:
		name = "identity-test"
		manifest["diagnostic"] = "identity_observations"
	default:
		return fmt.Errorf("invalid preparation mode")
	}
	deps := filepath.Join(root, "build", "deps")
	if err := os.MkdirAll(deps, 0755); err != nil {
		return err
	}
	generatedProfiler := filepath.Join(deps, "profiler-"+id)
	profilerMarker := filepath.Join(generatedProfiler, ".mw-prepared.json")
	prepared, err := markerMatches(profilerMarker, manifest)
	if err != nil {
		return err
	}
	if !prepared {
		if err := copyTree(profiler.Dir, generatedProfiler); err != nil {
			return fmt.Errorf("incomplete/existing generated profiler dependency: %w", err)
		}
		if err := applyPatches(root, generatedProfiler, profilerPatches); err != nil {
			return err
		}
		if err := copyAdditions(root, generatedProfiler, "patches/profiler-additions"); err != nil {
			return err
		}
		if mode == identityObservations {
			if err := copyAdditions(root, generatedProfiler, "patches/diagnostic-additions"); err != nil {
				return err
			}
			if err := applyPatches(root, generatedProfiler, []string{"diagnostics/identity-observations.patch"}); err != nil {
				return err
			}
		}
		if err := command(root, "bash", filepath.Join(root, "scripts/rebuild-profiler-ebpf.sh"), generatedProfiler); err != nil {
			return err
		}
		artifacts := map[string]string{}
		for _, name := range []string{"support/types.go", "support/ebpf/tracer.ebpf.amd64", "support/ebpf/tracer.ebpf.arm64"} {
			hash, err := fileHash(filepath.Join(generatedProfiler, name))
			if err != nil {
				return err
			}
			artifacts[name] = hash
		}
		if err := writeJSON(filepath.Join(generatedProfiler, ".mw-epoch-artifacts.json"), artifacts); err != nil {
			return err
		}
		if err := writeJSON(profilerMarker, manifest); err != nil {
			return err
		}
	}
	if err := verifyEpochArtifacts(generatedProfiler); err != nil {
		return err
	}
	generated := filepath.Join(deps, "gadget-"+id)
	marker := filepath.Join(generated, ".mw-prepared.json")
	prepared, err = markerMatches(marker, manifest)
	if err != nil {
		return err
	}
	if !prepared {
		if err := copyTree(gadget.Dir, generated); err != nil {
			return fmt.Errorf("incomplete/existing generated dependency: %w", err)
		}
		if err := applyPatches(root, generated, gadgetPatches); err != nil {
			return err
		}
		if err := copyAdditions(root, generated, "patches/gadget-additions"); err != nil {
			return err
		}
		if mode == dropFirstDefinition {
			if err := applyPatches(root, generated, []string{"faults/drop-first-definition.patch"}); err != nil {
				return err
			}
		}
		if err := writeJSON(marker, manifest); err != nil {
			return err
		}
	}
	modfile := filepath.Join("build", name+".go.mod")
	for original, target := range map[string]string{"go.mod": modfile, "go.sum": filepath.Join("build", name+".go.sum")} {
		content, err := os.ReadFile(filepath.Join(root, original))
		if err != nil {
			return err
		}
		if err := os.WriteFile(filepath.Join(root, target), content, 0644); err != nil {
			return err
		}
	}
	for module, directory := range map[string]string{lock.Gadget.Module: generated, lock.Profiler.Module: generatedProfiler} {
		relative, err := filepath.Rel(root, directory)
		if err != nil {
			return err
		}
		if err := command(root, "go", "mod", "edit", "-modfile="+modfile, "-replace="+module+"=./"+filepath.ToSlash(relative)); err != nil {
			return err
		}
	}
	manifestName := "patch-manifest.json"
	if mode != standard {
		manifestName = name + "-manifest.json"
	}
	if err := writeJSON(filepath.Join(root, "build", manifestName), manifest); err != nil {
		return err
	}
	fmt.Printf("Prepared Gadget patch %s\n", id)
	return nil
}
