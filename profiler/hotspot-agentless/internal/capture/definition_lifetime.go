package capture

import (
	"context"
	"errors"
	"fmt"
	"os"
	"sync"
	"time"

	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/operators"
	"go.opentelemetry.io/ebpf-profiler/libpf"
	"golang.org/x/sys/unix"
	"io.mazewall/experiments/hotspot-agentless/internal/identity"
	"io.mazewall/experiments/hotspot-agentless/internal/preflight"
)

type captureOwnerState uint8

const (
	captureOwnerOpen captureOwnerState = iota
	captureOwnerCloseRequested
	captureOwnerFinalizing
	captureOwnerClosed
)

type captureTargetOwner struct {
	mu              sync.Mutex
	state           captureOwnerState
	pidfd           int
	barrier         *identity.ExecBarrier
	holds           int
	closeReqDone        chan struct{}
	closeEntered        chan struct{}
	barrierCloseEntered chan struct{}
	closeErr            error
	retained        bool
	barrierCloseReq bool
	barrierRetained bool
	barrierClosed   bool
	barrierInFlight bool
	barrierDone     chan struct{}
	barrierCloser   func(*identity.ExecBarrier) error
}

func newCaptureTargetOwner(pidfd int) *captureTargetOwner {
	return &captureTargetOwner{
		pidfd:               pidfd,
		closeReqDone:        make(chan struct{}),
		closeEntered:        make(chan struct{}),
		barrierCloseEntered: make(chan struct{}),
		barrierDone:         make(chan struct{}),
	}
}

func (o *captureTargetOwner) closeBarrierHelper(barrier *identity.ExecBarrier) error {
	if barrier == nil {
		return nil
	}
	if o.barrierCloser != nil {
		return o.barrierCloser(barrier)
	}
	return barrier.Close()
}

func (o *captureTargetOwner) attachBarrier(barrier *identity.ExecBarrier) {
	if o == nil {
		return
	}
	o.mu.Lock()
	defer o.mu.Unlock()
	o.barrier = barrier
}

func (o *captureTargetOwner) acquireHold() error {
	if o == nil {
		return fmt.Errorf("capture target owner unavailable")
	}
	o.mu.Lock()
	defer o.mu.Unlock()
	if o.state != captureOwnerOpen || o.barrierCloseReq {
		return fmt.Errorf("capture target owner closed or close requested")
	}
	o.holds++
	return nil
}

func (o *captureTargetOwner) runFinalizer() {
	o.mu.Lock()
	if o.barrierCloseReq && !o.barrierClosed && !o.barrierInFlight {
		if o.retained || o.barrierRetained || o.barrier == nil {
			o.barrierClosed = true
			o.barrier = nil
			close(o.barrierDone)
		} else {
			barrier := o.barrier
			o.barrier = nil
			o.barrierInFlight = true
			done := o.barrierDone
			go func() {
				err := o.closeBarrierHelper(barrier)
				o.mu.Lock()
				o.closeErr = errors.Join(o.closeErr, err)
				o.barrierClosed = true
				o.barrierInFlight = false
				close(done)
				o.mu.Unlock()
			}()
		}
	}
	barrierInFlight := o.barrierInFlight
	barrierDone := o.barrierDone
	o.mu.Unlock()

	if barrierInFlight {
		<-barrierDone
	}

	o.mu.Lock()
	barrier := o.barrier
	closeBarrier := !o.barrierClosed && !o.retained && !o.barrierRetained && barrier != nil
	if closeBarrier {
		o.barrierClosed = true
		o.barrier = nil
	}
	pidfd := o.pidfd
	closePidfd := !o.retained && pidfd >= 0
	if closePidfd {
		o.pidfd = -1
	}
	o.mu.Unlock()

	var finErr error
	if closeBarrier {
		finErr = errors.Join(finErr, o.closeBarrierHelper(barrier))
	}
	if closePidfd {
		finErr = errors.Join(finErr, unix.Close(pidfd))
	}

	o.mu.Lock()
	defer o.mu.Unlock()
	o.closeErr = errors.Join(o.closeErr, finErr)
	o.state = captureOwnerClosed
	close(o.closeReqDone)
}

