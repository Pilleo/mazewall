package benchmark

import (
	"errors"
	"flag"
	"fmt"
	"io.mazewall/experiments/hotspot-agentless/internal/candidate"
	"io.mazewall/experiments/hotspot-agentless/internal/driver"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"time"
)

type benchmarkOptions struct {
	Operations, Warmup, PostAttachWarmup, Repeats, Diversity, PauseNS, ExpectMissing int
	ExpectMissingSequence                                                            *int
	GadgetRef                                                                        string
	Modes                                                                            []string
	DrainEvents, DrainFull                                                           bool
}

func parseBenchmarkOptions(args []string) (benchmarkOptions, error) {
	var o benchmarkOptions
	f := flag.NewFlagSet("benchmark", flag.ContinueOnError)
	f.IntVar(&o.Operations, "operations", 1200, "")
	f.IntVar(&o.Warmup, "warmup", 600, "")
	f.IntVar(&o.PostAttachWarmup, "post-attach-warmup", 0, "")
	f.IntVar(&o.Repeats, "repeats", 3, "")
	f.IntVar(&o.Diversity, "diversity", 1, "")
	f.IntVar(&o.PauseNS, "pause-ns", 500000, "")
	f.StringVar(&o.GadgetRef, "gadget-ref", "build/gadget-image.ref", "")
	f.IntVar(&o.ExpectMissing, "expect-missing", 0, "")
	sequence := f.Int("expect-missing-sequence", 0, "")
	modes := f.String("modes", "baseline,events,full", "")
	f.BoolVar(&o.DrainEvents, "drain-events", false, "")
	f.BoolVar(&o.DrainFull, "drain-full", false, "")
	if err := f.Parse(args); err != nil {
		return o, err
	}
	if f.NArg() != 0 {
		return o, fmt.Errorf("unexpected benchmark arguments: %v", f.Args())
	}
	f.Visit(func(v *flag.Flag) {
		if v.Name == "expect-missing-sequence" {
			o.ExpectMissingSequence = sequence
		}
	})
	o.Modes = strings.Split(*modes, ",")
	if o.Operations < 1 || o.Operations > 100000 || o.Warmup < 0 || o.Warmup > 100000 || o.Repeats < 1 || o.Repeats > 10 || o.PauseNS < 0 || o.PauseNS > 10000000 || (o.Diversity != 1 && o.Diversity != 8) || o.ExpectMissing < 0 {
		return o, fmt.Errorf("invalid bounded benchmark configuration")
	}
	if int64(o.Operations)*int64(o.PauseNS) > 20000000000 {
		return o, fmt.Errorf("paced workload must fit the 20-second target bound")
	}
	if o.PostAttachWarmup < 0 || o.PostAttachWarmup > 10000 || (o.PostAttachWarmup > 0 && o.PostAttachWarmup < o.Diversity) {
		return o, fmt.Errorf("post-attachment preparation must cover all routes within 10000 operations")
	}
	if sequence := o.ExpectMissingSequence; sequence != nil && (o.ExpectMissing != 1 || *sequence < 0 || *sequence >= o.Operations) {
		return o, fmt.Errorf("exact missing sequence requires one expected missing operation within workload")
	}
	for _, mode := range o.Modes {
		if mode != "baseline" && mode != "events" && mode != "full" {
			return o, fmt.Errorf("invalid benchmark mode %q", mode)
		}
		if o.DrainFull && (o.DrainEvents || mode == "events") {
			return o, fmt.Errorf("strict full drain measurements support baseline/full and one owner")
		}
		if o.DrainEvents && mode == "full" {
			return o, fmt.Errorf("owned event drain measurements support baseline/events only")
		}
	}
	return o, nil
}

func validateReport(options benchmarkOptions, mode string, report RunReport) error {
	if mode != "baseline" && report.Unobserved != options.ExpectMissing {
		return fmt.Errorf("unexplained benchmark missing operations: %v", report.MissingSequences)
	}
	if mode != "baseline" && options.ExpectMissingSequence != nil && (len(report.MissingSequences) != 1 || report.MissingSequences[0] != *options.ExpectMissingSequence) {
		return fmt.Errorf("event-loss control did not observe exact missing token: %v", report.MissingSequences)
	}
	if mode == "full" && report.CorrectSegments == 0 {
		return fmt.Errorf("no useful full-mode enrichment")
	}
	return nil
}

