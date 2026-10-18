//go:build mw_hotspot_qualification

package candidate

import (
	"bufio"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/Pilleo/mazewall/hotspot-bridge/bundle"
	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"golang.org/x/sys/unix"
	"io.mazewall/experiments/hotspot-agentless/internal/capture"
	"io.mazewall/experiments/hotspot-agentless/internal/driver"
	"io.mazewall/experiments/hotspot-agentless/internal/identity"
	"io.mazewall/experiments/hotspot-agentless/internal/preflight"
)

type ObservedVerdict struct {
	Status, Profile, InputDigest, ReportPath string
}

type observedCaseResult struct {
	Name                   string
	Status                 string
	Error                  string
	Target                 wire.Target
	Raw, Terminals, Useful int
	Summary                *wire.Summary `json:",omitempty"`
	Capture                *wire.Capture `json:",omitempty"`
}

// RunObservedMatrix is qualification-only. A report cannot change runtime admission.
func RunObservedMatrix(ctx context.Context, assets, output string) (ObservedVerdict, error) {
	v := ObservedVerdict{Status: "rejected", Profile: string(wire.Observed25), ReportPath: filepath.Join(output, "report.json")}
	root, err := observedRoot()
	if err != nil {
		return v, err
	}
	if !filepath.IsAbs(assets) || !filepath.IsAbs(output) {
		return v, fmt.Errorf("absolute assets/output required")
	}
	if err := os.Mkdir(output, 0755); err != nil {
		return v, err
	}
	manifest, err := os.ReadFile(filepath.Join(assets, "manifest.json"))
	if err != nil {
		return v, err
	}
	manifestHash := sha256.Sum256(manifest)
	verified, err := bundle.Verify(assets, hex.EncodeToString(manifestHash[:]), wire.Observed25)
	if err != nil {
		return v, err
	}
	store, err := verified.Store()
	if err != nil {
		return v, err
	}
	image, err := verified.Image()
	if err != nil {
		return v, err
	}
	var lock struct{ Images map[string]string }
	if err := driver.ReadJSON(filepath.Join(root, "dependencies.lock.json"), &lock); err != nil {
		return v, err
	}
	executable, err := os.Executable()
	if err != nil {
		return v, err
	}
	input := sha256.New()
	inputs := map[string]string{}
	for _, path := range []string{filepath.Join(assets, "manifest.json"), filepath.Join(root, "dependencies.lock.json"), filepath.Join(root, "src/ObservedWorkload.java"), executable} {
		hash, err := driver.HashFile(path)
		if err != nil {
			return v, err
		}
		inputs[path] = hash
		fmt.Fprintf(input, "%s\x00%s\x00", path, hash)
	}
	v.InputDigest = hex.EncodeToString(input.Sum(nil))
	docker := driver.NewDocker(root, os.Getenv("MAZEWALL_DOCKER_HOST"))
	if err := driver.WriteJSON(filepath.Join(output, "inputs.json"), map[string]any{"inputs": inputs, "images": lock.Images, "manifest_sha256": hex.EncodeToString(manifestHash[:]), "profile": v.Profile, "admission": false}); err != nil {
		return v, err
	}
	if err := observedProcReceipt(output, "controller", os.Getpid()); err != nil {
		return v, err
	}
	for _, name := range []string{"pid", "cgroup"} {
		self, err := os.Readlink("/proc/self/ns/" + name)
		if err != nil {
			return v, err
		}
		host, err := os.Readlink("/proc/1/ns/" + name)
		if err != nil {
			return v, err
		}
		if self != host {
			return v, fmt.Errorf("qualification requires actual host %s namespace: self=%s host=%s", name, self, host)
		}
	}
	if err := compileObserved(docker, root, output, lock.Images["jdk"]); err != nil {
		return v, err
	}
	results := []observedCaseResult{}
	for _, name := range []string{"ordinary", "compiled-churn", "class-unload", "target-exit", "permission-denial"} {
		result := runObservedCase(ctx, docker, name, root, output, assets, store, image, lock.Images, verified)
		results = append(results, result)
	}
	execResult := observedExecControl(ctx, output)
	results = append(results, execResult)
	qualified := true
	for _, result := range results {
		if result.Status != "qualified" && result.Status != "safe-control" {
			qualified = false
		}
	}
	if qualified {
		v.Status = "qualified"
	}
	return v, driver.WriteJSON(v.ReportPath, map[string]any{"verdict": v, "cases": results, "production_admission": false, "policy_eligibility": false, "scope": "controlled observational routes only", "unqualified": []string{"global collection", "GC/code/metadata reclamation ownership", "virtual threads", "logical TID lifetime", "policy completeness"}})
}

