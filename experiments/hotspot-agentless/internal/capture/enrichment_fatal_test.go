package capture

import (
	"context"
	"errors"
	"sync/atomic"
	"syscall"
	"testing"
	"time"

	"io.mazewall/experiments/hotspot-agentless/internal/identity"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
)

func TestEnrichmentPermissionIsFatalEvenWhenJoinedWithCancellation(t *testing.T) {
	for _, denial := range []syscall.Errno{syscall.EPERM, syscall.EACCES} {
		var cancelled atomic.Int32
		cause := errors.Join(context.Canceled, denial)
		e, err := NewEnricher(context.Background(), fixtureOptions(), lookupFunc(func(context.Context, model.StackRef) (model.StackEvidence, error) {
			return model.Unavailable("lookup_error", cause), cause
		}), &recordSink{}, bootClockFunc(func() (uint64, error) { return 100, nil }), func() { cancelled.Add(1) })
		if err != nil {
			t.Fatal(err)
		}
		if err := e.Submit(fixtureEvent("denied", 7)); err != nil {
			t.Fatal(err)
		}
		if err := e.Finish(); !errors.Is(err, denial) || cancelled.Load() != 1 {
			t.Fatal("permission denial was downgraded to ordinary cancellation", err, cancelled.Load())
		}
	}
}

type heldFatalTerminal struct{ entered, release chan struct{} }

func (s *heldFatalTerminal) Write(_ context.Context, r model.Record) error {
	if r.Kind == "enrichment" {
		close(s.entered)
		<-s.release
	}
	return nil
}

func TestEnrichmentFatalCauseSurvivesHeldTerminalBeforeFinish(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	fatal := &fatalState{cancel: cancel}
	sink := &heldFatalTerminal{entered: make(chan struct{}), release: make(chan struct{})}
	e, err := newEnricher(ctx, fixtureOptions(), lookupFunc(func(context.Context, model.StackRef) (model.StackEvidence, error) {
		return model.Unavailable("lookup_error", identity.ErrTargetExec), identity.ErrTargetExec
	}), sink, bootClockFunc(func() (uint64, error) { return 100, nil }), nil, fatal.Record)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		close(sink.release)
		if err := e.Finish(); !errors.Is(err, identity.ErrTargetExec) {
			t.Error("original lookup cause lost", err)
		}
	}()
	if err := e.Submit(fixtureEvent("failed", 7)); err != nil {
		t.Fatal(err)
	}
	select {
	case <-sink.entered:
	case <-time.After(time.Second):
		t.Fatal("terminal writer did not start")
	}
	if !errors.Is(fatal.Err(), identity.ErrTargetExec) || ctx.Err() == nil {
		t.Fatal("original cause depends on a blocked terminal write or Finish", fatal.Err(), ctx.Err())
	}
}
