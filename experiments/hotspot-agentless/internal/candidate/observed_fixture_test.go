//go:build mw_hotspot_qualification

package candidate

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"testing"
	"time"

	"github.com/Pilleo/mazewall/hotspot-bridge/bundle"
	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"golang.org/x/sys/unix"
	"io.mazewall/experiments/hotspot-agentless/internal/driver"
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

func TestObservedFixtureCompilesMaintainedExecEntry(t *testing.T) {
	root, err := observedRoot()
	if err != nil {
		t.Fatal(err)
	}
	output := t.TempDir()
	docker := &observedCompilerReceipt{}
	if err := compileObserved(docker, root, output, "test-image"); err != nil {
		t.Fatal(err)
	}
	if !slices.Contains(docker.created, "/fixture/ObservedExecWorkload.java") || !slices.Contains(docker.created, "/fixture/ObservedWorkload.java") {
		t.Fatal("actual compiler command omits a maintained fixture source", docker.created)
	}
	if _, err := os.Stat(filepath.Join(output, "fixture/ObservedExecWorkload.java")); err != nil {
		t.Fatal("exec source not staged in compiler mount", err)
	}
}

func TestObservedExecUsesJVMWorkloadInsteadOfShellControl(t *testing.T) {
	root, err := observedRoot()
	if err != nil {
		t.Fatal(err)
	}
	docker := &observedCompilerReceipt{}
	runObservedCase(context.Background(), docker, "target-exec", root, t.TempDir(), "", "", "", map[string]string{"jdk": "test-image"}, bundle.Verified{})
	if !slices.Contains(docker.created, "ObservedExecWorkload") {
		t.Fatal("exec control did not launch the actual JVM exec workload", docker.created)
	}
}

type observedPermissionReceipt struct {
	observedCompilerReceipt
	present   bool
	queries   int
	startErr  error
	removeErr error
}

func (d *observedPermissionReceipt) Output(timeout time.Duration, args ...string) (string, error) {
	switch args[0] {
	case "start":
		return "native denial fixture", d.startErr
	case "ps":
		d.queries++
		if d.present {
			return fmt.Sprintf("%064x", d.next), nil
		}
		return "", nil
	case "rm":
		if d.removeErr != nil {
			return "", d.removeErr
		}
		d.present = false
		return "", nil
	default:
		return d.observedCompilerReceipt.Output(timeout, args...)
	}
}

func TestObservedPermissionCollectorRetiresBeforeReturn(t *testing.T) {
	dir := t.TempDir()
	docker := &observedPermissionReceipt{present: true}
	err := observedPermissionControl(docker, driver.NewContainerOwners(docker), dir, dir, dir, "test-image", wire.Target{PID: 123})
	if err != nil {
		t.Fatal(err)
	}
	if docker.present || docker.queries < 2 {
		t.Fatal("permission control returned before confirming collector absence")
	}
}

func TestObservedPermissionCollectorFailureRetainsPendingOwners(t *testing.T) {
	dir := t.TempDir()
	docker := &observedPermissionReceipt{present: true, startErr: unix.EACCES, removeErr: unix.EPERM}
	err := observedPermissionControl(docker, driver.NewContainerOwners(docker), dir, dir, dir, "test-image", wire.Target{PID: 123})
	var pending interface{ HasPendingOwners() bool }
	if !errors.Is(err, unix.EACCES) || !errors.Is(err, unix.EPERM) || !errors.As(err, &pending) || !pending.HasPendingOwners() {
		t.Fatal("collector failure lost a cause or unresolved remote ownership", err)
	}
}

func TestObservedPermissionCollectorKeepsErrorAfterConfirmedRetirement(t *testing.T) {
	dir := t.TempDir()
	docker := &observedPermissionReceipt{present: true, startErr: unix.EACCES}
	err := observedPermissionControl(docker, driver.NewContainerOwners(docker), dir, dir, dir, "test-image", wire.Target{PID: 123})
	if !errors.Is(err, unix.EACCES) || docker.present || docker.queries < 2 {
		t.Fatal("original error or confirmed collector retirement was lost", err)
	}
	var pending interface{ HasPendingOwners() bool }
	if errors.As(err, &pending) && pending.HasPendingOwners() {
		t.Fatal("retired collector incorrectly retained as pending", err)
	}
}
