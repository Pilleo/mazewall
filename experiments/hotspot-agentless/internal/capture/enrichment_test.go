package capture

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"io.mazewall/experiments/hotspot-agentless/internal/model"
)

type recordSink struct {
	mu      sync.Mutex
	records []model.Record
	fail    bool
}

func (s *recordSink) Write(ctx context.Context, r model.Record) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.fail {
		return errors.New("disk full")
	}
	s.records = append(s.records, r)
	return nil
}
func (s *recordSink) snapshot() []model.Record {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]model.Record(nil), s.records...)
}

type lookupFunc func(context.Context, model.StackRef) (model.StackEvidence, error)

func (f lookupFunc) Lookup(ctx context.Context, ref model.StackRef) (model.StackEvidence, error) {
	return f(ctx, ref)
}

type bootClockFunc func() (uint64, error)

func (f bootClockFunc) Now() (uint64, error) { return f() }
func fixtureEvent(id string, ref uint64) model.Event {
	return model.Event{SessionID: "session-A", EventID: id, PID: 123, TID: 456, ProcessStartTicks: 987, CorrelationID: ref, CaptureBootNS: 100, ClockDomain: "CLOCK_BOOTTIME"}
}
func fixtureOptions() EnrichmentOptions {
	return EnrichmentOptions{Workers: 1, MaxPending: 1, MaxPendingBytes: 8192, Deadline: 3 * time.Second}
}
func oneFrame(name string) model.StackEvidence {
	return model.StackEvidence{Attribution: "event_correlated", Walk: "returned", ScopeLookup: "unknown", Frames: []model.Frame{{FunctionName: name}}}
}

func TestPendingLimitDoesNotBlockConsumptionOnLookup(t *testing.T) {
	ctx := context.Background()
	started := make(chan struct{})
	release := make(chan struct{})
	lookup := lookupFunc(func(ctx context.Context, ref model.StackRef) (model.StackEvidence, error) {
		close(started)
		select {
		case <-release:
			return oneFrame("pathA"), nil
		case <-ctx.Done():
			return model.StackEvidence{}, ctx.Err()
		}
	})
	sink := &recordSink{}
	e, err := NewEnricher(ctx, fixtureOptions(), lookup, sink, bootClockFunc(func() (uint64, error) { return 100, nil }), nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := e.Submit(fixtureEvent("event-1", 7)); err != nil {
		t.Fatal(err)
	}
	<-started
	done := make(chan error, 1)
	go func() { done <- e.Submit(fixtureEvent("event-2", 7)) }()
	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("consumption blocked on resolver")
	}
	close(release)
	if err := e.Finish(); err != nil {
		t.Fatal(err)
	}
	var skipped, resolved int
	for _, r := range sink.snapshot() {
		if r.Kind == "enrichment" && r.EventID == "event-2" && r.Evidence.Reason == "pending_count_limit" {
			skipped++
		}
		if r.Kind == "enrichment" && r.EventID == "event-1" && r.Evidence.Attribution == "event_correlated" {
			resolved++
		}
	}
	if skipped != 1 || resolved != 1 {
		t.Fatalf("skipped=%d resolved=%d", skipped, resolved)
	}
	if e.Stats().MaxPending != 1 || e.Stats().Received != 2 {
		t.Fatalf("bad accounting: %+v", e.Stats())
	}
}

