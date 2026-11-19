//go:build mw_kernel && linux

package capture

import (
	"context"
	"errors"
	"fmt"
	"os"
	"testing"
	"time"

	cebpf "github.com/cilium/ebpf"

	"go.opentelemetry.io/ebpf-profiler/libpf"
	"go.opentelemetry.io/ebpf-profiler/metrics"
	ottimes "go.opentelemetry.io/ebpf-profiler/times"
	"go.opentelemetry.io/ebpf-profiler/tracer"
	"go.opentelemetry.io/ebpf-profiler/tracer/types"
	"go.opentelemetry.io/otel/metric/noop"
	"golang.org/x/sys/unix"
	"io.mazewall/experiments/hotspot-agentless/internal/identity"
	"io.mazewall/experiments/hotspot-agentless/internal/preflight"
)

// Actual process capability -> Config -> original maps -> actual Tracer.Close.
// No native Join window/final result is synthesized by this acquisition control.
func TestKernelDefinitionLifetimeOriginalBindingAndClose(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	pid := os.Getpid()
	pidfd, err := unix.PidfdOpen(pid, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := unix.Close(pidfd); err != nil {
			t.Error(err)
		}
	}()
	barrier, err := identity.NewExecBarrier(pid)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := barrier.Close(); err != nil {
			t.Error(err)
		}
	}()
	stat, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid))
	if err != nil {
		t.Fatal(err)
	}
	birth, err := preflight.ParseStartTicks(string(stat))
	if err != nil {
		t.Fatal(err)
	}
	lifetime := definitionLifetime{session: "native-definition-binding", pid: uint32(pid), birth: birth, ctx: ctx, pidfd: pidfd, barrier: barrier, fatal: &fatalState{}}
	if err := lifetime.Validate(); err != nil {
		t.Fatal("actual retained process/initial namespace", err)
	}
	metrics.Start(noop.Meter{})
	var generation uint64
	var counterID, perfID uint32
	for range 2 {
		tr, err := tracer.NewTracer(ctx, &tracer.Config{TraceDefinitionLifetime: lifetime, IncludeTracers: types.AllTracers(), Intervals: ottimes.New(0, time.Second, 0), ProbabilisticThreshold: 100, ProbabilisticInterval: time.Second})
		if err != nil {
			t.Fatal("actual source constructor", err)
		}
		t.Cleanup(tr.Close)
		binding, err := tr.TraceDefinitionBinding()
		if err != nil || binding.Session != lifetime.Session() || binding.HostTGID != lifetime.HostTGID() || binding.Birth != birth || binding.Generation <= generation || binding.CounterMapID == 0 || binding.PerfMapID == 0 || binding.CounterMapID == counterID || binding.PerfMapID == perfID || binding.PossibleCPUs <= 0 {
			t.Fatal("actual independent acquisition/generation", binding, err)
		}
		generation, counterID, perfID = binding.Generation, binding.CounterMapID, binding.PerfMapID
		tr.Close()
		if value, err := tr.TraceDefinitionBinding(); err == nil || value.CounterMapID != 0 {
			t.Fatal("actual Close did not abandon binding", value, err)
		}
		if value, err := tr.TraceDefinitionFinal(ctx); err == nil || value.Outcome != 0 || errors.Is(err, context.DeadlineExceeded) {
			t.Fatal("Close-first source invented finality or merely timed out", value, err)
		}
	}
}

