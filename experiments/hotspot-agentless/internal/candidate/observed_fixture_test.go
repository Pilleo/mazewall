//go:build mw_hotspot_qualification

package candidate

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"github.com/Pilleo/mazewall/hotspot-bridge/bundle"
)

type observedCompilerReceipt struct {
	next    int
	created []string
}

func (d *observedCompilerReceipt) Output(_ time.Duration, args ...string) (string, error) {
	switch args[0] {
	case "create":
		d.created = append([]string{}, args...)
		d.next++
		return fmt.Sprintf("%064x", d.next), nil
	case "start":
		return "javac fixture\n", nil
	case "ps":
		return "", nil
	default:
		return "", fmt.Errorf("unexpected compiler operation %v", args)
	}
}

func TestObservedTargetIsUnprivilegedWithWritableGates(t *testing.T) {
	root, err := observedRoot()
	if err != nil {
		t.Fatal(err)
	}
	output := t.TempDir()
	docker := &observedCompilerReceipt{}
	runObservedCase(context.Background(), docker, "ordinary", root, output, "", "", "", map[string]string{"jdk": "pinned-test-image"}, bundle.Verified{})
	user := ""
	for i, arg := range docker.created {
		if arg == "--user" && i+1 < len(docker.created) {
			user = docker.created[i+1]
		}
	}
	if user == "" || user == "0" || user == "0:0" {
		t.Errorf("target must satisfy privileged_target preflight; user=%q", user)
	}
	info, err := os.Stat(filepath.Join(output, "ordinary/gates"))
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm()&0002 == 0 {
		t.Error("unprivileged fixture cannot write its owned test gate mount")
	}
}
func (*observedCompilerReceipt) Start(*os.File, *os.File, ...string) (*exec.Cmd, error) {
	return nil, fmt.Errorf("not used by compiler")
}

func TestObservedFixtureMountPoint(t *testing.T) {
	root, err := observedRoot()
	if err != nil {
		t.Fatal(err)
	}
	output := t.TempDir()
	if err := compileObserved(&observedCompilerReceipt{}, root, output, "pinned-test-image"); err != nil {
		t.Fatal(err)
	}
	// A nested writable mount cannot create its destination in a read-only parent.
	info, err := os.Stat(filepath.Join(output, "fixture/gates"))
	if err != nil || !info.IsDir() {
		t.Fatalf("target's read-only fixture lacks nested gate mount point: %v", err)
	}
}