func (o *captureTargetOwner) releaseHold() error {
	if o == nil {
		return nil
	}
	o.mu.Lock()
	if o.holds <= 0 {
		o.mu.Unlock()
		return fmt.Errorf("capture target owner hold count underflow")
	}
	o.holds--
	var startFinalizer bool
	var startBarrierFinalizer bool
	if o.holds == 0 {
		if (o.state == captureOwnerCloseRequested || o.state == captureOwnerFinalizing) && o.state != captureOwnerClosed {
			if o.state != captureOwnerFinalizing {
				o.state = captureOwnerFinalizing
				startFinalizer = true
			}
		} else if o.barrierCloseReq && !o.barrierClosed && !o.barrierInFlight {
			if o.retained || o.barrierRetained || o.barrier == nil {
				o.barrierClosed = true
				o.barrier = nil
				close(o.barrierDone)
			} else {
				startBarrierFinalizer = true
			}
		}
	}
	barrier := o.barrier
	if startBarrierFinalizer {
		o.barrier = nil
		o.barrierInFlight = true
	}
	done := o.barrierDone
	o.mu.Unlock()

	if startFinalizer {
		go o.runFinalizer()
	} else if startBarrierFinalizer {
		go func() {
			err := o.closeBarrierHelper(barrier)
			o.mu.Lock()
			o.closeErr = errors.Join(o.closeErr, err)
			o.barrierClosed = true
			o.barrierInFlight = false
			close(done)
			o.mu.Unlock()
		}()
	}
	return nil
}

func (o *captureTargetOwner) CloseBarrier(retain bool) error {
	if o == nil {
		return nil
	}
	o.mu.Lock()
	if retain {
		o.barrierRetained = true
	}
	o.barrierCloseReq = true
	if o.barrierClosed {
		err := o.closeErr
		o.mu.Unlock()
		return err
	}
	if o.holds > 0 {
		o.mu.Unlock()
		return nil
	}
	if !o.barrierInFlight {
		if o.retained || o.barrierRetained || o.barrier == nil {
			o.barrierClosed = true
			o.barrier = nil
			close(o.barrierDone)
			err := o.closeErr
			o.mu.Unlock()
			return err
		}
		barrier := o.barrier
		o.barrier = nil
		o.barrierInFlight = true
		done := o.barrierDone
		go func() {
			err := o.closeBarrierHelper(barrier)
			o.mu.Lock()
			o.closeErr = errors.Join(o.closeErr, err)
			o.barrierClosed = true
			o.barrierInFlight = false
			close(done)
			o.mu.Unlock()
		}()
	}
	if o.barrierCloseEntered != nil {
		select {
		case <-o.barrierCloseEntered:
		default:
			close(o.barrierCloseEntered)
		}
	}
	done := o.barrierDone
	o.mu.Unlock()

	select {
	case <-done:
		o.mu.Lock()
		defer o.mu.Unlock()
		return o.closeErr
	case <-time.After(2 * time.Second):
		return fmt.Errorf("capture target owner close barrier timed out")
	}
}

func (o *captureTargetOwner) Close(retain bool) error {
	if o == nil {
		return nil
	}
	o.mu.Lock()
	if o.state == captureOwnerClosed {
		err := o.closeErr
		o.mu.Unlock()
		return err
	}
	if retain {
		o.retained = true
	}
	if o.state == captureOwnerOpen {
		o.state = captureOwnerCloseRequested
		if o.closeEntered != nil {
			select {
			case <-o.closeEntered:
			default:
				close(o.closeEntered)
			}
		}
	}
	var startFinalizer bool
	if o.holds == 0 && o.state != captureOwnerFinalizing {
		o.state = captureOwnerFinalizing
		startFinalizer = true
	}
	done := o.closeReqDone
	o.mu.Unlock()

	if startFinalizer {
		go o.runFinalizer()
	}

	select {
	case <-done:
		o.mu.Lock()
		err := o.closeErr
		o.mu.Unlock()
		return err
	case <-time.After(2 * time.Second):
		o.mu.Lock()
		err := fmt.Errorf("capture target owner close timed out waiting for %d active holds", o.holds)
		o.mu.Unlock()
		return err
	}
}