func Run(root string, args []string) error {
	o, err := parseBenchmarkOptions(args)
	if err != nil {
		return err
	}
	var lock struct{ Images map[string]string }
	var patch struct {
		SHA string `json:"patch_sha256"`
	}
	if err := driver.ReadJSON(filepath.Join(root, "dependencies.lock.json"), &lock); err != nil {
		return err
	}
	if err := driver.ReadJSON(filepath.Join(root, "build/patch-manifest.json"), &patch); err != nil {
		return err
	}
	imagePath := o.GadgetRef
	if !filepath.IsAbs(imagePath) {
		imagePath = filepath.Join(root, imagePath)
	}
	image, err := os.ReadFile(imagePath)
	if err != nil {
		return err
	}
	directory, err := driver.NewDirectory(filepath.Join(root, "build"), "benchmark.")
	if err != nil {
		return err
	}
	docker := driver.NewDocker(root, "")
	reports := []RunReport{}
	for repeat := 0; repeat < o.Repeats; repeat++ {
		for offset := range o.Modes {
			mode := o.Modes[(offset+repeat)%len(o.Modes)]
			report, err := runOne(root, o, mode, repeat, directory, docker, lock.Images, patch.SHA, strings.TrimSpace(string(image)))
			if err != nil {
				return fmt.Errorf("benchmark artifacts %s: %w", directory, err)
			}
			reports = append(reports, report)
			fmt.Printf("%s %d ops/s %.2f p99_ns %d missing %d\n", mode, repeat, report.Throughput, report.Target.P99NS, report.Unobserved)
		}
	}
	summary := map[string]any{}
	for _, mode := range o.Modes {
		var throughput, latency []float64
		for _, report := range reports {
			if report.Manifest.Mode == mode {
				throughput = append(throughput, report.Throughput)
				latency = append(latency, float64(report.Target.P99NS))
			}
		}
		sort.Float64s(throughput)
		sort.Float64s(latency)
		summary[mode] = map[string]any{"runs": len(throughput), "throughput_median": median(throughput), "throughput_min": throughput[0], "throughput_max": throughput[len(throughput)-1], "p99_ns_median": median(latency)}
	}
	if err := driver.WriteJSON(filepath.Join(directory, "benchmark-report.json"), map[string]any{"runs": reports, "comparison_semantics": "independent_runs_same_runtime_workload; collector CPU window includes startup/readiness", "collection_loss_complete": false, "kernel_memory_measured": false, "summary": summary}); err != nil {
		return err
	}
	fmt.Println("Benchmark artifacts:", directory)
	return nil
}

func median(values []float64) float64 {
	middle := len(values) / 2
	if len(values)%2 == 0 {
		return (values[middle-1] + values[middle]) / 2
	}
	return values[middle]
}