func observedRoot() (string, error) {
	path, err := os.Getwd()
	if err != nil {
		return "", err
	}
	for {
		if _, err := os.Stat(filepath.Join(path, "dependencies.lock.json")); err == nil {
			return path, nil
		}
		parent := filepath.Dir(path)
		if parent == path {
			return "", fmt.Errorf("native owner root not found")
		}
		path = parent
	}
}

func compileObserved(docker candidateDocker, root, output, image string) (failure error) {
	fixture := filepath.Join(output, "fixture")
	if err := os.Mkdir(fixture, 0755); err != nil {
		return err
	}
	if err := os.Mkdir(filepath.Join(fixture, "classes"), 0755); err != nil {
		return err
	}
	if err := os.Mkdir(filepath.Join(fixture, "gates"), 0755); err != nil {
		return err
	}
	source, err := os.ReadFile(filepath.Join(root, "src/ObservedWorkload.java"))
	if err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(fixture, "ObservedWorkload.java"), source, 0644); err != nil {
		return err
	}
	owners := driver.NewContainerOwners(docker)
	defer func() { failure = owners.Finish(fixture, failure) }()
	versionID, err := owners.Create("observed-javac-version", "--pull=never", "--network=none", image, "javac", "-version")
	if err != nil {
		return err
	}
	version, err := docker.Output(10*time.Second, "start", "--attach", versionID)
	if err != nil {
		return err
	}
	if err := os.WriteFile(filepath.Join(fixture, "javac-version-before-compile.txt"), []byte(version), 0644); err != nil {
		return err
	}
	args := []string{"--pull=never", "--network=none", "--mount", "type=bind,src=" + fixture + ",dst=/fixture", image, "javac", "-d", "/fixture/classes", "/fixture/ObservedWorkload.java"}
	if err := driver.WriteJSON(filepath.Join(fixture, "compiler-inputs.json"), map[string]any{"image": image, "argv": args, "source_sha256": fmt.Sprintf("%x", sha256.Sum256(source))}); err != nil {
		return err
	}
	id, err := owners.Create("observed-javac", args...)
	if err != nil {
		return err
	}
	text, err := docker.Output(30*time.Second, "start", "--attach", id)
	if writeErr := os.WriteFile(filepath.Join(fixture, "javac.log"), []byte(text), 0644); err != nil || writeErr != nil {
		return errors.Join(err, writeErr)
	}
	hashes := map[string]string{}
	err = filepath.WalkDir(filepath.Join(fixture, "classes"), func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return err
		}
		hashes[path], err = driver.HashFile(path)
		return err
	})
	return errors.Join(err, driver.WriteJSON(filepath.Join(fixture, "classes-before-run.json"), hashes))
}

func observedProcReceipt(output, role string, pid int) error {
	prefix := fmt.Sprintf("/proc/%d/", pid)
	files := map[string]string{}
	for _, name := range []string{"stat", "status", "cgroup", "maps", "mountinfo", "cmdline"} {
		data, err := os.ReadFile(prefix + name)
		if err != nil {
			return err
		}
		files[name] = string(data)
	}
	for _, name := range []string{"mnt", "pid", "cgroup", "user"} {
		value, err := os.Readlink(prefix + "ns/" + name)
		if err != nil {
			return err
		}
		files["ns/"+name] = value
		host, err := os.Readlink("/proc/1/ns/" + name)
		if err != nil {
			return err
		}
		files["host/ns/"+name] = host
	}
	return driver.WriteJSON(filepath.Join(output, role+"-proc.json"), files)
}

