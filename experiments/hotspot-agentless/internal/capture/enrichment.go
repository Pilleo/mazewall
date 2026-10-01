package capture

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/cilium/ebpf"
	"sync"
	"time"

	"golang.org/x/sys/unix"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
)

type StackLookup interface {
	Lookup(context.Context, model.StackRef) (model.StackEvidence, error)
}
type ReferenceRecovery interface {
	RecoverDefinition(context.Context, model.StackRef) (bool, error)
}
type BootClock interface{ Now() (uint64, error) }
type LinuxBootClock struct{}

func (LinuxBootClock) Now() (uint64, error) {
	var ts unix.Timespec
	if err := unix.ClockGettime(unix.CLOCK_BOOTTIME, &ts); err != nil {
		return 0, err
	}
	return uint64(ts.Sec)*1_000_000_000 + uint64(ts.Nsec), nil
}

type EnrichmentOptions struct {
	Workers, MaxPending, MaxPendingBytes int
	Deadline                             time.Duration
}

func DefaultEnrichmentOptions() EnrichmentOptions {
	return EnrichmentOptions{Workers: 4, MaxPending: 1024, MaxPendingBytes: 16 * 1024 * 1024, Deadline: time.Second}
}

type pendingEvent struct {
	event model.Event
	bytes int
}
type Enricher struct {
	ctx            context.Context
	opts           EnrichmentOptions
	lookup         StackLookup
	sink           model.Sink
	clock          BootClock
	onFatal        context.CancelFunc
	jobs           chan pendingEvent
	wg             sync.WaitGroup
	mu             sync.Mutex
	closed         bool
	err            error
	pending, bytes int
	stats          model.Stats
}

func NewEnricher(ctx context.Context, opts EnrichmentOptions, lookup StackLookup, sink model.Sink, clock BootClock, onFatal context.CancelFunc) (*Enricher, error) {
	if opts.Workers <= 0 || opts.MaxPending <= 0 || opts.MaxPendingBytes <= 0 || opts.Deadline <= 0 || opts.Deadline > time.Hour {
		return nil, fmt.Errorf("invalid enrichment limits")
	}
	if lookup == nil || sink == nil || clock == nil {
		return nil, fmt.Errorf("enrichment dependencies are required")
	}
	e := &Enricher{ctx: ctx, opts: opts, lookup: lookup, sink: sink, clock: clock, onFatal: onFatal, jobs: make(chan pendingEvent, opts.MaxPending)}
	for i := 0; i < opts.Workers; i++ {
		e.wg.Add(1)
		go e.worker()
	}
	return e, nil
}

