package capture

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"io.mazewall/experiments/hotspot-agentless/internal/accounting"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
)

func TestSuccessfulEnrichmentAccountingComplete(t *testing.T) {
	sink := &recordSink{}
	lookup := lookupFunc(func(context.Context, model.StackRef) (model.StackEvidence, error) {
		return oneFrame("pathA"), nil
	})
	opts := fixtureOptions()
	opts.Workers = 2
	opts.MaxPending = 5
	e, err := NewEnricher(context.Background(), opts, lookup, sink,
		bootClockFunc(func() (uint64, error) { return 100, nil }), nil)
	if err != nil {
		t.Fatal(err)
	}

	if err := e.Submit(fixtureEvent("event-1", 7)); err != nil {
		t.Fatal(err)
	}
	if err := e.Submit(fixtureEvent("event-2", 8)); err != nil {
		t.Fatal(err)
	}

	if err := e.Finish(); err != nil {
		t.Fatal(err)
	}

	counts, err := e.accounting()
	if err != nil {
		t.Fatalf("accounting returned error: %v", err)
	}
	proof := accounting.Evaluate(counts)
	if !proof.Complete {
		t.Fatalf("expected complete proof, got: %+v (counts: %+v)", proof, counts)
	}
	if counts.Attempted != 2 || counts.Submitted != 2 || counts.ReserveFailed != 0 || counts.Received != 2 || counts.Terminals != 2 {
		t.Fatalf("unexpected counts: %+v", counts)
	}
	if !counts.Known || !counts.Quiesced || !counts.Joined {
		t.Fatalf("expected final flags true: %+v", counts)
	}
}

func TestQueueCountAndByteLimitSkipAccounting(t *testing.T) {
	t.Run("count_limit_skip_accounting", func(t *testing.T) {
		sink := &recordSink{}
		started := make(chan struct{})
		release := make(chan struct{})
		var once sync.Once
		var releaseOnce sync.Once
		doRelease := func() { releaseOnce.Do(func() { close(release) }) }

		lookup := lookupFunc(func(ctx context.Context, ref model.StackRef) (model.StackEvidence, error) {
			once.Do(func() { close(started) })
			<-release
			return oneFrame("slowPath"), nil
		})

		opts := fixtureOptions()
		opts.Workers = 1
		opts.MaxPending = 1
		opts.MaxPendingBytes = 8192

		e, err := NewEnricher(context.Background(), opts, lookup, sink,
			bootClockFunc(func() (uint64, error) { return 100, nil }), nil)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() {
			doRelease()
			_ = e.Finish()
		})

		// First event enters worker and blocks
		if err := e.Submit(fixtureEvent("event-1", 7)); err != nil {
			t.Fatal(err)
		}
		select {
		case <-started:
		case <-time.After(5 * time.Second):
			t.Fatal("timed out waiting for worker lookup")
		}

		// Second event exceeds MaxPending -> skipped immediately
		if err := e.Submit(fixtureEvent("event-2", 8)); err != nil {
			t.Fatal(err)
		}

		doRelease()

		if err := e.Finish(); err != nil {
			t.Fatal(err)
		}

		counts, err := e.accounting()
		if err != nil {
			t.Fatalf("accounting returned error: %v", err)
		}
		proof := accounting.Evaluate(counts)
		if !proof.Complete {
			t.Fatalf("expected complete proof for count skip, got: %+v (counts: %+v)", proof, counts)
		}
		if counts.Attempted != 2 || counts.Submitted != 2 || counts.Received != 2 || counts.Terminals != 2 {
			t.Fatalf("unexpected counts: %+v", counts)
		}
		if e.Stats().Skipped != 1 || e.Stats().Resolved != 1 {
			t.Fatalf("unexpected stats: %+v", e.Stats())
		}
	})

	t.Run("byte_limit_skip_accounting", func(t *testing.T) {
		sink := &recordSink{}
		opts := fixtureOptions()
		opts.MaxPending = 10
		opts.MaxPendingBytes = 1 // Forces immediate byte limit skip

		e, err := NewEnricher(context.Background(), opts, lookupFunc(func(context.Context, model.StackRef) (model.StackEvidence, error) {
			t.Error("lookup invoked on byte-skipped event")
			return model.StackEvidence{}, nil
		}), sink, bootClockFunc(func() (uint64, error) { return 100, nil }), nil)
		if err != nil {
			t.Fatal(err)
		}

		if err := e.Submit(fixtureEvent("byte-skip-event", 7)); err != nil {
			t.Fatal(err)
		}
		if err := e.Finish(); err != nil {
			t.Fatal(err)
		}

		counts, err := e.accounting()
		if err != nil {
			t.Fatalf("accounting returned error: %v", err)
		}
		proof := accounting.Evaluate(counts)
		if !proof.Complete {
			t.Fatalf("expected complete proof for byte skip, got: %+v (counts: %+v)", proof, counts)
		}
		if counts.Attempted != 1 || counts.Submitted != 1 || counts.Received != 1 || counts.Terminals != 1 {
			t.Fatalf("unexpected counts: %+v", counts)
		}
		if e.Stats().Skipped != 1 || e.Stats().Resolved != 0 {
			t.Fatalf("unexpected stats: %+v", e.Stats())
		}
	})

}