func observedTarget(pid int, container, image string) (wire.Target, error) {
	t := wire.Target{PID: uint32(pid), ContainerID: container, ImageID: image}
	stat, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid))
	if err != nil {
		return t, err
	}
	t.StartTicks, err = preflight.ParseStartTicks(string(stat))
	if err != nil {
		return t, err
	}
	data, err := os.ReadFile(fmt.Sprintf("/proc/%d/cgroup", pid))
	if err != nil {
		return t, err
	}
	var cgPath string
	for _, line := range strings.Split(string(data), "\n") {
		if path, ok := strings.CutPrefix(line, "0::"); ok {
			if cgPath != "" || !filepath.IsAbs(path) || filepath.Clean(path) != path || strings.Contains(path, "/../") {
				return t, fmt.Errorf("non-visible/escaping cgroup path %q", path)
			}
			cgPath = path
		}
	}
	if cgPath == "" {
		return t, fmt.Errorf("no host-visible unified cgroup")
	}
	var cg, ns unix.Stat_t
	if err := unix.Stat(filepath.Join("/sys/fs/cgroup", cgPath), &cg); err != nil {
		return t, err
	}
	if err := unix.Stat(fmt.Sprintf("/proc/%d/ns/mnt", pid), &ns); err != nil {
		return t, err
	}
	t.CgroupID, t.MountNS = cg.Ino, ns.Ino
	return t, identity.CheckObservedTarget(t.PID, t)
}

func readObservedOracle(gates string) (map[string]observedOracle, error) {
	oracle := map[string]observedOracle{}
	workers := map[uint32]bool{}
	for worker := 0; worker < 4; worker++ {
		data, err := os.ReadFile(filepath.Join(gates, fmt.Sprintf("oracle-%d.tsv", worker)))
		if err != nil {
			return nil, err
		}
		var workerTID uint32
		for _, line := range strings.Split(strings.TrimSpace(string(data)), "\n") {
			fields := strings.Split(line, "\t")
			if len(fields) != 3 {
				return nil, fmt.Errorf("invalid source oracle")
			}
			tid, err := strconv.ParseUint(fields[2], 10, 32)
			if err != nil || tid == 0 {
				return nil, fmt.Errorf("invalid source TID")
			}
			if _, ok := oracle[fields[0]]; ok {
				return nil, fmt.Errorf("duplicate source token")
			}
			if workerTID != 0 && workerTID != uint32(tid) {
				return nil, fmt.Errorf("worker changed TID")
			}
			workerTID = uint32(tid)
			oracle[fields[0]] = observedOracle{Method: fields[1], TID: workerTID}
		}
		if workers[workerTID] {
			return nil, fmt.Errorf("duplicate source worker")
		}
		workers[workerTID] = true
	}
	if len(oracle) != 400 {
		return nil, fmt.Errorf("source oracle count %d", len(oracle))
	}
	return oracle, nil
}

type observedDecoded struct {
	decoder        *wire.Decoder
	err            error
	raw, terminals int
	summary        *wire.Summary
}

