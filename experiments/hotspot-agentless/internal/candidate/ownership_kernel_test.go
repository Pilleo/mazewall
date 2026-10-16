//go:build mw_kernel

package candidate

import (
	"io.mazewall/experiments/hotspot-agentless/internal/driver"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestCandidateDockerOwnersAreActuallyRetired(t *testing.T) {
	endpoint := os.Getenv("MW_CANDIDATE_DOCKER_HOST")
	if endpoint == "" {
		t.Fatal("explicit MW_CANDIDATE_DOCKER_HOST required")
	}
	root, err := filepath.Abs("../..")
	if err != nil {
		t.Fatal(err)
	}
	var lock struct{ Images map[string]string }
	if err := driver.ReadJSON(filepath.Join(root, "dependencies.lock.json"), &lock); err != nil {
		t.Fatal(err)
	}
	docker := driver.NewDocker(root, endpoint)
	owners := candidateOwners{docker: docker}
	t.Cleanup(func() {
		if _, err := owners.retire(); err != nil {
			t.Error(err)
		}
	})
	out := t.TempDir()
	log, err := os.Create(filepath.Join(out, "wrappers.log"))
	if err != nil {
		t.Fatal(err)
	}
	defer log.Close()
	for _, role := range []string{"target", "helper"} {
		id, err := owners.create(role, "--pull=never", lock.Images["go"], "sh", "-c", "exit 0")
		if err != nil {
			t.Fatal(err)
		}
		process, err := owners.start(log, log, id)
		if err != nil {
			t.Fatal(err)
		}
		if err := process.wait(10 * time.Second); err != nil {
			t.Fatal(err)
		}
	}
	if err := owners.finish(out, map[string]any{"production_qualified": false}, nil); err != nil {
		t.Fatal(err)
	}
	for _, receipt := range owners.requests {
		text, err := docker.Output(5*time.Second, "ps", "-a", "--no-trunc", "--filter", "id="+receipt.ID, "--format", "{{.ID}}")
		if err != nil || strings.TrimSpace(text) != "" {
			t.Fatalf("actual remote owner remains: %s %q %v", receipt.ID, text, err)
		}
	}
}
