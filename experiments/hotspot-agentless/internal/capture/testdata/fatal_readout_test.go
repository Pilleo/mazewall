package capture

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"sync"
	"syscall"
	"testing"
	"time"

	gadgetcontext "github.com/inspektor-gadget/inspektor-gadget/pkg/gadget-context"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/operators"
)

type sequenceReadFailure struct{ err error }

func (r *sequenceReadFailure) ReadAt([]byte, int64) (int, error) { return -1, r.err }

type pendingOwnerObservation struct{ owner *eventStopOwner }

func (o pendingOwnerObservation) EventFinalization() (operators.EventFinalizationObservation, bool) {
	return o.owner.snapshot(), true
}

func TestRunExitObservedReaderAndPendingOwner(t *testing.T) {
	for _, denial := range []syscall.Errno{0, syscall.EACCES, syscall.EPERM} {
		t.Run(fmt.Sprint(int(denial)), func(t *testing.T) {
			session, cancelSession := context.WithCancel(context.Background())
			defer cancelSession()
			fatal := fatalState{cancel: cancelSession}
			var runErr error = context.Canceled
			var first error
			if denial != 0 {
				first = &os.PathError{Op: "read", Path: "/proc/123/mem", Err: syscall.EIO}
				base := &sequenceReadFailure{err: first}
				reader := newObservedReader(base, fatal.Record)
				_, _ = reader.ReadAt(make([]byte, 8), 0)
				base.err = &os.PathError{Op: "read", Path: "/proc/123/mem", Err: denial}
				_, err := reader.ReadAt(make([]byte, 8), 0)
				runErr = fmt.Errorf("provider formatting: %v", err)
				if errors.Is(runErr, denial) || !errors.Is(fatal.Err(), first) || !errors.Is(fatal.Err(), denial) || session.Err() == nil {
					t.Fatal("fixture did not retain real reader causes independently of formatting", runErr, fatal.Err())
				}
			}
			backend := newStopBackendFixture()
			owner := newEventStopOwner(backend)
			readout, cancelReadout := context.WithCancel(context.Background())
			defer cancelReadout()
			done := make(chan error, 1)
			go func() { _, err := owner.stop(readout); done <- err }()
			select {
			case <-backend.flushStarted:
			case <-time.After(time.Second):
				t.Fatal("readout did not start")
			}
			cancelReadout()
			var stopErr error
			select {
			case stopErr = <-done:
			case <-time.After(time.Second):
				t.Fatal("readout wait ignored cancellation")
			}
			select {
			case <-owner.done:
			case <-time.After(time.Second):
				t.Fatal("stop owner did not settle")
			}
			if !errors.Is(stopErr, context.Canceled) {
				t.Fatal(stopErr)
			}
			gc := gadgetcontext.New(context.Background(), "pending-readout")
			defer gc.Cancel()
			gc.SetVar("ebpfInstance", pendingOwnerObservation{owner})
			observation, err := eventFinalizationSnapshot(gc, "request")
			if err != nil || observation == nil || observation.ReceiverJoined || observation.BufferDrained || observation.FinalCounterRead || observation.ResourcesRetired || observation.NativeReserveFailures != nil {
				t.Fatal("unjoined owner invented finality", observation, err)
			}
			var output bytes.Buffer
			var callbackMu sync.Mutex
			callbackMu.Lock()
			returned := make(chan error, 1)
			go func() { returned <- projectedRunExit(&fatal, runErr, stopErr, observation, newObservedSink(&output)) }()
			var result error
			select {
			case result = <-returned:
			case <-time.After(time.Second):
				callbackMu.Unlock()
				t.Fatal("pending return blocked behind held callback")
			}
			callbackMu.Unlock()
			if !errors.Is(result, errReadoutPending) || !errors.Is(result, context.Canceled) {
				t.Fatal("pending/readout causes lost", result)
			}
			if denial == 0 {
				if errors.Is(result, syscall.EIO) || errors.Is(result, syscall.EACCES) || errors.Is(result, syscall.EPERM) {
					t.Fatal("ordinary readout failure invented a native denial", result)
				}
			} else if !errors.Is(result, first) || !errors.Is(result, denial) {
				t.Fatal("pending return discarded original reader/permission causes", result)
			}
			if output.Len() != 0 {
				t.Fatal("summary/error row written after unjoined readout", output.String())
			}
			backend.mu.Lock()
			retired, joined := backend.retired, backend.joined
			backend.mu.Unlock()
			if retired || joined {
				t.Fatal("held callback certified joined/retired", retired, joined)
			}
		})
	}
}