func TestSkipPathTerminalFailureAccounting(t *testing.T) {
	t.Run("count_skip_terminal_failure", func(t *testing.T) {
		sink := &terminalFailSink{}
		started := make(chan struct{})
		release := make(chan struct{})
		var once sync.Once
		var releaseOnce sync.Once
		doRelease := func() { releaseOnce.Do(func() { close(release) }) }

		lookup := lookupFunc(func(ctx context.Context, ref model.StackRef) (model.StackEvidence, error) {
			once.Do(func() { close(started) })
			<-release
			return oneFrame("slowPath"), nil
		})

		opts := fixtureOptions()
		opts.Workers = 1
		opts.MaxPending = 1
		opts.MaxPendingBytes = 8192

		e, err := NewEnricher(context.Background(), opts, lookup, sink,
			bootClockFunc(func() (uint64, error) { return 100, nil }), nil)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() {
			doRelease()
			_ = e.Finish()
		})

		// Event 1 enters worker
		if err := e.Submit(fixtureEvent("event-1", 7)); err != nil {
			t.Fatal(err)
		}
		select {
		case <-started:
		case <-time.After(5 * time.Second):
			t.Fatal("timed out waiting for worker lookup")
		}

		// Event 2 exceeds MaxPending -> immediate skip terminal write fails in Submit
		err = e.Submit(fixtureEvent("event-2", 8))
		if err == nil {
			t.Fatal("expected skip terminal write failure on count limit, got nil")
		}

		doRelease()

		if err := e.Finish(); err == nil {
			t.Fatal("expected Finish to report terminal write failure")
		}

		counts, err := e.accounting()
		if err == nil && accounting.Evaluate(counts).Complete {
			t.Fatal("failed count skip terminal must not yield complete receipt")
		}
	})

	t.Run("byte_skip_terminal_failure", func(t *testing.T) {
		sink := &terminalFailSink{}
		opts := fixtureOptions()
		opts.MaxPending = 10
		opts.MaxPendingBytes = 1 // Any event exceeds byte limit

		e, err := NewEnricher(context.Background(), opts, lookupFunc(func(context.Context, model.StackRef) (model.StackEvidence, error) {
			return model.StackEvidence{}, nil
		}), sink, bootClockFunc(func() (uint64, error) { return 100, nil }), nil)
		if err != nil {
			t.Fatal(err)
		}

		// Event exceeds MaxPendingBytes -> immediate skip terminal write fails in Submit
		err = e.Submit(fixtureEvent("event-byte-fail", 7))
		if err == nil {
			t.Fatal("expected skip terminal write failure on byte limit, got nil")
		}

		if err := e.Finish(); err == nil {
			t.Fatal("expected Finish to report terminal write failure")
		}

		counts, err := e.accounting()
		if err == nil && accounting.Evaluate(counts).Complete {
			t.Fatal("failed byte skip terminal must not yield complete receipt")
		}
	})
}