func TestKernelDefinitionMapCloneOriginalCloseRetainsID(t *testing.T) {
	spec := &cebpf.MapSpec{
		Type:       cebpf.Array,
		KeySize:    4,
		ValueSize:  4,
		MaxEntries: 1,
	}
	orig, err := cebpf.NewMap(spec)
	if err != nil {
		t.Fatal("create original bpf map", err)
	}
	origInfo, err := orig.Info()
	if err != nil {
		_ = orig.Close()
		t.Fatal("read original map info", err)
	}
	origID, ok := origInfo.ID()
	if !ok || origID == 0 {
		_ = orig.Close()
		t.Fatal("original map ID zero or unavailable")
	}

	cloned, err := orig.Clone()
	if err != nil {
		_ = orig.Close()
		t.Fatal("clone map", err)
	}
	defer cloned.Close()

	if cloned.FD() == orig.FD() {
		t.Fatal("cloned map shares same FD token instead of dup")
	}

	cloneInfo, err := cloned.Info()
	if err != nil {
		t.Fatal("read clone map info", err)
	}
	cloneID, ok := cloneInfo.ID()
	if !ok || cloneID != origID {
		t.Fatalf("clone ID mismatch: orig=%d clone=%d", origID, cloneID)
	}

	// Close the original map; the clone must retain access and kernel ID
	if err := orig.Close(); err != nil {
		t.Fatal("close original map", err)
	}

	afterInfo, err := cloned.Info()
	if err != nil {
		t.Fatal("read clone map info after original close", err)
	}
	afterID, ok := afterInfo.ID()
	if !ok || afterID != origID {
		t.Fatalf("clone ID lost after original close: orig=%d after=%d", origID, afterID)
	}

	var key, val uint32 = 0, 42
	if err := cloned.Update(key, val, cebpf.UpdateAny); err != nil {
		t.Fatal("update cloned map after original close", err)
	}
	var readVal uint32
	if err := cloned.Lookup(key, &readVal); err != nil || readVal != val {
		t.Fatalf("lookup cloned map: got=%d expected=%d err=%v", readVal, val, err)
	}
}

func TestKernelDefinitionLifetimeCloseRequestWithEnteredCallbackBlocksActualCloseTillReturn(t *testing.T) {
	pid := uint32(os.Getpid())
	stat, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid))
	if err != nil {
		t.Fatal(err)
	}
	birth, err := preflight.ParseStartTicks(string(stat))
	if err != nil {
		t.Fatal(err)
	}
	fd, err := unix.PidfdOpen(int(pid), 0)
	if err != nil {
		t.Fatal(err)
	}
	barrier, err := identity.NewExecBarrier(int(pid))
	if err != nil {
		_ = unix.Close(fd)
		t.Fatal(err)
	}
	owner := newCaptureTargetOwner(fd)
	owner.attachBarrier(barrier)

	provider := &definitionProviderFixture{session: "s", pid: pid, birth: birth}
	gc := &definitionContextFixture{owner: provider}
	lifetime := definitionLifetime{
		session: "s",
		pid:     pid,
		birth:   birth,
		ctx:     context.Background(),
		barrier: barrier,
		pidfd:   fd,
		gc:      gc,
		owner:   owner,
		fatal:   &fatalState{},
	}
	view, release, err := lifetime.RetainDefinitionLifetime()
	if err != nil {
		_ = owner.Close(false)
		t.Fatal(err)
	}

	authority, ok := view.(libpf.TraceDefinitionReadoutAuthority)
	if !ok {
		_ = release()
		_ = owner.Close(false)
		t.Fatal("retained view lacks readout authority")
	}

	entered := make(chan struct{})
	continueAction := make(chan struct{})
	actionDone := make(chan struct{})
	var actionErr error

	defer func() {
		select {
		case <-continueAction:
		default:
			close(continueAction)
		}
		select {
		case <-actionDone:
		case <-time.After(2 * time.Second):
			t.Fatal("cleanup timed out waiting for actionDone")
		}
		_ = release()
		_ = owner.Close(false)
	}()

	go func() {
		defer close(actionDone)
		actionErr = authority.WithDefinitionReadout(func() error {
			close(entered)
			<-continueAction
			return nil
		})
	}()

	select {
	case <-entered:
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for readout action entry")
	}

	// While action is active, request close concurrently
	closeDone := make(chan error, 1)
	go func() {
		closeDone <- owner.Close(false)
	}()

	// Verify owner is in close-requested state and fd is still valid
	if _, err := unix.FcntlInt(uintptr(fd), unix.F_GETFD, 0); err != nil {
		t.Fatal("pidfd prematurely closed while readout action was entered:", err)
	}

	// Release action
	close(continueAction)
	select {
	case <-actionDone:
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for action return")
	}
	if actionErr != nil {
		t.Fatal("readout action returned error:", actionErr)
	}
	if err := release(); err != nil {
		t.Fatal("release returned error:", err)
	}

	// Close must now complete and fd must be closed
	select {
	case err := <-closeDone:
		if err != nil {
			t.Fatal("owner close failed:", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("owner close timed out waiting for action return")
	}

	if _, err := unix.FcntlInt(uintptr(fd), unix.F_GETFD, 0); err == nil {
		t.Fatal("pidfd still open after final close returned")
	}
}
