//go:build mw_hotspot_qualification

package candidate

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"golang.org/x/sys/unix"
)

func TestObservedControlRequiresTypedChangeAndFailedCapture(t *testing.T) {
	cmd := exec.Command("/bin/sh", "-c", "exit 1")
	err := cmd.Run()
	if err == nil {
		t.Fatal("expected actual process failure")
	}
	exit := observedProcessExit{PID: cmd.Process.Pid, Reaped: true, ExitCode: 1, cause: err}
	failure := observedCollectorFailure{Error: "original cause", TargetExec: true}
	if err := verifyObservedControl("target-exec", true, exit, failure, nil, wire.ErrProcess); err != nil {
		t.Fatal("actual failed process with typed exec cause rejected", err)
	}
	for _, name := range []string{"not ready", "not reaped", "successful process", "missing native cause", "message only", "summary", "accepted capture"} {
		t.Run(name, func(t *testing.T) {
			ready, actual, native, summary, rejected := true, exit, failure, (*wire.Summary)(nil), error(wire.ErrProcess)
			switch name {
			case "not ready":
				ready = false
			case "not reaped":
				actual.Reaped = false
			case "successful process":
				actual.ExitCode, actual.cause = 0, nil
			case "missing native cause":
				native = observedCollectorFailure{}
			case "message only":
				native = observedCollectorFailure{Error: "strict HotSpot target executed a new image"}
			case "summary":
				summary = &wire.Summary{}
			case "accepted capture":
				rejected = nil
			}
			if err := verifyObservedControl("target-exec", ready, actual, native, summary, rejected); err == nil {
				t.Fatal("unsafe control accepted")
			}
		})
	}
	if err := verifyObservedControl("target-exit", true, exit, failure, nil, wire.ErrProcess); err == nil {
		t.Fatal("exec cause substituted for target exit")
	}
	failure.TargetExec, failure.TargetExit = false, true
	if err := verifyObservedControl("target-exit", true, exit, failure, nil, wire.ErrProcess); err != nil {
		t.Fatal("original typed target exit rejected", err)
	}
}

func TestExpectedTargetKillDoesNotHideOtherCleanupFailures(t *testing.T) {
	cmd := exec.Command("/bin/sh", "-c", "exit 137")
	killed := cmd.Run()
	if killed == nil {
		t.Fatal("expected actual exit137")
	}
	if !onlyExpectedTargetKill(errors.Join(killed, fmt.Errorf("wrapper: %w", killed))) {
		t.Fatal("original intentional target termination not classified")
	}
	other := exec.Command("/bin/sh", "-c", "exit 1").Run()
	for _, unexpected := range []error{nil, unix.EPERM, unix.EACCES, context.DeadlineExceeded, other, errors.Join(killed, unix.EACCES), errors.New(killed.Error())} {
		if onlyExpectedTargetKill(unexpected) {
			t.Fatal("unknown or mixed cleanup failure classified as intentional exit137", unexpected)
		}
	}
}

func TestObservedExecTransitionKeepsBoundIdentity(t *testing.T) {
	before := wire.Target{PID: 123, StartTicks: 456, CgroupID: 789, MountNS: 42, ContainerID: "container", ImageID: "image"}
	if err := validateObservedExecTransition(before, before, "/usr/bin/sleep", "/usr/bin/sleep"); err != nil {
		t.Fatal("same-generation executable replacement rejected", err)
	}
	for _, field := range []string{"pid", "birth", "cgroup", "mount", "container", "image", "wrong executable", "same basename"} {
		t.Run(field, func(t *testing.T) {
			after, executable := before, "/usr/bin/sleep"
			switch field {
			case "pid":
				after.PID++
			case "birth":
				after.StartTicks++
			case "cgroup":
				after.CgroupID++
			case "mount":
				after.MountNS++
			case "container":
				after.ContainerID += "other"
			case "image":
				after.ImageID += "other"
			case "wrong executable":
				executable = "/usr/bin/java"
			case "same basename":
				executable = "/other/sleep"
			}
			if err := validateObservedExecTransition(before, after, "/usr/bin/sleep", executable); err == nil {
				t.Fatal("foreign target or executable substituted for JVM exec")
			}
		})
	}
	if err := validateObservedExecTransition(wire.Target{}, wire.Target{}, "/usr/bin/sleep", "/usr/bin/sleep"); err == nil {
		t.Fatal("unknown target generation accepted")
	}
}

func TestObservedExecTransitionUsesIndependentCanonicalPath(t *testing.T) {
	target := wire.Target{PID: 123, StartTicks: 456, CgroupID: 789, MountNS: 42, ContainerID: "container", ImageID: "image"}
	canonical := "/usr/lib/cargo/bin/coreutils/sleep"
	if err := validateObservedExecTransition(target, target, canonical, canonical); err != nil {
		t.Fatal("independently bound uutils executable rejected", err)
	}
	if err := validateObservedExecTransition(target, target, canonical, "/usr/bin/sleep"); err == nil {
		t.Fatal("another sleep path substituted for the bound executable")
	}
	for _, unknown := range []string{"", "sleep", "/usr/../bin/sleep"} {
		if err := validateObservedExecTransition(target, target, unknown, unknown); err == nil {
			t.Fatal("unknown/noncanonical replacement binding accepted", unknown)
		}
	}
}

type observedExitStateReceipt struct {
	observedCompilerReceipt
	state      string
	inspectErr error
}

func (d *observedExitStateReceipt) Output(timeout time.Duration, args ...string) (string, error) {
	switch args[0] {
	case "kill":
		return "owned-target", nil
	case "inspect":
		return d.state, d.inspectErr
	default:
		return d.observedCompilerReceipt.Output(timeout, args...)
	}
}

func TestObservedTargetKillRequiresActualExitState(t *testing.T) {
	valid := `{"Status":"exited","Running":false,"Pid":0,"ExitCode":137,"Error":""}`
	dir := t.TempDir()
	if err := observeTargetKill(&observedExitStateReceipt{state: valid}, dir, "owned-target"); err != nil {
		t.Fatal("valid independent exit receipt rejected", err)
	}
	if data, err := os.ReadFile(filepath.Join(dir, "target-exit-state.json")); err != nil || string(data) != valid {
		t.Fatal("original independent receipt not retained", string(data), err)
	}
	for _, state := range []string{
		`{"Status":"exited","Running":false,"ExitCode":137,"Error":""}`,
		`{"Status":"exited","Running":false,"Pid":0,"ExitCode":0,"Error":""}`,
		`{"Status":"exited","Running":false,"Pid":123,"ExitCode":137,"Error":""}`,
		`{"Status":"exited","Running":false,"Pid":0,"ExitCode":137,"Error":"permission denied"}`,
		`{"Status":"exited","Running":false,"Pid":0,"ExitCode":137.5,"Error":""}`,
	} {
		if err := observeTargetKill(&observedExitStateReceipt{state: state}, t.TempDir(), "owned-target"); err == nil {
			t.Fatal("incomplete or unexpected target exit receipt accepted", state)
		}
	}
	if err := observeTargetKill(&observedExitStateReceipt{inspectErr: unix.EACCES}, t.TempDir(), "owned-target"); !errors.Is(err, unix.EACCES) {
		t.Fatal("exit query permission failure lost", err)
	}
}