// Submit never waits for a stack lookup. Pending limits include active workers.
// Sink writes may apply output backpressure; output failure is session-fatal.
func (e *Enricher) Submit(event model.Event) error {
	encoded, err := json.Marshal(event)
	if err != nil {
		return err
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.closed {
		return fmt.Errorf("enrichment input closed")
	}
	if e.err != nil {
		return e.err
	}
	e.stats.Received++
	if err := e.write(model.Record{Kind: "event", SessionID: event.SessionID, EventID: event.EventID, Event: &event}); err != nil {
		return err
	}
	reason := ""
	if e.pending >= e.opts.MaxPending {
		reason = "pending_count_limit"
	} else if len(encoded) > e.opts.MaxPendingBytes-e.bytes {
		reason = "pending_byte_limit"
	}
	if reason != "" {
		e.stats.Skipped++
		evidence := model.Unavailable(reason, nil)
		return e.write(model.Record{Kind: "enrichment", SessionID: event.SessionID, EventID: event.EventID, Evidence: &evidence})
	}
	e.pending++
	e.bytes += len(encoded)
	if e.pending > e.stats.MaxPending {
		e.stats.MaxPending = e.pending
	}
	if e.bytes > e.stats.MaxPendingBytes {
		e.stats.MaxPendingBytes = e.bytes
	}
	e.jobs <- pendingEvent{event: event, bytes: len(encoded)}
	return nil
}

func (e *Enricher) write(record model.Record) error {
	record.SchemaVersion = 1
	// Capture cancellation must not erase terminal cancellation records.
	if err := e.sink.Write(context.WithoutCancel(e.ctx), record); err != nil {
		if e.err == nil {
			e.err = err
			if e.onFatal != nil {
				e.onFatal()
			}
		}
		return err
	}
	return nil
}

func (e *Enricher) resolve(event model.Event) (model.StackEvidence, error) {
	if err := e.ctx.Err(); err != nil {
		return model.Unavailable("cancelled", err), nil
	}
	if event.CorrelationID == 0 {
		return model.Unavailable("missing_reference", nil), nil
	}
	now, err := e.clock.Now()
	if err != nil {
		return model.Unavailable("clock_error", err), err
	}
	if now < event.CaptureBootNS {
		return model.Unavailable("clock_anomaly", nil), nil
	}
	age := now - event.CaptureBootNS
	if age >= uint64(e.opts.Deadline) {
		return model.Unavailable("deadline_exceeded", nil), nil
	}
	ctx, cancel := context.WithTimeout(e.ctx, e.opts.Deadline-time.Duration(age))
	defer cancel()
	evidence, err := e.lookup.Lookup(ctx, event.StackRef())
	if err == nil {
		err = ctx.Err()
	}
	if err != nil {
		reason := "lookup_error"
		if errors.Is(err, context.DeadlineExceeded) {
			reason = "deadline_exceeded"
		} else if errors.Is(err, context.Canceled) {
			reason = "cancelled"
		}
		return model.Unavailable(reason, err), err
	}
	// BOOTTIME advances during suspend even when the Go monotonic timeout does
	// not. Recheck the original clock before accepting a returned definition.
	now, err = e.clock.Now()
	if err != nil {
		return model.Unavailable("clock_error", err), err
	}
	if now < event.CaptureBootNS {
		return model.Unavailable("clock_anomaly", nil), nil
	}
	if now-event.CaptureBootNS >= uint64(e.opts.Deadline) {
		return model.Unavailable("deadline_exceeded", nil), nil
	}
	if evidence.Attribution == "event_correlated" && len(evidence.Frames) == 0 {
		return model.Unavailable("empty_definition", nil), nil
	}
	return evidence, nil
}

func (e *Enricher) worker() {
	defer e.wg.Done()
	for job := range e.jobs {
		evidence, err := e.resolve(job.event)
		e.mu.Lock()
		if evidence.Attribution == "event_correlated" {
			e.stats.Resolved++
		} else {
			e.stats.Unavailable++
		}
		writeErr := e.write(model.Record{Kind: "enrichment", SessionID: job.event.SessionID, EventID: job.event.EventID, Evidence: &evidence})
		if err != nil && !errors.Is(err, context.DeadlineExceeded) && !errors.Is(err, context.Canceled) {
			if e.err == nil {
				e.err = err
				if e.onFatal != nil {
					e.onFatal()
				}
			}
		}
		recoverMissing := writeErr == nil && e.err == nil && e.ctx.Err() == nil && (evidence.Reason == "definition_unavailable" || evidence.Reason == "deadline_exceeded")
		e.mu.Unlock()
		if recoverMissing {
			e.recover(job.event)
		}
		e.mu.Lock()
		e.pending--
		e.bytes -= job.bytes
		e.mu.Unlock()
	}
}

func (e *Enricher) recover(event model.Event) {
	recovery, ok := e.lookup.(ReferenceRecovery)
	if !ok || event.CorrelationID == 0 || e.ctx.Err() != nil {
		return
	}
	// Reuse the fixed enrichment workers. No unbounded recovery goroutines,
	// queues or second correlation cache are introduced.
	ctx, cancel := context.WithTimeout(e.ctx, 50*time.Millisecond)
	invalidated, err := recovery.RecoverDefinition(ctx, event.StackRef())
	cancel()
	now, clockErr := e.clock.Now()
	err = errors.Join(err, clockErr)
	action := model.RecoveryResult{CorrelationID: event.CorrelationID, Invalidated: invalidated, BootNS: now}
	if err != nil {
		action.Error = err.Error()
	}
	e.mu.Lock()
	defer e.mu.Unlock()
	e.stats.RecoveryAttempts++
	if invalidated {
		e.stats.RecoveryInvalidations++
	}
	if err != nil {
		e.stats.RecoveryErrors++
	}
	_ = e.write(model.Record{Kind: "recovery", SessionID: event.SessionID, EventID: event.EventID, Recovery: &action})
	// A concurrent LRU iteration abort or expiry is an explicit unsuccessful
	// attempt. Native permission and unexpected recovery failures stay fatal.
	fatal := clockErr != nil || errors.Is(err, unix.EPERM) || errors.Is(err, unix.EACCES) || (err != nil && !errors.Is(err, context.Canceled) && !errors.Is(err, context.DeadlineExceeded) && !errors.Is(err, ebpf.ErrIterationAborted))
	if fatal && e.err == nil {
		e.err = err
		if e.onFatal != nil {
			e.onFatal()
		}
	}
}

func (e *Enricher) Finish() error {
	e.mu.Lock()
	if !e.closed {
		e.closed = true
		close(e.jobs)
	}
	e.mu.Unlock()
	e.wg.Wait()
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.err
}
func (e *Enricher) Stats() model.Stats { e.mu.Lock(); defer e.mu.Unlock(); return e.stats }
