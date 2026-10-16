//go:build mw_kernel

package driver

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestContainerOwnersAreActuallyRetired(t *testing.T) {
	endpoint := os.Getenv("MW_CANDIDATE_DOCKER_HOST")
	if endpoint == "" {
		t.Fatal("explicit MW_CANDIDATE_DOCKER_HOST required")
	}
	root, err := filepath.Abs("../..")
	if err != nil {
		t.Fatal(err)
	}
	var lock struct{ Images map[string]string }
	if err := ReadJSON(filepath.Join(root, "dependencies.lock.json"), &lock); err != nil {
		t.Fatal(err)
	}
	docker := NewDocker(root, endpoint)
	owners := NewContainerOwners(docker)
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
		id, err := owners.Create(role, "--pull=never", lock.Images["go"], "sh", "-c", "exit 0")
		if err != nil {
			t.Fatal(err)
		}
		process, err := owners.Start(log, log, id)
		if err != nil {
			t.Fatal(err)
		}
		if err := process.Wait(10 * time.Second); err != nil {
			t.Fatal(err)
		}
	}
	if err := owners.Finish(out, nil); err != nil {
		t.Fatal(err)
	}
	for _, receipt := range owners.requests {
		text, err := docker.Output(5*time.Second, "ps", "-a", "--no-trunc", "--filter", "id="+receipt.ID, "--format", "{{.ID}}")
		if err != nil || strings.TrimSpace(text) != "" {
			t.Fatalf("actual remote owner remains: %s %q %v", receipt.ID, text, err)
		}
	}
}