// Borrowed capture resources remain owned until the final readout callback ends.
type definitionLifetime struct {
	session  string
	pid      uint32
	birth    uint64
	ctx      context.Context
	pidfd    int
	barrier  *identity.ExecBarrier
	fatal    *fatalState
	observed bool
	expected wire.Target
	gc           operators.GadgetContext
	owner        *captureTargetOwner
	validateHook func() error
}

var _ libpf.TraceDefinitionLifetime = definitionLifetime{}
var _ libpf.TraceDefinitionReadoutAuthority = definitionLifetime{}
var _ libpf.TraceDefinitionRetainer = definitionLifetime{}
var _ libpf.TraceDefinitionRetainer = (*retainedDefinitionLifetime)(nil)
var _ libpf.TraceDefinitionReadoutAuthority = (*retainedDefinitionLifetime)(nil)

func (l definitionLifetime) Session() string  { return l.session }
func (l definitionLifetime) HostTGID() uint32 { return l.pid }
func (l definitionLifetime) Birth() uint64    { return l.birth }

func (l definitionLifetime) RetainDefinitionLifetime() (libpf.TraceDefinitionLifetime, func() error, error) {
	if l.owner == nil {
		return nil, nil, fmt.Errorf("definition capture target owner unavailable")
	}
	if err := l.owner.acquireHold(); err != nil {
		return nil, nil, err
	}
	view, release := newRetainedDefinitionLifetime(l)
	return view, release, nil
}

type retainedDefinitionLifetime struct {
	inner         definitionLifetime
	mu            sync.Mutex
	released      bool
	activeOps     int
	activeEntered chan struct{}
	relOnce       sync.Once
	relDone       bool
}

func newRetainedDefinitionLifetime(inner definitionLifetime) (libpf.TraceDefinitionLifetime, func() error) {
	r := &retainedDefinitionLifetime{inner: inner}
	release := func() error {
		var shouldRelease bool
		r.relOnce.Do(func() {
			r.mu.Lock()
			r.released = true
			if r.activeOps == 0 && !r.relDone {
				r.relDone = true
				shouldRelease = true
			}
			r.mu.Unlock()
		})
		if shouldRelease && r.inner.owner != nil {
			return r.inner.owner.releaseHold()
		}
		return nil
	}
	return r, release
}

func (r *retainedDefinitionLifetime) RetainDefinitionLifetime() (libpf.TraceDefinitionLifetime, func() error, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.released {
		return nil, nil, fmt.Errorf("retained definition lifetime view released")
	}
	r.activeOps++
	if r.activeEntered != nil {
		select {
		case <-r.activeEntered:
		default:
			close(r.activeEntered)
		}
	}
	defer func() {
		r.activeOps--
		if r.activeOps == 0 && r.released && !r.relDone {
			r.relDone = true
			if r.inner.owner != nil {
				_ = r.inner.owner.releaseHold()
			}
		}
	}()
	return r.inner.RetainDefinitionLifetime()
}

func (r *retainedDefinitionLifetime) Session() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.released {
		return ""
	}
	return r.inner.Session()
}

func (r *retainedDefinitionLifetime) HostTGID() uint32 {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.released {
		return 0
	}
	return r.inner.HostTGID()
}

func (r *retainedDefinitionLifetime) Birth() uint64 {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.released {
		return 0
	}
	return r.inner.Birth()
}

func (r *retainedDefinitionLifetime) Validate() error {
	r.mu.Lock()
	if r.released {
		r.mu.Unlock()
		return fmt.Errorf("definition lifetime view released")
	}
	r.activeOps++
	r.mu.Unlock()

	err := r.inner.Validate()

	var shouldRelease bool
	r.mu.Lock()
	r.activeOps--
	if r.activeOps == 0 && r.released && !r.relDone {
		r.relDone = true
		shouldRelease = true
	}
	r.mu.Unlock()

	if shouldRelease && r.inner.owner != nil {
		relErr := r.inner.owner.releaseHold()
		err = errors.Join(err, relErr)
	}
	return err
}