func TestSubmitAfterCloseRejected(t *testing.T) {
	sink := &recordSink{}
	lookup := lookupFunc(func(context.Context, model.StackRef) (model.StackEvidence, error) {
		return oneFrame("pathA"), nil
	})
	e, err := NewEnricher(context.Background(), fixtureOptions(), lookup, sink,
		bootClockFunc(func() (uint64, error) { return 100, nil }), nil)
	if err != nil {
		t.Fatal(err)
	}

	if err := e.Submit(fixtureEvent("event-1", 7)); err != nil {
		t.Fatal(err)
	}
	if err := e.Finish(); err != nil {
		t.Fatal(err)
	}

	// Submit after Finish must be rejected
	if err := e.Submit(fixtureEvent("event-after-close", 8)); err == nil {
		t.Fatal("expected error submitting after close")
	}

	// Frozen receipt remains valid and reflects only the admitted event
	counts, err := e.accounting()
	if err != nil {
		t.Fatalf("accounting returned error: %v", err)
	}
	proof := accounting.Evaluate(counts)
	if !proof.Complete || counts.Attempted != 1 || counts.Submitted != 1 || counts.Received != 1 || counts.Terminals != 1 {
		t.Fatalf("frozen receipt corrupted after close submit: %+v", counts)
	}
}

func TestAccountingBeforeFinishUnavailable(t *testing.T) {
	sink := &recordSink{}
	lookup := lookupFunc(func(context.Context, model.StackRef) (model.StackEvidence, error) {
		return oneFrame("pathA"), nil
	})
	e, err := NewEnricher(context.Background(), fixtureOptions(), lookup, sink,
		bootClockFunc(func() (uint64, error) { return 100, nil }), nil)
	if err != nil {
		t.Fatal(err)
	}

	if err := e.Submit(fixtureEvent("event-1", 7)); err != nil {
		t.Fatal(err)
	}

	// Calling accounting before Finish must fail
	if _, err := e.accounting(); err == nil {
		t.Fatal("expected error calling accounting before Finish")
	}

	_ = e.Finish()
}

func TestStickyCheckedOverflow(t *testing.T) {
	sink := &recordSink{}
	lookup := lookupFunc(func(context.Context, model.StackRef) (model.StackEvidence, error) {
		return oneFrame("pathA"), nil
	})
	e, err := NewEnricher(context.Background(), fixtureOptions(), lookup, sink,
		bootClockFunc(func() (uint64, error) { return 100, nil }), nil)
	if err != nil {
		t.Fatal(err)
	}

	// Manually set counter to max to simulate overflow
	e.mu.Lock()
	e.counts.Attempted = ^uint64(0)
	e.mu.Unlock()

	// Next increment triggers sticky overflow
	_ = e.Submit(fixtureEvent("event-overflow", 7))
	_ = e.Finish()

	if _, err := e.accounting(); err == nil {
		t.Fatal("expected accounting error on sticky overflow")
	}
}

type eventFailSink struct{}

func (eventFailSink) Write(_ context.Context, r model.Record) error {
	if r.Kind == "event" {
		return errors.New("event write failed")
	}
	return nil
}

func TestEventWriteFailureAccounting(t *testing.T) {
	sink := eventFailSink{}
	lookup := lookupFunc(func(context.Context, model.StackRef) (model.StackEvidence, error) {
		return oneFrame("pathA"), nil
	})
	e, err := NewEnricher(context.Background(), fixtureOptions(), lookup, sink,
		bootClockFunc(func() (uint64, error) { return 100, nil }), nil)
	if err != nil {
		t.Fatal(err)
	}

	if err := e.Submit(fixtureEvent("fail-event", 7)); err == nil {
		t.Fatal("expected error submitting event to failing sink")
	}

	if err := e.Finish(); err == nil {
		t.Fatal("expected finish error after event write failure")
	}

	if _, err := e.accounting(); err == nil {
		t.Fatal("expected accounting error after failed event delivery")
	}
}

func TestHeldWorkerDeadlineAccounting(t *testing.T) {
	sink := &recordSink{}
	lookup := lookupFunc(func(context.Context, model.StackRef) (model.StackEvidence, error) {
		return oneFrame("pathA"), nil
	})
	opts := fixtureOptions()
	opts.Deadline = 50 * time.Millisecond

	clockValue := uint64(100)
	var clockMu sync.Mutex
	clock := bootClockFunc(func() (uint64, error) {
		clockMu.Lock()
		defer clockMu.Unlock()
		return clockValue, nil
	})

	e, err := NewEnricher(context.Background(), opts, lookup, sink, clock, nil)
	if err != nil {
		t.Fatal(err)
	}

	// Submit event at clock = 100
	ev := fixtureEvent("deadline-event", 7)
	ev.CaptureBootNS = 100

	// Advance clock past deadline before worker checks
	clockMu.Lock()
	clockValue = 100 + uint64(60*time.Millisecond)
	clockMu.Unlock()

	if err := e.Submit(ev); err != nil {
		t.Fatal(err)
	}
	if err := e.Finish(); err != nil {
		t.Fatal(err)
	}

	counts, err := e.accounting()
	if err != nil {
		t.Fatalf("accounting returned error: %v", err)
	}
	proof := accounting.Evaluate(counts)
	if !proof.Complete {
		t.Fatalf("expected complete proof, got: %+v", proof)
	}
	if counts.Terminals != counts.Received || counts.Received != 1 {
		t.Fatalf("unexpected counts: %+v", counts)
	}
	if e.Stats().Unavailable != 1 {
		t.Fatalf("expected unavailable stat on deadline: %+v", e.Stats())
	}
}

