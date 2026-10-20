package tooling

import (
	"archive/tar"
	"compress/gzip"
	"debug/buildinfo"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"golang.org/x/sys/unix"
)

const sourceDateEpoch = 1790985600

func distributionFiles(root string) ([]string, error) {
	info, err := os.Lstat(root)
	if err != nil {
		return nil, err
	}
	if !info.IsDir() {
		return nil, fmt.Errorf("invalid/symbolic distribution root")
	}
	var names []string
	err = filepath.WalkDir(root, func(path string, entry os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if entry.Type()&os.ModeSymlink != 0 {
			return fmt.Errorf("symbolic distribution path: %s", path)
		}
		if entry.IsDir() {
			return nil
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		if !info.Mode().IsRegular() {
			return fmt.Errorf("unsupported distribution member: %s", path)
		}
		name, err := filepath.Rel(root, path)
		if err != nil {
			return err
		}
		name = filepath.ToSlash(name)
		if strings.ContainsAny(name, "\r\n") {
			return fmt.Errorf("invalid distribution filename")
		}
		names = append(names, name)
		return nil
	})
	sort.Strings(names)
	return names, err
}

func writeDistributionChecksums(root string) error {
	names, err := distributionFiles(root)
	if err != nil {
		return err
	}
	var index strings.Builder
	for _, name := range names {
		if name == "SHA256SUMS" {
			continue
		}
		hash, err := fileHash(filepath.Join(root, filepath.FromSlash(name)))
		if err != nil {
			return err
		}
		fmt.Fprintf(&index, "%s  %s\n", hash, name)
	}
	return os.WriteFile(filepath.Join(root, "SHA256SUMS"), []byte(index.String()), 0644)
}

func checkDistributionChecksums(root string) error {
	path, err := ownedAsset(root, "SHA256SUMS")
	if err != nil {
		return err
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	listed := map[string]bool{}
	for _, line := range strings.Split(strings.TrimSuffix(string(data), "\n"), "\n") {
		fields := strings.SplitN(line, "  ", 2)
		if len(fields) != 2 || !shaPattern.MatchString(fields[0]) || listed[fields[1]] {
			return fmt.Errorf("invalid distribution checksum index")
		}
		name := fields[1]
		path, err := ownedAsset(root, name)
		if err != nil {
			return err
		}
		actual, err := fileHash(path)
		if err != nil {
			return err
		}
		if actual != fields[0] {
			return fmt.Errorf("distribution checksum mismatch: %s", name)
		}
		listed[name] = true
	}
	names, err := distributionFiles(root)
	if err != nil {
		return err
	}
	actual := 0
	for _, name := range names {
		if name == "SHA256SUMS" {
			continue
		}
		actual++
		if !listed[name] {
			return fmt.Errorf("unlisted distribution file: %s", name)
		}
	}
	if actual != len(listed) {
		return fmt.Errorf("distribution checksum inventory mismatch")
	}
	return nil
}

func archiveDistribution(root, output string) (failure error) {
	names, err := distributionFiles(root)
	if err != nil {
		return err
	}
	file, err := os.OpenFile(output, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0644)
	if err != nil {
		return err
	}
	defer func() { failure = errors.Join(failure, file.Close()) }()
	compressed := gzip.NewWriter(file)
	compressed.Header.ModTime = time.Unix(sourceDateEpoch, 0)
	compressed.Header.Name = ""
	defer func() { failure = errors.Join(failure, compressed.Close()) }()
	writer := tar.NewWriter(compressed)
	defer func() { failure = errors.Join(failure, writer.Close()) }()
	for _, name := range names {
		path, err := ownedAsset(root, name)
		if err != nil {
			return err
		}
		info, err := os.Stat(path)
		if err != nil {
			return err
		}
		header, err := tar.FileInfoHeader(info, "")
		if err != nil {
			return err
		}
		header.Name = name
		header.Uid = 0
		header.Gid = 0
		header.Uname = ""
		header.Gname = ""
		header.ModTime = time.Unix(sourceDateEpoch, 0)
		header.AccessTime = time.Time{}
		header.ChangeTime = time.Time{}
		header.Mode = 0644
		header.Format = tar.FormatPAX
		if info.Mode().Perm()&0111 != 0 {
			header.Mode = 0755
		}
		if err := writer.WriteHeader(header); err != nil {
			return err
		}
		input, err := os.Open(path)
		if err != nil {
			return err
		}
		_, copyErr := io.Copy(writer, input)
		closeErr := input.Close()
		if err := errors.Join(copyErr, closeErr); err != nil {
			return err
		}
	}
	return nil
}

type graphModule struct {
	Path, Version, Dir, Sum string
	Replace                 *graphModule
}

func moduleGraph(root string) (map[string]graphModule, error) {
	args := []string{"list", "-buildvcs=false"}
	if work := os.Getenv("GOWORK"); work == "" || work == "off" {
		args = append(args, "-modfile", "build/experiment.go.mod")
	}
	args = append(args, "-m", "-json", "all")
	child := exec.Command("go", args...)
	child.Dir = root
	child.Stderr = os.Stderr
	data, err := child.Output()
	if err != nil {
		return nil, err
	}
	decoder := json.NewDecoder(strings.NewReader(string(data)))
	modules := map[string]graphModule{}
	for {
		var module graphModule
		err := decoder.Decode(&module)
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return nil, err
		}
		modules[module.Path] = module
	}
	return modules, nil
}

func bridgeSource(graph map[string]graphModule) (string, error) {
	module, ok := graph["github.com/Pilleo/mazewall/hotspot-bridge"]
	if module.Replace != nil {
		module = *module.Replace
	}
	if !ok || module.Dir == "" {
		return "", fmt.Errorf("missing actual hotspot-bridge source")
	}
	return module.Dir, nil
}

func verifiedPreparationIdentity(root string) (string, error) {
	id, err := patchID(root, standard)
	if err != nil {
		return "", err
	}
	data, err := os.ReadFile(filepath.Join(root, "dependencies.lock.json"))
	if err != nil {
		return "", err
	}
	var lock dependencyLock
	if err := json.Unmarshal(data, &lock); err != nil {
		return "", err
	}
	expected := map[string]any{"base_revision": lock.Gadget.Revision, "base_sum": lock.Gadget.Sum, "patch_sha256": id, "submitted_upstream": false, "fault_injection": nil, "profiler_base_revision": lock.Profiler.Revision, "profiler_base_sum": lock.Profiler.Sum, "capture_abi": float64(2), "bpf_builder": lock.Images["gadget_builder"]}
	for _, name := range []string{"build/patch-manifest.json", "build/deps/gadget-" + id + "/.mw-prepared.json", "build/deps/profiler-" + id + "/.mw-prepared.json"} {
		valid, err := markerMatches(filepath.Join(root, name), expected)
		if err != nil {
			return "", err
		}
		if !valid {
			return "", fmt.Errorf("missing prepared native provenance: %s", name)
		}
	}
	if err := verifyEpochArtifacts(filepath.Join(root, "build/deps/profiler-"+id)); err != nil {
		return "", err
	}
	return id, nil
}

func copySourceTree(source, destination string, excludeGenerated bool) error {
	if err := os.Mkdir(destination, 0755); err != nil {
		return err
	}
	return filepath.WalkDir(source, func(path string, entry os.DirEntry, err error) error {
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
		if excludeGenerated && (entry.Name() == "build" || entry.Name() == "__pycache__") {
			if entry.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if entry.Type()&os.ModeSymlink != 0 {
			return fmt.Errorf("symbolic source/distribution path: %s", path)
		}
		target := filepath.Join(destination, relative)
		info, err := entry.Info()
		if err != nil {
			return err
		}
		if entry.IsDir() {
			// Staging directories need owner access for copying and cleanup; file modes stay verbatim.
			return os.Mkdir(target, info.Mode().Perm()|0700)
		}
		if !info.Mode().IsRegular() {
			return fmt.Errorf("unsupported source member: %s", path)
		}
		if err := copyFile(path, target); err != nil {
			return err
		}
		return os.Chmod(target, info.Mode().Perm())
	})
}

func selectedModuleLicenses(modulePath, directory string) ([]string, error) {
	var names []string
	err := filepath.WalkDir(directory, func(path string, entry os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if entry.IsDir() {
			return nil
		}
		name := strings.ToLower(entry.Name())
		for _, prefix := range []string{"license", "copying", "notice", "copyright"} {
			if strings.HasPrefix(name, prefix) {
				relative, err := filepath.Rel(directory, path)
				if err != nil {
					return err
				}
				names = append(names, filepath.ToSlash(relative))
				break
			}
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	sort.Strings(names)
	if len(names) == 0 {
		return nil, fmt.Errorf("missing selected-module license: %s", modulePath)
	}
	return names, nil
}

func releaseDistribution(engine, bundle, output, libraryVersion string) (failure error) {
	if output == "" || libraryVersion == "" {
		return fmt.Errorf("explicit release destination/library version required")
	}
	output, err := filepath.Abs(output)
	if err != nil {
		return err
	}
	for _, path := range []string{output, output + ".sha256"} {
		if _, err := os.Lstat(path); err == nil {
			return fmt.Errorf("distribution destination exists: %s", path)
		} else if !os.IsNotExist(err) {
			return err
		}
	}
	manifest, err := verifyAssets(bundle)
	if err != nil {
		return err
	}
	identity, err := verifiedPreparationIdentity(engine)
	if err != nil {
		return err
	}
	if identity != manifest.PatchSHA256 {
		return fmt.Errorf("asset and actual prepared source identity differ")
	}
	info, err := buildinfo.ReadFile(filepath.Join(bundle, "mw-hotspot-collector"))
	if err != nil {
		return err
	}
	if len(info.Deps) == 0 {
		return fmt.Errorf("collector has no embedded module provenance")
	}
	graph, err := moduleGraph(engine)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(output), 0755); err != nil {
		return err
	}
	temp, err := os.MkdirTemp(filepath.Dir(output), ".mw-release-")
	if err != nil {
		return err
	}
	defer func() { failure = errors.Join(failure, os.RemoveAll(temp)) }()
	root := filepath.Join(temp, "release")
	if err := os.Mkdir(root, 0755); err != nil {
		return err
	}
	if err := installAssets(bundle, filepath.Join(root, "assets")); err != nil {
		return err
	}
	var notices []map[string]any
	for _, linked := range info.Deps {
		module, ok := graph[linked.Path]
		if !ok {
			return fmt.Errorf("linked module absent from prepared graph: %s", linked.Path)
		}
		replacement := module
		if module.Replace != nil {
			replacement = *module.Replace
		}
		if replacement.Dir == "" {
			return fmt.Errorf("missing selected module source: %s", module.Path)
		}
		names, err := selectedModuleLicenses(module.Path, replacement.Dir)
		if err != nil {
			return err
		}
		for _, name := range names {
			destination := filepath.Join(root, "licenses", module.Path, filepath.FromSlash(name))
			if err := os.MkdirAll(filepath.Dir(destination), 0755); err != nil {
				return err
			}
			source, err := ownedAsset(replacement.Dir, name)
			if err != nil {
				return err
			}
			if err := copyFile(source, destination); err != nil {
				return err
			}
		}
		var version, replacementVersion, sum any
		if module.Version != "" {
			version = module.Version
		}
		if replacement.Version != "" {
			replacementVersion = replacement.Version
		}
		if replacement.Sum != "" {
			sum = replacement.Sum
		}
		notices = append(notices, map[string]any{"module": module.Path, "version": version, "replacement_module": replacement.Path, "replacement_version": replacementVersion, "sum": sum, "notices": names})
	}
	goRootOutput, err := exec.Command("go", "env", "GOROOT").Output()
	if err != nil {
		return err
	}
	goRoot := strings.TrimSpace(string(goRootOutput))
	license := filepath.Join(goRoot, "LICENSE")
	if _, err := os.Stat(license); os.IsNotExist(err) {
		license = filepath.Join("/usr/share/doc", strings.Replace(filepath.Base(goRoot), "go-", "golang-", 1), "copyright")
	} else if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Join(root, "licenses/go"), 0755); err != nil {
		return err
	}
	if err := copyFile(license, filepath.Join(root, "licenses/go/LICENSE")); err != nil {
		return err
	}
	gadgetModule, ok := graph["github.com/inspektor-gadget/inspektor-gadget"]
	if !ok || gadgetModule.Replace == nil {
		return fmt.Errorf("missing actual patched gadget source")
	}
	gadget := gadgetModule.Replace.Dir
	profilerModule, ok := graph["go.opentelemetry.io/ebpf-profiler"]
	if !ok || profilerModule.Replace == nil {
		return fmt.Errorf("missing actual patched profiler source")
	}
	bridge, err := bridgeSource(graph)
	if err != nil {
		return err
	}
	if err := os.Mkdir(filepath.Join(root, "licenses/mazewall"), 0755); err != nil {
		return err
	}
	if err := copyFile(filepath.Join(gadget, "LICENSE"), filepath.Join(root, "licenses/mazewall/LICENSE")); err != nil {
		return err
	}
	sourceRoot := filepath.Join(temp, "source")
	if err := os.Mkdir(sourceRoot, 0755); err != nil {
		return err
	}
	for _, copy := range []struct {
		from, to  string
		generated bool
	}{{engine, "mazewall-hotspot", true}, {bridge, "hotspot-bridge", true}, {gadget, "inspektor-gadget", false}, {profilerModule.Replace.Dir, "opentelemetry-ebpf-profiler", false}} {
		if err := copySourceTree(copy.from, filepath.Join(sourceRoot, copy.to), copy.generated); err != nil {
			return err
		}
	}
	if err := archiveDistribution(sourceRoot, filepath.Join(root, "native-source.tar.gz")); err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(root, "collector-build-info.txt"), []byte(info.String()), 0644); err != nil {
		return err
	}
	if err := copyFile(filepath.Join(engine, "dependencies.lock.json"), filepath.Join(root, "dependencies.lock.json")); err != nil {
		return err
	}
	if err := copyFile(filepath.Join(filepath.Dir(engine), "HOTSPOT_AGENTLESS.md"), filepath.Join(root, "HOTSPOT_AGENTLESS.md")); err != nil {
		return err
	}
	assetHash, err := fileHash(filepath.Join(bundle, "manifest.json"))
	if err != nil {
		return err
	}
	if err := writeJSON(filepath.Join(root, "provenance.json"), map[string]any{"schema_version": 1, "library": "io.mazewall:profiler:" + libraryVersion, "collector_version": manifest.CollectorVersion, "capture_abi": manifest.CaptureABI, "wire_schema": manifest.WireSchema, "asset_manifest_sha256": assetHash, "source_date_epoch": sourceDateEpoch, "modules": notices}); err != nil {
		return err
	}
	if err := writeDistributionChecksums(root); err != nil {
		return err
	}
	if err := checkDistributionChecksums(root); err != nil {
		return err
	}
	staged := filepath.Join(temp, "release.tar.gz")
	if err := archiveDistribution(root, staged); err != nil {
		return err
	}
	hash, err := fileHash(staged)
	if err != nil {
		return err
	}
	sidecar := filepath.Join(temp, "release.sha256")
	if err := os.WriteFile(sidecar, []byte(hash+"  "+filepath.Base(output)+"\n"), 0644); err != nil {
		return err
	}
	if err := unix.Renameat2(unix.AT_FDCWD, staged, unix.AT_FDCWD, output, unix.RENAME_NOREPLACE); err != nil {
		return err
	}
	return unix.Renameat2(unix.AT_FDCWD, sidecar, unix.AT_FDCWD, output+".sha256", unix.RENAME_NOREPLACE)
}
