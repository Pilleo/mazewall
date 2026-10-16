package candidate

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io.mazewall/experiments/hotspot-agentless/internal/driver"
	"io.mazewall/experiments/hotspot-agentless/internal/preflight"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

type candidateCase struct {
	label   string
	flags   []string
	virtual bool
}

func Run(root string, args []string) error {
	f := flag.NewFlagSet("candidates", flag.ContinueOnError)
	assets := f.String("assets", "", "")
	output := f.String("output", "", "")
	endpoint := f.String("docker-host", "", "")
	if err := f.Parse(args); err != nil {
		return err
	}
	if f.NArg() != 0 || *assets == "" || *output == "" || *endpoint == "" {
		return fmt.Errorf("candidates requires --assets, --output, --docker-host")
	}
	assetRoot, err := filepath.Abs(*assets)
	if err != nil {
		return err
	}
	out, err := filepath.Abs(*output)
	if err != nil {
		return err
	}
	if err := os.Mkdir(out, 0755); err != nil {
		return err
	}
	classes := filepath.Join(out, "classes")
	if err := os.Mkdir(classes, 0755); err != nil {
		return err
	}
	var lock struct{ Images map[string]string }
	if err := driver.ReadJSON(filepath.Join(root, "dependencies.lock.json"), &lock); err != nil {
		return err
	}
	var manifest map[string]any
	if err := driver.ReadJSON(filepath.Join(assetRoot, "manifest.json"), &manifest); err != nil {
		return err
	}
	digest, ok := manifest["gadget_digest"].(string)
	if !ok || !strings.HasPrefix(digest, "sha256:") || len(digest) != 71 {
		return fmt.Errorf("invalid candidate gadget digest")
	}
	docker := driver.NewDocker(root, *endpoint)
	source := filepath.Join(root, "fixtures/java")
	if err := compileCandidates(docker, source, out, classes, lock.Images["jdk"]); err != nil {
		return err
	}
	cases := []candidateCase{{"c1-serial", []string{"-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1"}, false}, {"c2-serial", []string{"-XX:+UseSerialGC", "-XX:-TieredCompilation"}, false}, {"xint-g1", []string{"-Xint", "-XX:+UseG1GC"}, false}}
	for _, n := range []int{1, 2, 4} {
		cases = append(cases, candidateCase{fmt.Sprintf("virtual-carriers-%d", n), []string{"-Xint", "-XX:+UseSerialGC", fmt.Sprintf("-Djdk.virtualThreadScheduler.parallelism=%d", n)}, true})
	}
	results := []map[string]any{}
	for _, c := range cases {
		result, err := runCase(docker, c, assetRoot, out, classes, lock.Images, "ghcr.io/inspektor-gadget/gadget/mw-hotspot-openat@"+digest)
		if err != nil {
			return fmt.Errorf("candidate artifacts %s: %w", out, err)
		}
		results = append(results, result)
	}
	return driver.WriteJSON(filepath.Join(out, "summary.json"), map[string]any{"jdk_image": lock.Images["jdk"], "go_image": lock.Images["go"], "asset_manifest": manifest, "experimental_schema": 1, "results": results, "production_qualified": false})
}

func compileCandidates(docker candidateDocker, source, out, classes, image string) (failure error) {
	owners := candidateOwners{docker: docker}
	defer func() { failure = owners.finish(out, nil, failure) }()
	id, err := owners.create("compiler", "--pull=never", "--user", strconv.Itoa(os.Getuid()), "--mount", "type=bind,src="+source+",dst="+source+",readonly", "--mount", "type=bind,src="+out+",dst="+out, image, "javac", "-d", classes, filepath.Join(source, "RuntimeCandidateFixture.java"), filepath.Join(source, "VirtualThreadOracle.java"))
	if err != nil {
		return err
	}
	_, err = docker.Output(60*time.Second, "start", "--attach", id)
	return err
}