func TestSynchronizedHeldWorkerAccountingUnavailableUntilJoined(t *testing.T) {
	sink := &recordSink{}
	started := make(chan struct{})
	release := make(chan struct{})
	var once sync.Once
	var releaseOnce sync.Once
	doRelease := func() { releaseOnce.Do(func() { close(release) }) }

	lookup := lookupFunc(func(ctx context.Context, ref model.StackRef) (model.StackEvidence, error) {
		once.Do(func() { close(started) })
		<-release
		return oneFrame("pathHeld"), nil
	})

	opts := fixtureOptions()
	opts.Workers = 1
	opts.MaxPending = 5

	e, err := NewEnricher(context.Background(), opts, lookup, sink,
		bootClockFunc(func() (uint64, error) { return 100, nil }), nil)
	if err != nil {
		t.Fatal(err)
	}

	finishDone := make(chan struct{})
	var finishErr error
	var finishStarted atomic.Bool

	t.Cleanup(func() {
		doRelease()
		if finishStarted.Load() {
			select {
			case <-finishDone:
			case <-time.After(5 * time.Second):
				t.Error("timed out waiting for background Finish to complete during cleanup")
			}
		} else {
			_ = e.Finish()
		}
	})

	if err := e.Submit(fixtureEvent("held-event", 7)); err != nil {
		t.Fatal(err)
	}

	select {
	case <-started:
	case <-time.After(5 * time.Second):
		t.Fatal("timed out waiting for worker to start lookup")
	}

	// Start Finish concurrently while worker is held
	finishStarted.Store(true)
	go func() {
		finishErr = e.Finish()
		close(finishDone)
	}()

	// Wait until Finish has entered its closing phase (e.closed == true under e.mu) while worker remains held
	deadline := time.Now().Add(5 * time.Second)
	enteredClose := false
	for time.Now().Before(deadline) {
		e.mu.Lock()
		c := e.closed
		e.mu.Unlock()
		if c {
			enteredClose = true
			break
		}
		time.Sleep(100 * time.Microsecond)
	}
	if !enteredClose {
		t.Fatal("timed out waiting for Finish to enter closing phase while worker held")
	}

	// While worker is held, Finish has entered closed state but cannot join worker, so accounting() MUST be unavailable
	if _, err := e.accounting(); err == nil {
		t.Fatal("accounting() succeeded while worker was held and Finish in flight")
	}

	select {
	case <-finishDone:
		t.Fatalf("Finish returned prematurely while worker held: %v", finishErr)
	default:
		// Expected: Finish is blocked waiting for worker to join
	}

	// Release worker
	doRelease()

	// Now Finish must complete cleanly
	select {
	case <-finishDone:
		if finishErr != nil {
			t.Fatalf("Finish returned error: %v", finishErr)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("timed out waiting for Finish to join released worker")
	}

	// After clean Finish, accounting must return complete proof
	counts, err := e.accounting()
	if err != nil {
		t.Fatalf("accounting returned error: %v", err)
	}
	proof := accounting.Evaluate(counts)
	if !proof.Complete {
		t.Fatalf("expected complete proof, got: %+v (counts: %+v)", proof, counts)
	}
	if !counts.Known || !counts.Quiesced || !counts.Joined {
		t.Fatalf("expected final flags true: %+v", counts)
	}
	if counts.Received != 1 || counts.Terminals != 1 || counts.Attempted != 1 || counts.Submitted != 1 {
		t.Fatalf("unexpected counts: %+v", counts)
	}
}