func (r *retainedDefinitionLifetime) WithDefinitionReadout(action func() error) error {
	r.mu.Lock()
	if r.released {
		r.mu.Unlock()
		return fmt.Errorf("definition lifetime view released")
	}
	r.activeOps++
	r.mu.Unlock()

	err := r.inner.WithDefinitionReadout(action)

	var shouldRelease bool
	r.mu.Lock()
	r.activeOps--
	if r.activeOps == 0 && r.released && !r.relDone {
		r.relDone = true
		shouldRelease = true
	}
	r.mu.Unlock()

	if shouldRelease && r.inner.owner != nil {
		relErr := r.inner.owner.releaseHold()
		err = errors.Join(err, relErr)
	}
	return err
}

func pollDefinitionTarget(pidfd int) error {
	if pidfd < 0 {
		return fmt.Errorf("definition target pidfd unavailable")
	}
	fds := []unix.PollFd{{Fd: int32(pidfd), Events: unix.POLLIN}}
	n, err := unix.Poll(fds, 0)
	if err != nil {
		return fmt.Errorf("poll definition target: %w", err)
	}
	if n != 0 || fds[0].Revents != 0 {
		return fmt.Errorf("definition target pidfd signaled %#x", fds[0].Revents)
	}
	return nil
}

func (l definitionLifetime) Validate() (err error) {
	if l.fatal == nil {
		return fmt.Errorf("definition capture fatal state unavailable")
	}
	if err := l.fatal.Err(); err != nil {
		return err
	}
	defer func() {
		if err != nil {
			l.fatal.Record(err)
			err = l.fatal.Err()
		}
	}()
	if l.ctx == nil || l.session == "" || l.pid == 0 || l.birth == 0 {
		return fmt.Errorf("definition capture lifetime incomplete")
	}
	if err := l.ctx.Err(); err != nil {
		return err
	}
	if l.barrier == nil {
		return fmt.Errorf("definition exec barrier unavailable")
	}
	if err := l.barrier.Check(); err != nil {
		return err
	}
	if err := pollDefinitionTarget(l.pidfd); err != nil {
		return err
	}
	if err := identity.CheckInitialPIDNamespace(int(l.pid)); err != nil {
		return err
	}
	stat, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", l.pid))
	if err != nil {
		return fmt.Errorf("definition target birth: %w", err)
	}
	birth, err := preflight.ParseStartTicks(string(stat))
	if err != nil {
		return err
	}
	if birth != l.birth {
		return fmt.Errorf("definition target birth changed")
	}
	if l.observed {
		if err := identity.CheckObservedTarget(l.pid, l.expected); err != nil {
			return err
		}
	}
	if err := l.barrier.Check(); err != nil {
		return err
	}
	if err := pollDefinitionTarget(l.pidfd); err != nil {
		return err
	}
	if err := l.ctx.Err(); err != nil {
		return err
	}
	return l.fatal.Err()
}

type definitionReadoutProvider interface {
	WithDefinitionReadout(string, uint32, uint64, func() error) error
}

func (l definitionLifetime) WithDefinitionReadout(action func() error) (err error) {
	if l.owner != nil {
		if err := l.owner.acquireHold(); err != nil {
			return fmt.Errorf("acquire definition capture hold: %w", err)
		}
		defer func() {
			relErr := l.owner.releaseHold()
			err = errors.Join(err, relErr)
		}()
	}
	if l.gc == nil {
		return fmt.Errorf("definition Gadget context unavailable")
	}
	raw, ok := l.gc.GetVar("ebpfInstance")
	if !ok {
		return fmt.Errorf("definition original source owner unavailable")
	}
	owner, ok := raw.(definitionReadoutProvider)
	if !ok {
		return fmt.Errorf("definition original owner lacks readout authority")
	}
	validate := l.Validate
	if l.validateHook != nil {
		validate = l.validateHook
	}
	return owner.WithDefinitionReadout(l.session, l.pid, l.birth, func() error {
		if err := validate(); err != nil {
			return err
		}
		if action != nil {
			return action()
		}
		return nil
	})
}