func runObservedCase(parent context.Context, docker candidateDocker, name, root, output, assets, store, image string, images map[string]string, verified bundle.Verified) (result observedCaseResult) {
	result = observedCaseResult{Name: name, Status: "rejected"}
	dir := filepath.Join(output, name)
	if err := os.Mkdir(dir, 0755); err != nil {
		result.Error = err.Error()
		return
	}
	gates := filepath.Join(dir, "gates")
	if err := os.Mkdir(gates, 0777); err != nil {
		result.Error = err.Error()
		return
	}
	if err := os.Chmod(gates, 0777); err != nil {
		result.Error = err.Error()
		return
	}
	owners := driver.NewContainerOwners(docker)
	var logs []*os.File
	var target *driver.OwnedCommand
	defer func() {
		err := os.WriteFile(filepath.Join(gates, "release"), []byte("release"), 0644)
		if target != nil {
			err = errors.Join(err, target.Wait(5*time.Second))
		}
		err = owners.Finish(dir, err, logs...)
		if err != nil {
			result.Status = "rejected"
			result.Error = errors.Join(errors.New(result.Error), err).Error()
		}
		if err := driver.WriteJSON(filepath.Join(dir, "result.json"), result); err != nil {
			result.Status = "rejected"
			result.Error += err.Error()
		}
	}()
	fail := func(err error) { result.Error = err.Error() }
	log, err := os.Create(filepath.Join(dir, "target.log"))
	if err != nil {
		fail(err)
		return
	}
	logs = append(logs, log)
	flags := []string{}
	if name == "compiled-churn" {
		flags = []string{"-XX:+PrintCompilation", "-Xlog:codecache*=debug"}
		err = os.WriteFile(filepath.Join(gates, "churn"), nil, 0644)
	}
	if name == "class-unload" {
		flags = []string{"-Xlog:class+unload=info"}
		err = os.WriteFile(filepath.Join(gates, "unload"), nil, 0644)
	}
	if err != nil {
		fail(err)
		return
	}
	args := []string{"--pull=never", "--network=none", "--user", "65534:65534", "--pid=host", "--cgroupns=host", "--mount", "type=bind,src=" + filepath.Join(output, "fixture") + ",dst=/fixture,readonly", "--mount", "type=bind,src=" + gates + ",dst=/fixture/gates", images["jdk"], "java"}
	args = append(args, flags...)
	args = append(args, "-cp", "/fixture/classes", "ObservedWorkload", "/fixture/gates", "400", "4", "20")
	if err := driver.WriteJSON(filepath.Join(dir, "target-inputs.json"), map[string]any{"argv": args, "jdk": images["jdk"], "warmups": 40, "marked": 400, "workers": 4, "pace_ms": 20}); err != nil {
		fail(err)
		return
	}
	id, err := owners.Create("observed-"+name, args...)
	if err != nil {
		fail(err)
		return
	}
	target, err = owners.Start(log, log, id)
	if err != nil {
		fail(err)
		return
	}
	if err := driver.WaitUntil(10*time.Second, "plain target ready", func() (bool, error) {
		return readyOrExited(docker, id, func() (bool, error) { return driver.Exists(filepath.Join(gates, "ready")) })
	}); err != nil {
		fail(err)
		return
	}
	pidData, err := os.ReadFile(filepath.Join(gates, "ready"))
	if err != nil {
		fail(err)
		return
	}
	pid, err := strconv.Atoi(string(pidData))
	if err != nil || pid <= 0 {
		fail(fmt.Errorf("invalid target PID %q", pidData))
		return
	}
	inspect, err := docker.Output(5*time.Second, "inspect", "--format", "{{.Id}} {{.State.Pid}} {{.Image}} {{json .HostConfig.PidMode}} {{json .HostConfig.CgroupnsMode}} {{json .Mounts}}", id)
	if err != nil {
		fail(err)
		return
	}
	if err := os.WriteFile(filepath.Join(dir, "container-association.txt"), []byte(inspect), 0644); err != nil {
		fail(err)
		return
	}
	fields := strings.Fields(inspect)
	if len(fields) < 3 || fields[0] != id || fields[1] != strconv.Itoa(pid) || fields[2] != strings.TrimPrefix(images["jdk"], "eclipse-temurin@") {
		fail(fmt.Errorf("actual container/PID/image association mismatch: %s", inspect))
		return
	}
	if err := observedProcReceipt(dir, "target", pid); err != nil {
		fail(err)
		return
	}
	result.Target, err = observedTarget(pid, id, images["jdk"])
	if err != nil {
		fail(err)
		return
	}
	oracle, err := readObservedOracle(gates)
	if err != nil {
		fail(err)
		return
	}
	for _, entry := range oracle {
		if _, err := os.Stat(fmt.Sprintf("/proc/%d/task/%d", pid, entry.TID)); err != nil {
			fail(err)
			return
		}
	}
	if err := driver.WriteJSON(filepath.Join(dir, "identity-and-oracle.json"), map[string]any{"target": result.Target, "oracle": oracle}); err != nil {
		fail(err)
		return
	}
	flagsText, err := docker.Output(10*time.Second, "exec", id, "jcmd", strconv.Itoa(pid), "VM.flags", "-all")
	if writeErr := os.WriteFile(filepath.Join(dir, "vm-flags.txt"), []byte(flagsText), 0644); err != nil || writeErr != nil {
		fail(errors.Join(err, writeErr))
		return
	}
	if name == "permission-denial" {
		err = observedPermissionControl(docker, owners, dir, root, assets, images["go"], result.Target)
		if err != nil {
			fail(err)
			return
		}
		result.Status = "safe-control"
		return
	}
	expected, err := verified.Expected(result.Target)
	if err != nil {
		fail(err)
		return
	}
	raw, err := os.Create(filepath.Join(dir, "capture.jsonl"))
	if err != nil {
		fail(err)
		return
	}
	logs = append(logs, raw)
	read, write := io.Pipe()
	control, send, err := os.Pipe()
	if err != nil {
		fail(err)
		return
	}
	defer control.Close()
	defer send.Close()
	ctx, cancel := context.WithTimeout(parent, 12*time.Second)
	defer cancel()
	ready := make(chan struct{}, 1)
	decoded := make(chan observedDecoded, 1)
	go func() {
		defer read.Close()
		d := wire.NewDecoder(expected)
		var decodeErr error
		marked, terminals := map[string]bool{}, map[string]bool{}
		var summary *wire.Summary
		sent := false
		s := bufio.NewScanner(read)
		s.Buffer(make([]byte, 4096), 1048577)
		for s.Scan() {
			var row wire.Record
			if err := json.Unmarshal(s.Bytes(), &row); err == nil {
				if row.Event != nil && strings.HasPrefix(row.Event.Filename, "/fixture/gates/marked-") {
					marked[row.EventID] = true
				}
				if row.Evidence != nil {
					terminals[row.EventID] = true
				}
				if row.Summary != nil {
					summary = row.Summary
				}
			}
			if decodeErr == nil {
				decodeErr = d.Accept(s.Bytes())
			}
			if _, ok := d.Ready(); ok && !sent {
				ready <- struct{}{}
				sent = true
			}
		}
		terminalCount := 0
		for id := range marked {
			if terminals[id] {
				terminalCount++
			}
		}
		decoded <- observedDecoded{decoder: d, err: errors.Join(decodeErr, s.Err()), raw: len(marked), terminals: terminalCount, summary: summary}
	}()
	runDone := make(chan error, 1)
	go func() {
		err := capture.RunObservedCandidate(ctx, capture.Config{PID: pid, ExpectedTarget: result.Target, DrainFull: true, Image: image, Store: store}, io.MultiWriter(raw, write), control)
		write.Close()
		runDone <- err
	}()
	var d observedDecoded
	started := false
	select {
	case <-ready:
		started = true
	case d = <-decoded:
	case <-ctx.Done():
		cancel()
		d = <-decoded
	case <-time.After(10 * time.Second):
		cancel()
		d = <-decoded
	}
	var workErr error
	if started {
		workErr = os.WriteFile(filepath.Join(gates, "measure"), []byte("measure"), 0644)
		if workErr == nil {
			workErr = driver.WaitUntil(5*time.Second, "400 marked calls", func() (bool, error) { return driver.Exists(filepath.Join(gates, "done")) })
		}
		if name == "target-exit" {
			_, workErr = docker.Output(5*time.Second, "kill", "--signal=KILL", id)
		}
		if workErr != nil {
			cancel()
		} else if name != "target-exit" {
			_, workErr = send.Write([]byte("stop\n"))
		}
		d = <-decoded
	}
	runErr := <-runDone
	result.Raw, result.Terminals, result.Summary = d.raw, d.terminals, d.summary
	captured, decodeErr := d.decoder.Finish(runErr)
	if err := errors.Join(workErr, d.err, decodeErr, runErr); err != nil {
		fail(err)
		if name == "target-exit" && started && runErr != nil {
			result.Error = "target loss correctly rejected: " + err.Error()
			result.Status = "safe-control"
		}
		return
	}
	result.Capture = &captured
	routes, useful, err := observedRoutes(captured.Pairs, oracle)
	result.Useful = useful
	if err != nil {
		fail(err)
		return
	}
	want := map[string]string{}
	for token, entry := range oracle {
		want[token] = entry.Method
	}
	if err := verifyObservedMarked(routes, want, result.Raw, result.Terminals); err != nil {
		fail(err)
		return
	}
	if name == "class-unload" {
		data, err := os.ReadFile(filepath.Join(dir, "target.log"))
		if err != nil {
			fail(err)
			return
		}
		if !strings.Contains(string(data), "unloading class ObservedWorkload$Payload") {
			fail(fmt.Errorf("class unloading not observed; unavailable, not qualification"))
			return
		}
	}
	if name == "target-exit" {
		fail(fmt.Errorf("target exit accepted as successful capture"))
		return
	}
	result.Status = "qualified"
	return
}