func runCase(docker candidateDocker, c candidateCase, assets, out, classes string, images map[string]string, image string) (result map[string]any, failure error) {
	root := filepath.Join(out, c.label)
	if err := os.Mkdir(root, 0755); err != nil {
		return nil, err
	}
	var target, collector string
	var child, owner processOwner
	owners := candidateOwners{docker: docker}
	var logs []*os.File
	defer func() {
		failure = owners.finish(root, result, failure, logs...)
		if failure != nil {
			result = nil
		}
	}()
	targetLog, err := os.Create(filepath.Join(root, "target.log"))
	if err != nil {
		return nil, err
	}
	logs = append(logs, targetLog)
	argv := []string{"--pull=never", "--user", strconv.Itoa(os.Getuid()), "--pid=host", "--mount", "type=bind,src=" + out + ",dst=" + out, images["jdk"], "java"}
	argv = append(argv, c.flags...)
	argv = append(argv, "-XX:+PrintCompilation", "-cp", classes)
	if c.virtual {
		argv = append(argv, "VirtualThreadOracle", root)
	} else {
		argv = append(argv, "RuntimeCandidateFixture", root, "40")
	}
	target, err = owners.create("target", argv...)
	if err != nil {
		return nil, err
	}
	child, err = owners.start(targetLog, targetLog, target)
	if err != nil {
		return nil, err
	}
	if err := driver.WaitUntil(60*time.Second, "target readiness", func() (bool, error) {
		return readyOrExited(docker, target, func() (bool, error) { return driver.Exists(filepath.Join(root, "ready")) })
	}); err != nil {
		return nil, err
	}
	pidBytes, err := os.ReadFile(filepath.Join(root, "ready"))
	if err != nil {
		return nil, err
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(pidBytes)))
	if err != nil || pid <= 0 {
		return nil, fmt.Errorf("invalid fixture PID: %q", pidBytes)
	}
	stat, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid))
	if err != nil {
		return nil, err
	}
	ticks, err := preflight.ParseStartTicks(string(stat))
	if err != nil {
		return nil, err
	}
	if err := driver.WriteJSON(filepath.Join(root, "identity.json"), targetIdentity{PID: uint32(pid), StartTicks: ticks, FixtureRoot: root}); err != nil {
		return nil, err
	}
	flags, flagsErr := docker.Output(20*time.Second, "exec", target, "jcmd", strconv.Itoa(pid), "VM.flags", "-all")
	flagsCode := 0
	if flagsErr != nil {
		var exit *exec.ExitError
		if !errors.As(flagsErr, &exit) || errors.Is(flagsErr, context.DeadlineExceeded) {
			return nil, flagsErr
		}
		flagsCode = exit.ExitCode()
		flags += "\n" + flagsErr.Error()
	}
	if err := os.WriteFile(filepath.Join(root, "vm-flags.log"), []byte(flags), 0644); err != nil {
		return nil, err
	}
	stdout, err := os.Create(filepath.Join(root, "capture.jsonl"))
	if err != nil {
		return nil, err
	}
	logs = append(logs, stdout)
	stderr, err := os.Create(filepath.Join(root, "collector.log"))
	if err != nil {
		return nil, err
	}
	logs = append(logs, stderr)
	collector, err = owners.create("collector", "--pull=never", "--privileged", "--pid=host", "--mount", "type=bind,src="+assets+",dst="+assets+",readonly", "--mount", "type=bind,src=/sys,dst=/sys,readonly", images["go"], filepath.Join(assets, "mw-hotspot-collector"), "--capture", "--protocol-version=1", "--hotspot-identity=unknown", "--pid", strconv.Itoa(pid), "--store", filepath.Join(assets, "oci"), "--image", image, "--duration", "15s")
	if err != nil {
		return nil, err
	}
	owner, err = owners.start(stdout, stderr, collector)
	if err != nil {
		return nil, err
	}
	if err := driver.WaitUntil(10*time.Second, "native attachment", func() (bool, error) {
		return readyOrExited(docker, collector, func() (bool, error) { return Attached(filepath.Join(root, "capture.jsonl")) })
	}); err != nil {
		return nil, err
	}
	if err := os.WriteFile(filepath.Join(root, "measure"), []byte("measure"), 0644); err != nil {
		return nil, err
	}
	collectorErr := owner.wait(30 * time.Second)
	if err := os.WriteFile(filepath.Join(root, "release"), []byte("release"), 0644); err != nil {
		return nil, errors.Join(collectorErr, err)
	}
	targetErr := child.wait(10 * time.Second)
	if err := errors.Join(collectorErr, targetErr); err != nil {
		return nil, err
	}
	oracle := "manifest.json"
	if c.virtual {
		oracle = "oracle.jsonl"
	}
	verdict, err := VerifyFiles(c.virtual, filepath.Join(root, oracle), filepath.Join(root, "capture.jsonl"))
	if err != nil {
		var rejection *oracleRejection
		if !errors.As(err, &rejection) {
			return nil, err
		}
		verdict = map[string]any{"oracle_rejected": err.Error(), "production_qualified": false}
	}
	result = map[string]any{"label": c.label, "pid": pid, "flags": c.flags, "flags_observed_exit": flagsCode, "collector_exit": 0, "target_exit": 0, "verdict": verdict, "production_qualified": false}
	return result, nil
}

func readyOrExited(docker candidateDocker, id string, predicate func() (bool, error)) (bool, error) {
	ready, err := predicate()
	if err != nil || ready {
		return ready, err
	}
	status, err := docker.Output(5*time.Second, "inspect", "--format", "{{.State.Status}}", id)
	if err != nil {
		return false, err
	}
	if status = strings.TrimSpace(status); status == "exited" || status == "dead" {
		return false, fmt.Errorf("owned container %s exited before readiness", id)
	}
	return false, nil
}
