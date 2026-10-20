//go:build mw_kernel

package benchmark

import (
	"errors"
	"io.mazewall/experiments/hotspot-agentless/internal/driver"
	"os"
	"path/filepath"
	"testing"
	"time"
)

type removalFailureDocker struct{ driver.Docker }

func (d removalFailureDocker) Output(timeout time.Duration, args ...string) (string, error) {
	text, err := d.Docker.Output(timeout, args...)
	if err == nil && args[0] == "rm" {
		return text, os.ErrPermission
	}
	return text, err
}

func TestBenchmarkRetirementPrecedesActualResultPublication(t *testing.T) {
	endpoint := os.Getenv("DOCKER_HOST")
	if endpoint == "" {
		t.Fatal("explicit DOCKER_HOST required")
	}
	root, err := filepath.Abs("../..")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(root, "build/benchmark-classes/BenchmarkFixture.class")); err != nil {
		t.Fatal("run make benchmark-fixture before the explicit native test", err)
	}
	var lock struct{ Images map[string]string }
	if err := driver.ReadJSON(filepath.Join(root, "dependencies.lock.json"), &lock); err != nil {
		t.Fatal(err)
	}
	var patch struct {
		SHA string `json:"patch_sha256"`
	}
	if err := driver.ReadJSON(filepath.Join(root, "build/patch-manifest.json"), &patch); err != nil {
		t.Fatal(err)
	}
	for _, denied := range []bool{false, true} {
		directory, err := driver.NewDirectory(filepath.Join(root, "build"), "benchmark-owner-test.")
		if err != nil {
			t.Fatal(err)
		}
		docker := driver.NewDocker(root, endpoint)
		var transport benchmarkDocker = docker
		if denied {
			transport = removalFailureDocker{docker}
		}
		report, failure := runOne(root, benchmarkOptions{Operations: 8, Warmup: 8, Diversity: 1}, "baseline", 0, directory, transport, lock.Images, patch.SHA, "unused")
		artifact := filepath.Join(directory, "baseline-0")
		var cleanup struct {
			Retired    bool                  `json:"resources_retired"`
			Containers []struct{ ID string } `json:"containers"`
			Pending    []string              `json:"pending_containers"`
			Failures   []string              `json:"failures"`
		}
		if err := driver.ReadJSON(filepath.Join(artifact, "cleanup.json"), &cleanup); err != nil {
			t.Fatal(err)
		}
		if !cleanup.Retired || len(cleanup.Pending) != 0 || len(cleanup.Containers) != 1 {
			t.Fatalf("actual retirement incomplete: %+v %v", cleanup, failure)
		}
		for _, container := range cleanup.Containers {
			text, err := docker.Output(5*time.Second, "ps", "-a", "--no-trunc", "--filter", "id="+container.ID, "--format", "{{.ID}}")
			if err != nil || text != "" {
				t.Fatalf("actual container remains: %s %q %v", container.ID, text, err)
			}
		}
		_, resultErr := os.Stat(filepath.Join(artifact, "run-report.json"))
		if denied {
			if !errors.Is(failure, os.ErrPermission) || len(cleanup.Failures) == 0 || !os.IsNotExist(resultErr) || report.Target.Successful != 0 {
				t.Fatalf("cleanup failure published a result: %+v %v %v", cleanup, failure, resultErr)
			}
		} else if failure != nil || resultErr != nil || report.Target.Successful != 8 {
			t.Fatalf("actual positive benchmark failed: %+v %v %v", report, failure, resultErr)
		}
		t.Logf("injected_remove_failure=%t resources_retired=%t artifacts=%s", denied, cleanup.Retired, artifact)
	}
}