func observedExecControl(ctx context.Context, output string) (result observedCaseResult) {
	result = observedCaseResult{Name: "target-exec", Status: "rejected"}
	cmd := exec.CommandContext(ctx, "/bin/sh", "-c", "read token; exec /bin/true")
	in, err := cmd.StdinPipe()
	if err != nil {
		result.Error = err.Error()
		return
	}
	defer in.Close()
	if err := cmd.Start(); err != nil {
		result.Error = err.Error()
		return
	}
	defer cmd.Process.Kill()
	barrier, err := identity.NewExecBarrier(cmd.Process.Pid)
	if err != nil {
		in.Close()
		cmd.Wait()
		result.Error = err.Error()
		return
	}
	defer func() {
		if err := barrier.Close(); err != nil {
			result.Status = "rejected"
			result.Error += err.Error()
		}
	}()
	if err := barrier.Check(); err != nil {
		in.Close()
		cmd.Wait()
		result.Error = err.Error()
		return
	}
	if err := driver.WriteJSON(filepath.Join(output, "exec-before.json"), map[string]any{"pid": cmd.Process.Pid, "argv": cmd.Args, "barrier_initial": "clear"}); err != nil {
		in.Close()
		cmd.Wait()
		result.Error = err.Error()
		return
	}
	_, writeErr := in.Write([]byte("exec\n"))
	err = errors.Join(writeErr, cmd.Wait())
	if err != nil {
		result.Error = err.Error()
		return
	}
	err = barrier.Check()
	if !errors.Is(err, identity.ErrTargetExec) {
		result.Error = fmt.Sprintf("exec not rejected: %v", err)
		return
	}
	// This shell control proves the real poison path, not JVM exec during full readout.
	result.Error = "actual same-PID exec barrier rejected; JVM/full-readout exec coupling unavailable"
	return
}