func TestPastDeadlineAndAbsentReferenceNeverInvokeLookup(t *testing.T) {
	called := false
	lookup := lookupFunc(func(context.Context, model.StackRef) (model.StackEvidence, error) {
		called = true
		return oneFrame("wrong"), nil
	})
	sink := &recordSink{}
	opts := fixtureOptions()
	opts.MaxPending = 2
	e, err := NewEnricher(context.Background(), opts, lookup, sink, bootClockFunc(func() (uint64, error) { return 4_000_000_100, nil }), nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := e.Submit(fixtureEvent("old", 7)); err != nil {
		t.Fatal(err)
	}
	if err := e.Submit(fixtureEvent("absent", 0)); err != nil {
		t.Fatal(err)
	}
	if err := e.Finish(); err != nil {
		t.Fatal(err)
	}
	if called {
		t.Fatal("old or absent event requested another stack")
	}
	if e.Stats().Unavailable != 2 {
		t.Fatalf("bad accounting: %+v", e.Stats())
	}
}

func TestPendingByteLimitKeepsEventAndTerminalReason(t *testing.T) {
	opts := fixtureOptions()
	opts.MaxPendingBytes = 1
	sink := &recordSink{}
	e, err := NewEnricher(context.Background(), opts, lookupFunc(func(context.Context, model.StackRef) (model.StackEvidence, error) {
		t.Error("lookup on rejected event")
		return model.StackEvidence{}, nil
	}), sink, bootClockFunc(func() (uint64, error) { return 100, nil }), nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := e.Submit(fixtureEvent("large", 7)); err != nil {
		t.Fatal(err)
	}
	if err := e.Finish(); err != nil {
		t.Fatal(err)
	}
	rows := sink.snapshot()
	if len(rows) != 2 || rows[0].Kind != "event" || rows[1].Evidence.Reason != "pending_byte_limit" {
		t.Fatalf("lost refusal evidence: %+v", rows)
	}
}

func TestOutputFailureIsFatalAndNotReportedAsSuccessfulEnrichment(t *testing.T) {
	sink := &recordSink{fail: true}
	fatal := make(chan struct{}, 1)
	e, err := NewEnricher(context.Background(), fixtureOptions(), lookupFunc(func(context.Context, model.StackRef) (model.StackEvidence, error) { return oneFrame("pathA"), nil }), sink, bootClockFunc(func() (uint64, error) { return 100, nil }), func() { fatal <- struct{}{} })
	if err != nil {
		t.Fatal(err)
	}
	if err := e.Submit(fixtureEvent("fail", 7)); err == nil {
		t.Fatal("output error swallowed")
	}
	if err := e.Finish(); err == nil {
		t.Fatal("session declared successful")
	}
	select {
	case <-fatal:
	default:
		t.Fatal("collector was not cancelled")
	}
}

func TestBootDeadlineIsRecheckedAfterLookup(t *testing.T) {
	var clock atomic.Uint64
	clock.Store(100)
	sink := &recordSink{}
	lookup := lookupFunc(func(context.Context, model.StackRef) (model.StackEvidence, error) {
		// CLOCK_BOOTTIME includes suspend, unlike Go's monotonic timer budget.
		clock.Store(4_000_000_100)
		return oneFrame("tooLate"), nil
	})
	e, err := NewEnricher(context.Background(), fixtureOptions(), lookup, sink, bootClockFunc(func() (uint64, error) { return clock.Load(), nil }), nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := e.Submit(fixtureEvent("expired", 7)); err != nil {
		t.Fatal(err)
	}
	if err := e.Finish(); err != nil {
		t.Fatal(err)
	}
	rows := sink.snapshot()
	if len(rows) != 2 || rows[1].Evidence.Reason != "deadline_exceeded" || len(rows[1].Evidence.Frames) != 0 {
		t.Fatalf("late frames attached: %+v", rows)
	}
}

func TestCancellationProducesOneTerminalOutcome(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	started := make(chan struct{})
	sink := &recordSink{}
	lookup := lookupFunc(func(ctx context.Context, ref model.StackRef) (model.StackEvidence, error) {
		close(started)
		<-ctx.Done()
		return model.StackEvidence{}, ctx.Err()
	})
	e, err := NewEnricher(ctx, fixtureOptions(), lookup, sink, bootClockFunc(func() (uint64, error) { return 100, nil }), nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := e.Submit(fixtureEvent("cancelled", 7)); err != nil {
		t.Fatal(err)
	}
	<-started
	cancel()
	if err := e.Finish(); err != nil {
		t.Fatal(err)
	}
	rows := sink.snapshot()
	if len(rows) != 2 || rows[1].Evidence.Reason != "cancelled" || e.Stats().Unavailable != 1 {
		t.Fatalf("terminal cancellation lost: %+v", rows)
	}
}

func TestRepeatedReferencesRemainDistinctOccurrences(t *testing.T) {
	opts := fixtureOptions()
	opts.MaxPending = 3
	opts.Workers = 2
	sink := &recordSink{}
	lookup := lookupFunc(func(ctx context.Context, ref model.StackRef) (model.StackEvidence, error) {
		if ref.CorrelationID == 7 {
			return oneFrame("pathA"), nil
		}
		return oneFrame("pathB"), nil
	})
	e, err := NewEnricher(context.Background(), opts, lookup, sink, bootClockFunc(func() (uint64, error) { return 100, nil }), nil)
	if err != nil {
		t.Fatal(err)
	}
	for _, event := range []model.Event{fixtureEvent("A-1", 7), fixtureEvent("B-1", 8), fixtureEvent("A-2", 7)} {
		if err := e.Submit(event); err != nil {
			t.Fatal(err)
		}
	}
	if err := e.Finish(); err != nil {
		t.Fatal(err)
	}
	seen := map[string]string{}
	for _, r := range sink.snapshot() {
		if r.Kind == "enrichment" {
			if _, ok := seen[r.EventID]; ok {
				t.Fatal("duplicate terminal")
			}
			seen[r.EventID] = r.Evidence.Frames[0].FunctionName
		}
	}
	if seen["A-1"] != "pathA" || seen["A-2"] != "pathA" || seen["B-1"] != "pathB" {
		t.Fatalf("cross-event substitution: %v", seen)
	}
}

func TestLateDefinitionDoesNotRewriteAnUnavailableOccurrence(t *testing.T) {
	var available atomic.Bool
	sink := &recordSink{}
	lookup := lookupFunc(func(context.Context, model.StackRef) (model.StackEvidence, error) {
		if !available.Load() {
			return model.Unavailable("definition_unavailable", nil), nil
		}
		return oneFrame("pathA"), nil
	})
	e, err := NewEnricher(context.Background(), fixtureOptions(), lookup, sink, bootClockFunc(func() (uint64, error) { return 100, nil }), nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := e.Submit(fixtureEvent("original", 7)); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(time.Second)
	for e.Stats().Unavailable != 1 {
		if time.Now().After(deadline) {
			t.Fatal("original did not become terminal")
		}
		time.Sleep(time.Millisecond)
	}
	available.Store(true)
	if err := e.Submit(fixtureEvent("subsequent", 7)); err != nil {
		t.Fatal(err)
	}
	if err := e.Finish(); err != nil {
		t.Fatal(err)
	}
	var first, second *model.StackEvidence
	for _, r := range sink.snapshot() {
		if r.Kind == "enrichment" {
			if r.EventID == "original" {
				first = r.Evidence
			} else if r.EventID == "subsequent" {
				second = r.Evidence
			}
		}
	}
	if first == nil || first.Attribution != "unavailable" || len(first.Frames) != 0 || second == nil || second.Attribution != "event_correlated" {
		t.Fatal("late definition changed original evidence or did not resolve subsequent event")
	}
}