func runOne(root string, o benchmarkOptions, mode string, repeat int, directory string, docker driver.Docker, images map[string]string, patch, image string) (report RunReport, failure error) {
	artifact := filepath.Join(directory, fmt.Sprintf("%s-%d", mode, repeat))
	if err := os.Mkdir(artifact, 0755); err != nil {
		return report, err
	}
	run, err := driver.Token(8)
	if err != nil {
		return report, err
	}
	output, err := os.Create(filepath.Join(artifact, "events.jsonl"))
	if err != nil {
		return report, err
	}
	defer func() { failure = errors.Join(failure, output.Close()) }()
	stderr, err := os.Create(filepath.Join(artifact, "collector.log"))
	if err != nil {
		return report, err
	}
	defer func() { failure = errors.Join(failure, stderr.Close()) }()
	var target, collector string
	var attached *exec.Cmd
	var sampler *resourceSampler
	defer func() {
		if sampler != nil {
			sampler.stop()
		}
		failure = errors.Join(failure, driver.RemoveOwned(docker, collector, target))
		if attached != nil {
			failure = errors.Join(failure, driver.WaitChild(attached, 5*time.Second))
		}
	}()
	started := time.Now()
	target, err = docker.Output(30*time.Second, "run", "-d", "--user", fmt.Sprintf("%d:%d", os.Getuid(), os.Getgid()), "--pid=host", "--mount", "type=bind,src="+root+",dst=/work,readonly", "--mount", "type=bind,src="+artifact+",dst=/fixture", images["jdk"], "java", "-Xint", "-XX:+UseSerialGC", "-Xmx128m", "-cp", "/work/build/benchmark-classes", "BenchmarkFixture", "/fixture", run, strconv.Itoa(o.Operations), strconv.Itoa(o.Warmup), strconv.Itoa(o.Diversity), strconv.Itoa(o.PauseNS), strconv.Itoa(o.PostAttachWarmup))
	if err != nil {
		target = ""
		return report, err
	}
	target = strings.TrimSpace(target)
	pid, err := inspectInt(docker, target, "{{.State.Pid}}")
	if err != nil {
		return report, err
	}
	if err := driver.WaitUntil(25*time.Second, "target readiness", func() (bool, error) {
		logs, err := docker.Output(5*time.Second, "logs", target)
		return strings.Contains(logs, "ready "), err
	}); err != nil {
		return report, err
	}
	collectorBinary := os.Getenv("MW_COLLECTOR_BINARY")
	if collectorBinary == "" {
		collectorBinary = "build/mw-hotspot-demo"
	}
	var cpid int
	if mode != "baseline" {
		store := os.Getenv("MW_GADGET_STORE")
		if store == "" {
			home, err := os.UserHomeDir()
			if err != nil {
				return report, err
			}
			store = filepath.Join(home, ".ig/oci-store")
		}
		args := []string{"create", "--privileged", "--pid=host", "--mount", "type=bind,src=/sys,dst=/sys,readonly", "--mount", "type=bind,src=" + root + ",dst=/work,readonly", "--mount", "type=bind,src=" + store + ",dst=/store,readonly", "--entrypoint", "/work/" + collectorBinary, images["go"], "--capture", "--pid", strconv.Itoa(pid), "--image", image, "--store", "/store", "--duration", "45s"}
		if mode == "events" {
			args = append(args, "--collection-mode", "events")
		} else {
			args = append(args, "--hotspot-identity", "safepoint-epoch")
		}
		if o.DrainEvents {
			args = append(args, "--drain-events")
		}
		if o.DrainFull {
			args = append(args, "--drain-full")
		}
		collector, err = docker.Output(30*time.Second, args...)
		if err != nil {
			collector = ""
			return report, err
		}
		collector = strings.TrimSpace(collector)
		attached, err = docker.Start(output, stderr, "start", "--attach", collector)
		if err != nil {
			return report, err
		}
		if err := driver.WaitUntil(10*time.Second, "collector process", func() (bool, error) {
			running, err := docker.Output(5*time.Second, "inspect", "--format", "{{.State.Running}}", collector)
			return strings.TrimSpace(running) == "true", err
		}); err != nil {
			return report, err
		}
		cpid, err = inspectInt(docker, collector, "{{.State.Pid}}")
		if err != nil {
			return report, err
		}
		sampler = startResourceSampler(cpid)
		if err := driver.WaitUntil(25*time.Second, "collector attachment", func() (bool, error) { return candidate.Attached(filepath.Join(artifact, "events.jsonl")) }); err != nil {
			return report, err
		}
	}
	readiness := time.Since(started).Nanoseconds()
	sourceSHA, err := driver.HashFile(filepath.Join(root, "fixtures/java/BenchmarkFixture.java"))
	if err != nil {
		return report, err
	}
	classSHA, err := driver.HashFile(filepath.Join(root, "build/benchmark-classes/BenchmarkFixture.class"))
	if err != nil {
		return report, err
	}
	kernel, err := driver.Output(root, 5*time.Second, "uname", "-r")
	if err != nil {
		return report, err
	}
	data := map[string]any{"mode": mode, "run": run, "host_pid": pid, "target_uid": os.Getuid(), "operations": o.Operations, "warmup": o.Warmup, "post_attach_warmup": o.PostAttachWarmup, "preparation_pause_ns": 500000, "diversity": o.Diversity, "pause_ns": o.PauseNS, "jvm_image": images["jdk"], "jvm_flags": []string{"-Xint", "-XX:+UseSerialGC", "-Xmx128m"}, "patch_sha256": patch, "gadget_image": image, "kernel": strings.TrimSpace(kernel), "readiness_ns": readiness, "readiness_semantics": "target_ready_and_collector_attached; not_enrichment_readiness", "target_agent_injected": false, "source_sha256": sourceSHA, "class_sha256": classSHA, "drain_events": o.DrainEvents && mode == "events", "drain_full": o.DrainFull && mode == "full"}
	if mode != "baseline" {
		hash, err := driver.HashFile(filepath.Join(root, collectorBinary))
		if err != nil {
			return report, err
		}
		data["collector_binary_sha256"] = hash
	}
	manifestPath := filepath.Join(artifact, "benchmark-manifest.json")
	if err := driver.WriteJSON(manifestPath, data); err != nil {
		return report, err
	}
	if o.PostAttachWarmup > 0 {
		preparation := time.Now()
		if err := os.WriteFile(filepath.Join(artifact, "prepare"), nil, 0644); err != nil {
			return report, err
		}
		if err := driver.WaitUntil(15*time.Second, "post-attachment preparation", func() (bool, error) { return driver.Exists(filepath.Join(artifact, "preparation-target.json")) }); err != nil {
			return report, err
		}
		if mode == "full" {
			if err := driver.WaitUntil(5*time.Second, "epoch-checked preparation routes", func() (bool, error) {
				var manifest RunManifest
				if err := driver.ReadJSON(manifestPath, &manifest); err != nil {
					return false, err
				}
				events, err := os.Open(filepath.Join(artifact, "events.jsonl"))
				if err != nil {
					return false, err
				}
				observation, observeErr := ObserveReadiness(manifest, events)
				if err := events.Close(); err != nil {
					return false, err
				}
				if observeErr != nil {
					if err := os.WriteFile(filepath.Join(artifact, "readiness-error.log"), []byte(observeErr.Error()), 0644); err != nil {
						return false, err
					}
					return false, nil
				}
				return observation.Ready, driver.WriteJSON(filepath.Join(artifact, "readiness-before-measurement.json"), observation)
			}); err != nil {
				return report, err
			}
		}
		data["post_attachment_preparation_ns"] = time.Since(preparation).Nanoseconds()
		if err := driver.WriteJSON(manifestPath, data); err != nil {
			return report, err
		}
	}
	if err := os.WriteFile(filepath.Join(artifact, "gate"), nil, 0644); err != nil {
		return report, err
	}
	if err := driver.WaitUntil(25*time.Second, "target measurement", func() (bool, error) { return driver.Exists(filepath.Join(artifact, "benchmark-target.json")) }); err != nil {
		return report, err
	}
	if sampler != nil {
		sampler.stop()
		clock, err := driver.Output(root, 5*time.Second, "getconf", "CLK_TCK")
		if err != nil {
			return report, err
		}
		ticks, err := strconv.ParseInt(strings.TrimSpace(clock), 10, 64)
		if err != nil || ticks <= 0 {
			return report, fmt.Errorf("invalid platform clock ticks: %q", clock)
		}
		resources, err := sampler.observation(ticks)
		if err != nil {
			return report, err
		}
		data["collector_resources"] = resources
		if err := driver.WriteJSON(filepath.Join(artifact, "resource-samples.json"), map[string]any{"host_pid": cpid, "clock_ticks_per_second": ticks, "samples": sampler.samples, "errors": sampler.failures}); err != nil {
			return report, err
		}
		stopping := time.Now()
		if _, err := docker.Output(10*time.Second, "kill", "--signal", "SIGINT", collector); err != nil {
			return report, err
		}
		child := attached
		attached = nil
		if err := driver.WaitChild(child, 10*time.Second); err != nil {
			return report, err
		}
		data["post_target_stop_ns"] = time.Since(stopping).Nanoseconds()
		data["post_target_stop_semantics"] = "docker_signal_request_to_collector_exit; excludes_target_workload"
		code, err := inspectInt(docker, collector, "{{.State.ExitCode}}")
		if err != nil {
			return report, err
		}
		if code != 0 {
			return report, fmt.Errorf("collector exited %d; see %s", code, filepath.Join(artifact, "collector.log"))
		}
	}
	if err := os.WriteFile(filepath.Join(artifact, "finish"), nil, 0644); err != nil {
		return report, err
	}
	code, err := docker.Output(10*time.Second, "wait", target)
	if err != nil {
		return report, err
	}
	if strings.TrimSpace(code) != "0" {
		return report, fmt.Errorf("target failed: %s", code)
	}
	if err := driver.WriteJSON(manifestPath, data); err != nil {
		return report, err
	}
	var manifest RunManifest
	if err := driver.ReadJSON(manifestPath, &manifest); err != nil {
		return report, err
	}
	targetData, err := os.Open(filepath.Join(artifact, "benchmark-target.json"))
	if err != nil {
		return report, err
	}
	defer func() { failure = errors.Join(failure, targetData.Close()) }()
	events, err := os.Open(filepath.Join(artifact, "events.jsonl"))
	if err != nil {
		return report, err
	}
	defer func() { failure = errors.Join(failure, events.Close()) }()
	report, err = Analyze(manifest, targetData, events)
	if err != nil {
		return report, err
	}
	if err := driver.WriteJSON(filepath.Join(artifact, "run-report.json"), report); err != nil {
		return report, err
	}
	return report, validateReport(o, mode, report)
}

func inspectInt(docker driver.Docker, id, format string) (int, error) {
	text, err := docker.Output(5*time.Second, "inspect", "--format", format, id)
	if err != nil {
		return 0, err
	}
	value, err := strconv.Atoi(strings.TrimSpace(text))
	if err != nil {
		return 0, fmt.Errorf("invalid inspect integer %q: %w", text, err)
	}
	return value, nil
}