func observedPermissionControl(docker candidateDocker, owners *driver.ContainerOwners, dir, root, assets, image string, target wire.Target) error {
	binary, err := os.Executable()
	if err != nil {
		return err
	}
	if err := driver.WriteJSON(filepath.Join(dir, "denial-input.json"), target); err != nil {
		return err
	}
	id, err := owners.Create("observed-denial", "--pull=never", "--network=none", "--pid=host", "--cgroupns=host", "--cap-drop=ALL", "--security-opt=seccomp=unconfined", "--mount", "type=bind,src="+root+",dst="+root+",readonly", "--mount", "type=bind,src="+filepath.Dir(filepath.Dir(binary))+",dst="+filepath.Dir(filepath.Dir(binary))+",readonly", "--mount", "type=bind,src="+assets+",dst="+assets+",readonly", "--mount", "type=bind,src="+dir+",dst="+dir, "--mount", "type=bind,src=/sys,dst=/sys,readonly", "-e", "MW_OBSERVED_DENIAL="+dir, "-e", "MW_HOTSPOT_ASSETS="+assets, "-w", root, image, binary, "-test.run=^TestObservedPermissionNative$", "-test.v", "-test.count=1", "-test.timeout=20s")
	if err != nil {
		return err
	}
	text, err := docker.Output(25*time.Second, "start", "--attach", id)
	return errors.Join(err, os.WriteFile(filepath.Join(dir, "denial.log"), []byte(text), 0644))
}
