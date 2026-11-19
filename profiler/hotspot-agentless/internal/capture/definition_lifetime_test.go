package capture

import (
	"context"
	"errors"
	"os"
	"sync"
	"os/exec"
	"testing"
	"time"

	"github.com/inspektor-gadget/inspektor-gadget/pkg/operators"
	"go.opentelemetry.io/ebpf-profiler/libpf"
	"golang.org/x/sys/unix"
	"io.mazewall/experiments/hotspot-agentless/internal/identity"
)

func TestDefinitionLifetimeValidationFailuresStayFatal(t *testing.T) {
	fatal := &fatalState{}
	fatal.Record(unix.EACCES)
	lifetime := definitionLifetime{fatal: fatal}
	if err := lifetime.Validate(); !errors.Is(err, unix.EACCES) {
		t.Fatal("prior fatal overwritten", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	lifetime = definitionLifetime{session: "s", pid: 123, birth: 456, ctx: ctx, fatal: &fatalState{}}
	if err := lifetime.Validate(); !errors.Is(err, context.Canceled) || !errors.Is(lifetime.fatal.Err(), context.Canceled) {
		t.Fatal("context failure not retained before native validation", err)
	}
	barrier := &identity.ExecBarrier{}
	if err := barrier.Close(); err != nil {
		t.Fatal(err)
	}
	lifetime = definitionLifetime{session: "s", pid: 123, birth: 456, ctx: context.Background(), barrier: barrier, pidfd: -1, fatal: &fatalState{}}
	if err := lifetime.Validate(); err == nil || lifetime.fatal.Err() == nil {
		t.Fatal("closed borrowed resource admitted", err)
	}
}

func TestDefinitionLifetimePollsRealBorrowedPidfd(t *testing.T) {
	fd, err := unix.PidfdOpen(os.Getpid(), 0)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := unix.Close(fd); err != nil {
			t.Error(err)
		}
	}()
	if err := pollDefinitionTarget(fd); err != nil {
		t.Fatal(err)
	}
	if err := pollDefinitionTarget(-1); err == nil {
		t.Fatal("absent borrowed pidfd accepted")
	}
}

func TestDefinitionLifetimePollRefusesExitedTarget(t *testing.T) {
	cmd := exec.Command("/bin/true")
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() {
		if cmd.ProcessState == nil {
			if err := cmd.Wait(); err != nil {
				t.Error(err)
			}
		}
	}()
	fd, err := unix.PidfdOpen(cmd.Process.Pid, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := unix.Close(fd); err != nil {
			t.Error(err)
		}
	}()
	if err := cmd.Wait(); err != nil {
		t.Fatal(err)
	}
	if err := pollDefinitionTarget(fd); err == nil {
		t.Fatal("exited target accepted through retained actual pidfd")
	}
}

// This tests lazy tuple forwarding only, not native readout provenance/finality.
type definitionContextFixture struct {
	operators.GadgetContext
	owner any
}

func (g *definitionContextFixture) GetVar(name string) (any, bool) {
	return g.owner, name == "ebpfInstance" && g.owner != nil
}

type definitionProviderFixture struct {
	session string
	pid     uint32
	birth   uint64
	calls   int
}

func (p *definitionProviderFixture) WithDefinitionReadout(session string, pid uint32, birth uint64, action func() error) error {
	if p.session != "" && (session != p.session || pid != p.pid || birth != p.birth) {
		return unix.EPERM
	}
	p.calls++
	if action != nil {
		return action()
	}
	return nil
}

func TestDefinitionLifetimeResolvesOriginalProviderLazily(t *testing.T) {
	gc := &definitionContextFixture{}
	lifetime := definitionLifetime{session: "s", pid: 123, birth: 456, gc: gc}
	if lifetime.Session() != "s" || lifetime.HostTGID() != 123 || lifetime.Birth() != 456 {
		t.Fatal("capture tuple changed")
	}
	actions := 0
	action := func() error { actions++; return unix.EACCES }
	if err := lifetime.WithDefinitionReadout(action); err == nil || actions != 0 {
		t.Fatal("missing constructor-time provider admitted", err)
	}
	gc.owner = struct{}{}
	if err := lifetime.WithDefinitionReadout(action); err == nil || actions != 0 {
		t.Fatal("wrong provider admitted", err)
	}
	provider := &definitionProviderFixture{}
	gc.owner = provider
	if err := lifetime.WithDefinitionReadout(action); err == nil || actions != 0 || provider.calls != 1 {
		t.Fatal("incomplete lifetime admitted by readout validation", err, actions, provider.calls)
	}
	lifetime.validateHook = func() error { return nil }
	if err := lifetime.WithDefinitionReadout(action); !errors.Is(err, unix.EACCES) || actions != 1 || provider.calls != 2 {
		t.Fatal("original provider/tuple/action error not forwarded", err, actions, provider.calls)
	}
}

func TestDefinitionLifetimeOwnerCloseRacingRetain(t *testing.T) {
	fd, err := unix.PidfdOpen(os.Getpid(), 0)
	if err != nil {
		t.Fatal(err)
	}
	owner := newCaptureTargetOwner(fd)
	if err := owner.Close(false); err != nil {
		t.Fatal("owner close failed:", err)
	}
	if err := owner.acquireHold(); err == nil {
		t.Fatal("acquireHold admitted on closed owner")
	}
	lifetime := definitionLifetime{session: "s", pid: uint32(os.Getpid()), birth: 1, owner: owner}
	if _, _, err := lifetime.RetainDefinitionLifetime(); err == nil {
		t.Fatal("RetainDefinitionLifetime admitted on closed owner")
	}
}

func TestDefinitionLifetimeCloseRequestWithEnteredCallbackBlocksActualCloseTillReturn(t *testing.T) {
	var fds [2]int
	if err := unix.Pipe2(fds[:], unix.O_CLOEXEC); err != nil {
		t.Fatal(err)
	}
	defer func() { _ = unix.Close(fds[1]) }()

	owner := newCaptureTargetOwner(fds[0])

	entered := make(chan struct{})
	continueAction := make(chan struct{})
	actionDone := make(chan struct{})
	var actionErr error

	var closeErr error
	closeWorkerDone := make(chan struct{})
	var closeStarted bool

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
		if closeStarted {
			select {
			case <-closeWorkerDone:
			case <-time.After(2 * time.Second):
				t.Fatal("cleanup timed out waiting for closeWorkerDone")
			}
		}
		_ = owner.Close(false)
	}()

	go func() {
		defer close(actionDone)
		if err := owner.acquireHold(); err != nil {
			actionErr = err
			return
		}
		defer func() {
			actionErr = errors.Join(actionErr, owner.releaseHold())
		}()
		close(entered)
		<-continueAction
	}()

	select {
	case <-entered:
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for readout action entry")
	}

	// While action is active (holding a hold), request close concurrently
	closeStarted = true
	go func() {
		defer close(closeWorkerDone)
		closeErr = owner.Close(false)
	}()

	// Wait for owner to actually enter Close transition
	select {
	case <-owner.closeEntered:
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for owner closeEntered signal")
	}

	// Verify owner is in close-requested state and fd is still valid
	owner.mu.Lock()
	st := owner.state
	owner.mu.Unlock()
	if st != captureOwnerCloseRequested {
		t.Fatalf("expected captureOwnerCloseRequested, got %d", st)
	}

	if _, err := unix.FcntlInt(uintptr(fds[0]), unix.F_GETFD, 0); err != nil {
		t.Fatal("pidfd prematurely closed while model action was entered:", err)
	}

	// Release action
	close(continueAction)
	select {
	case <-actionDone:
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for action return")
	}
	if actionErr != nil {
		t.Fatal("model action returned error:", actionErr)
	}

	// Close must now complete and fd must be closed
	select {
	case <-closeWorkerDone:
		if closeErr != nil {
			t.Fatal("owner close failed:", closeErr)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("owner close timed out waiting for action return")
	}

	if _, err := unix.FcntlInt(uintptr(fds[0]), unix.F_GETFD, 0); err == nil {
		t.Fatal("pidfd still open after final close returned")
	}
}

func TestDefinitionLifetimeRetainedViewReRetainAfterReleaseRejects(t *testing.T) {
	var fds [2]int
	if err := unix.Pipe2(fds[:], unix.O_CLOEXEC); err != nil {
		t.Fatal(err)
	}
	defer func() { _ = unix.Close(fds[1]) }()

	owner := newCaptureTargetOwner(fds[0])
	defer owner.Close(false)

	lifetime := definitionLifetime{
		session: "re-retain-released",
		pid:     uint32(os.Getpid()),
		birth:   12345,
		ctx:     context.Background(),
		pidfd:   fds[0],
		owner:   owner,
		fatal:   &fatalState{},
	}

	view, release, err := lifetime.RetainDefinitionLifetime()
	if err != nil {
		t.Fatal("initial retain failed:", err)
	}
	if err := release(); err != nil {
		t.Fatal("release failed:", err)
	}

	retainer, ok := view.(libpf.TraceDefinitionRetainer)
	if !ok {
		t.Fatal("view lacks retainer capability")
	}
	if _, _, err := retainer.RetainDefinitionLifetime(); err == nil {
		t.Fatal("expected re-retain after release to be rejected")
	}

	owner.mu.Lock()
	holds := owner.holds
	owner.mu.Unlock()
	if holds != 0 {
		t.Fatalf("expected 0 holds after release and rejected re-retain, got %d", holds)
	}
}

func TestDefinitionLifetimeRetainedViewRacingReleaseAndReRetainOrder(t *testing.T) {
	// Ordering 1: Retain wins race before release -> hold count increases, old hold kept until release
	{
		var fds [2]int
		if err := unix.Pipe2(fds[:], unix.O_CLOEXEC); err != nil {
			t.Fatal(err)
		}
		defer func() {
			_ = unix.Close(fds[1])
		}()

		owner := newCaptureTargetOwner(fds[0])
		var release1, release2 func() error
		retainDone := make(chan struct{})
		releaseDone := make(chan struct{})
		var retainStarted, releaseStarted, ownerMuHeld bool
		defer func() {
			if ownerMuHeld {
				owner.mu.Unlock()
			}
			retainJoined, releaseJoined := !retainStarted, !releaseStarted
			if retainStarted {
				select {
				case <-retainDone:
					retainJoined = true
				case <-time.After(2 * time.Second):
					t.Error("cleanup timed out waiting for retain")
				}
			}
			if releaseStarted {
				select {
				case <-releaseDone:
					releaseJoined = true
				case <-time.After(2 * time.Second):
					t.Error("cleanup timed out waiting for release")
				}
			}
			if retainJoined && release2 != nil {
				if err := release2(); err != nil {
					t.Errorf("cleanup release2: %v", err)
				}
			}
			if releaseJoined && release1 != nil {
				if err := release1(); err != nil {
					t.Errorf("cleanup release1: %v", err)
				}
			}
			if err := owner.Close(false); err != nil {
				t.Errorf("cleanup owner close: %v", err)
			}
			select {
			case <-owner.closeReqDone:
			case <-time.After(2 * time.Second):
				t.Error("cleanup timed out waiting for owner completion")
			}
		}()

		lifetime := definitionLifetime{
			session: "race-order-1",
			pid:     uint32(os.Getpid()),
			birth:   12345,
			ctx:     context.Background(),
			pidfd:   fds[0],
			owner:   owner,
			fatal:   &fatalState{},
		}

		view1, rel1, err := lifetime.RetainDefinitionLifetime()
		if err != nil {
			t.Fatal(err)
		}
		release1 = rel1

		retView := view1.(*retainedDefinitionLifetime)
		activeEntered := make(chan struct{})
		retView.activeEntered = activeEntered

		var view2 libpf.TraceDefinitionLifetime
		var retainErr error
		var releaseErr error

		// Lock owner.mu so that Retain enters, increments activeOps to 1,
		// signals activeEntered, then blocks waiting for owner.mu to acquire new hold.
		owner.mu.Lock()
		ownerMuHeld = true

		retainStarted = true
		go func() {
			defer close(retainDone)
			retainer := view1.(libpf.TraceDefinitionRetainer)
			view2, release2, retainErr = retainer.RetainDefinitionLifetime()
		}()

		select {
		case <-activeEntered:
		case <-time.After(2 * time.Second):
			owner.mu.Unlock()
			ownerMuHeld = false
			t.Fatal("timed out waiting for activeEntered")
		}

		// Launch release1 while Retain is entered (activeOps == 1)
		releaseStarted = true
		go func() {
			defer close(releaseDone)
			releaseErr = release1()
		}()

		// Verify holds is still 1 (old hold kept while retain is entered)
		hBefore := owner.holds
		owner.mu.Unlock()
		ownerMuHeld = false
		if hBefore != 1 {
			t.Fatalf("expected holds to remain 1 while retain is entered, got %d", hBefore)
		}

		// Retain acquires new hold (holds -> 2),
		// then in defer drops activeOps to 0, which triggers release of the old hold (holds -> 1).

		select {
		case <-retainDone:
			if retainErr != nil {
				t.Fatalf("retain failed: %v", retainErr)
			}
		case <-time.After(2 * time.Second):
			t.Fatal("timed out waiting for retainDone")
		}

		select {
		case <-releaseDone:
			if releaseErr != nil {
				t.Fatalf("release failed: %v", releaseErr)
			}
		case <-time.After(2 * time.Second):
			t.Fatal("timed out waiting for releaseDone")
		}
		release1 = nil

		owner.mu.Lock()
		hAfter := owner.holds
		owner.mu.Unlock()
		if hAfter != 1 {
			t.Fatalf("expected 1 active hold after retain won and old view released, got %d", hAfter)
		}

		// Release view2 -> holds drop to 0
		if err := release2(); err != nil {
			t.Fatal(err)
		}
		release2 = nil

		owner.mu.Lock()
		hFinal := owner.holds
		owner.mu.Unlock()
		if hFinal != 0 {
			t.Fatalf("expected 0 holds after release2, got %d", hFinal)
		}
		_ = view2
	}

	// Ordering 2: Release wins before queued re-retain is admitted.
	{
		var fds [2]int
		if err := unix.Pipe2(fds[:], unix.O_CLOEXEC); err != nil {
			t.Fatal(err)
		}
		defer func() {
			_ = unix.Close(fds[1])
		}()

		owner := newCaptureTargetOwner(fds[0])
		var release1, release2 func() error
		queued := make(chan struct{})
		admit := make(chan struct{})
		var admitOnce sync.Once
		unblock := func() { admitOnce.Do(func() { close(admit) }) }
		retainDone := make(chan struct{})
		var retainStarted bool
		defer func() {
			unblock()
			joined := !retainStarted
			if retainStarted {
				select {
				case <-retainDone:
					joined = true
				case <-time.After(2 * time.Second):
					t.Error("cleanup timed out waiting for retain")
				}
			}
			if joined && release2 != nil {
				if err := release2(); err != nil {
					t.Errorf("cleanup release2: %v", err)
				}
			}
			if release1 != nil {
				if err := release1(); err != nil {
					t.Errorf("cleanup release1: %v", err)
				}
			}
			if err := owner.Close(false); err != nil {
				t.Errorf("cleanup owner close: %v", err)
			}
			select {
			case <-owner.closeReqDone:
			case <-time.After(2 * time.Second):
				t.Error("cleanup timed out waiting for owner completion")
			}
		}()

		lifetime := definitionLifetime{
			session: "race-order-2",
			pid:     uint32(os.Getpid()),
			birth:   12345,
			ctx:     context.Background(),
			pidfd:   fds[0],
			owner:   owner,
			fatal:   &fatalState{},
		}

		view1, rel1, err := lifetime.RetainDefinitionLifetime()
		if err != nil {
			t.Fatal(err)
		}
		release1 = rel1

		var retainErr error

		retainStarted = true
		go func() {
			defer close(retainDone)
			close(queued)
			<-admit
			retainer := view1.(libpf.TraceDefinitionRetainer)
			_, release2, retainErr = retainer.RetainDefinitionLifetime()
		}()

		select {
		case <-queued:
		case <-time.After(2 * time.Second):
			t.Fatal("timed out waiting for queued retain")
		}
		if err := release1(); err != nil {
			t.Fatal(err)
		}
		unblock()

		select {
		case <-retainDone:
			if retainErr == nil {
				t.Fatal("expected re-retain to be rejected after release won")
			}
		case <-time.After(2 * time.Second):
			t.Fatal("timed out waiting for retainDone")
		}

		owner.mu.Lock()
		h := owner.holds
		owner.mu.Unlock()
		if h != 0 {
			t.Fatalf("expected 0 holds after release won, got %d", h)
		}
	}
}

func TestCaptureOwnerCloseBarrierLifecycleAndConcurrency(t *testing.T) {
	var fds [2]int
	if err := unix.Pipe2(fds[:], unix.O_CLOEXEC); err != nil {
		t.Fatal(err)
	}

	owner := newCaptureTargetOwner(fds[0])

	barrierStarted := make(chan struct{})
	barrierRelease := make(chan struct{})
	var barrierCalls int
	var barrierMu sync.Mutex

	var releaseBarrierOnce sync.Once
	unblockBarrier := func() {
		releaseBarrierOnce.Do(func() {
			close(barrierRelease)
		})
	}

	owner.barrierCloser = func(b *identity.ExecBarrier) error {
		barrierMu.Lock()
		barrierCalls++
		barrierMu.Unlock()
		select {
		case <-barrierStarted:
		default:
			close(barrierStarted)
		}
		<-barrierRelease
		return unix.EACCES
	}
	owner.barrier = &identity.ExecBarrier{}

	var fullCloseErr error
	fullCloseDone := make(chan struct{})
	var secondBarrierErr error
	secondBarrierDone := make(chan struct{})

	var hasHold bool
	var fullCloseStarted bool
	var secondBarrierStarted bool

	defer func() {
		unblockBarrier()
		if hasHold {
			if err := owner.releaseHold(); err != nil {
				t.Errorf("cleanup release hold: %v", err)
			}
		}
		if err := owner.Close(false); err == nil || !errors.Is(err, unix.EACCES) || err.Error() != unix.EACCES.Error() {
			t.Errorf("cleanup owner close: expected only EACCES, got %v", err)
		}
		select {
		case <-owner.closeReqDone:
		case <-time.After(2 * time.Second):
			t.Error("cleanup timed out waiting for owner completion")
		}
		if fullCloseStarted {
			select {
			case <-fullCloseDone:
			case <-time.After(2 * time.Second):
				t.Error("cleanup timed out waiting for fullCloseDone")
			}
		}
		if secondBarrierStarted {
			select {
			case <-secondBarrierDone:
			case <-time.After(2 * time.Second):
				t.Error("cleanup timed out waiting for secondBarrierDone")
			}
		}
		_ = unix.Close(fds[1])
	}()

	// 1. Enter a hold
	if err := owner.acquireHold(); err != nil {
		t.Fatal(err)
	}
	hasHold = true

	// 2. Call CloseBarrier while hold is active
	if err := owner.CloseBarrier(false); err != nil {
		t.Fatalf("expected nil from CloseBarrier with active holds, got: %v", err)
	}
	owner.mu.Lock()
	req := owner.barrierCloseReq
	inFlight := owner.barrierInFlight
	owner.mu.Unlock()
	if !req {
		t.Fatal("expected barrierCloseReq to be true")
	}
	if inFlight {
		t.Fatal("barrier should not be in-flight while holds > 0")
	}

	// New-hold rejection AFTER CloseBarrier request
	if err := owner.acquireHold(); err == nil {
		t.Fatal("expected acquireHold to be rejected after CloseBarrier requested")
	}

	// 3. Last release triggers barrier finalizer asynchronously
	hasHold = false
	if err := owner.releaseHold(); err != nil {
		t.Fatal(err)
	}

	select {
	case <-barrierStarted:
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for barrier physical close to start")
	}

	owner.mu.Lock()
	inFlight = owner.barrierInFlight
	owner.mu.Unlock()
	if !inFlight {
		t.Fatal("expected barrierInFlight to be true")
	}

	// 4. Concurrent full Close while barrier is in flight
	fullCloseStarted = true
	go func() {
		defer close(fullCloseDone)
		fullCloseErr = owner.Close(false)
	}()

	select {
	case <-owner.closeEntered:
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for owner.closeEntered signal")
	}

	// 5. Concurrent second CloseBarrier while barrier is in flight
	secondBarrierStarted = true
	go func() {
		defer close(secondBarrierDone)
		secondBarrierErr = owner.CloseBarrier(false)
	}()

	select {
	case <-owner.barrierCloseEntered:
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for owner.barrierCloseEntered signal")
	}

	// Verify both callers are waiting and pidfd is still open
	select {
	case <-fullCloseDone:
		t.Fatal("full Close finished prematurely while barrier was still in flight")
	default:
	}
	select {
	case <-secondBarrierDone:
		t.Fatal("second CloseBarrier finished prematurely while barrier was still in flight")
	default:
	}
	if _, err := unix.FcntlInt(uintptr(fds[0]), unix.F_GETFD, 0); err != nil {
		t.Fatal("pidfd closed while barrier was still in flight:", err)
	}

	// 6. Unblock the physical barrier close
	unblockBarrier()

	select {
	case <-fullCloseDone:
		if !errors.Is(fullCloseErr, unix.EACCES) {
			t.Fatalf("expected full Close to join barrier error EACCES, got %v", fullCloseErr)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for full Close")
	}

	select {
	case <-secondBarrierDone:
		if !errors.Is(secondBarrierErr, unix.EACCES) {
			t.Fatalf("expected second CloseBarrier to join barrier error EACCES, got %v", secondBarrierErr)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("timed out waiting for second CloseBarrier")
	}

	barrierMu.Lock()
	calls := barrierCalls
	barrierMu.Unlock()
	if calls != 1 {
		t.Fatalf("expected exactly 1 barrier close call, got %d", calls)
	}

	if _, err := unix.FcntlInt(uintptr(fds[0]), unix.F_GETFD, 0); err == nil {
		t.Fatal("pidfd still open after full Close returned")
	}
}

func TestCaptureOwnerCloseRetainedDisposition(t *testing.T) {
	var fds [2]int
	if err := unix.Pipe2(fds[:], unix.O_CLOEXEC); err != nil {
		t.Fatal(err)
	}
	defer func() {
		_ = unix.Close(fds[0])
		_ = unix.Close(fds[1])
	}()

	owner := newCaptureTargetOwner(fds[0])
	var barrierCalls int
	owner.barrierCloser = func(b *identity.ExecBarrier) error {
		barrierCalls++
		return nil
	}
	owner.barrier = &identity.ExecBarrier{}

	// Close with retain = true
	if err := owner.Close(true); err != nil {
		t.Fatalf("owner Close(true) failed: %v", err)
	}

	owner.mu.Lock()
	closed := owner.state == captureOwnerClosed
	retained := owner.retained
	owner.mu.Unlock()

	if !closed {
		t.Fatal("expected owner to be closed")
	}
	if !retained {
		t.Fatal("expected owner to have retained = true")
	}

	// Retained disposition must NOT close the pidfd or the barrier
	if _, err := unix.FcntlInt(uintptr(fds[0]), unix.F_GETFD, 0); err != nil {
		t.Fatal("pidfd closed despite retained disposition:", err)
	}
	if barrierCalls != 0 {
		t.Fatalf("barrier closed despite retained disposition: %d calls", barrierCalls)
	}
}

func TestDefinitionLifetimeReleasedViewRejects(t *testing.T) {
	fd, err := unix.PidfdOpen(os.Getpid(), 0)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = unix.Close(fd) }()
	owner := newCaptureTargetOwner(fd)
	gc := &definitionContextFixture{owner: &definitionProviderFixture{}}
	lifetime := definitionLifetime{session: "s", pid: 123, birth: 456, gc: gc, owner: owner, fatal: &fatalState{}}

	view, release, err := lifetime.RetainDefinitionLifetime()
	if err != nil {
		t.Fatal(err)
	}
	if err := release(); err != nil {
		t.Fatal(err)
	}

	if err := view.Validate(); err == nil {
		t.Fatal("released view admitted Validate")
	}
	if s := view.Session(); s != "" {
		t.Fatalf("released view returned non-empty session: %q", s)
	}
	if pid := view.HostTGID(); pid != 0 {
		t.Fatalf("released view returned non-zero host TGID: %d", pid)
	}
	if birth := view.Birth(); birth != 0 {
		t.Fatalf("released view returned non-zero birth: %d", birth)
	}
	auth, ok := view.(libpf.TraceDefinitionReadoutAuthority)
	if !ok {
		t.Fatal("retained view does not implement TraceDefinitionReadoutAuthority")
	}
	if err := auth.WithDefinitionReadout(func() error { return nil }); err == nil {
		t.Fatal("released view admitted WithDefinitionReadout")
	}
}
