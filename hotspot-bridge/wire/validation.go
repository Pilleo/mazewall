package wire

import (
	"fmt"
	"math/bits"
	"strings"
)

func terminalCount(s Stats) (uint64, bool) {
	n, c := bits.Add64(s.Resolved, s.Unavailable, 0)
	n, last := bits.Add64(n, s.Skipped, 0)
	return n, c == 0 && last == 0
}

func (d *Decoder) validateSummary(s Summary) error {
	f := s.EventFinalization
	stop := s.DefinitionStop
	if f != nil && f.Error != "" {
		return fmt.Errorf("%w: %s", ErrFinality, f.Error)
	}
	if f == nil || f.Scope != "owned_openat_event_ring" || f.Phase != "resources_retired" || f.StopCause != "request" || !f.ProducersQuiesced || !f.BufferDrained || !f.ReceiverJoined || !f.FinalCounterRead || !f.ResourcesRetired || f.Error != "" || f.BackgroundLossSamplerStarted || f.NativeReserveFailures == nil || stop == nil || !stop.ReaderStopped || !stop.ConsumerStopped || !stop.BuffersDrained || !stop.AuxiliaryStopped || stop.Semantics != "owned_definition_perf_drained_partial_loss_snapshot" {
		return fmt.Errorf("%w: physical owner flags/scope", ErrFinality)
	}
	if len(s.Errors) != 0 {
		return fmt.Errorf("%w: %s", ErrCollector, strings.Join(s.Errors, "; "))
	}
	n, ok := terminalCount(s.Stats)
	if !ok || n != s.Stats.Received || !s.TerminalAccountingComplete || s.Stats.Received != d.counts.Received || s.Stats.Resolved != d.counts.Resolved || s.Stats.Unavailable != d.counts.Unavailable || s.Stats.Skipped != d.counts.Skipped || s.Stats.RecoveryAttempts != d.counts.RecoveryAttempts || s.Stats.RecoveryInvalidations != d.counts.RecoveryInvalidations || s.Stats.RecoveryErrors != d.counts.RecoveryErrors || s.Stats.MaxPending < 0 || s.Stats.MaxPending > 1024 || s.Stats.MaxPendingBytes < 0 || s.Stats.MaxPendingBytes > 16777216 {
		return fmt.Errorf("%w: terminal/recovery counts", ErrAccounting)
	}
	a := s.SelectedAccounting
	// The wire has an aggregate only. Native validates individual CPU rows before
	// checked aggregation; these equations cannot independently replay that proof.
	if a == nil || a.SchemaVersion != 1 || a.Scope != "owned_openat_handler_entry" || a.SessionID != d.session || a.PID != d.expected.Target.PID || a.Birth != d.expected.Target.StartTicks || a.MapID == 0 || a.PossibleCPUs <= 0 || !a.Complete || a.Reason != "" || a.Submitted > a.Attempted || a.ReserveFailed != a.Attempted-a.Submitted || a.Received > a.Submitted || a.Received != s.Stats.Received || a.Terminals != n {
		return fmt.Errorf("%w: selected aggregate/binding", ErrAccounting)
	}
	if *f.NativeReserveFailures != a.ReserveFailed {
		return fmt.Errorf("%w: reserve failure observations", ErrAccounting)
	}
	if loss := s.EventLossObservation; loss == nil || loss.Observed == nil || *loss.Observed != *f.NativeReserveFailures || loss.Semantics != "quiescent_native_reserve_failures_partial_snapshot" {
		return fmt.Errorf("%w: final native loss observation", ErrAccounting)
	}
	for _, o := range d.seen {
		if uint64(o.cpu) >= uint64(a.PossibleCPUs) {
			return fmt.Errorf("%w: event CPU outside possible cardinality", ErrAccounting)
		}
		if !o.terminal {
			return fmt.Errorf("%w: missing terminal", ErrAccounting)
		}
	}
	if f.LossComplete || s.CollectionHealth != "partial" && s.CollectionHealth != "unknown" || s.EventLoss != nil || s.DefinitionLoss != nil || s.EventLossObservation != nil && s.EventLossObservation.Complete || s.DefinitionReaderObservation != nil && s.DefinitionReaderObservation.Complete {
		return fmt.Errorf("%w: unsupported global loss claim", ErrAccounting)
	}
	lanes := map[Lane]bool{}
	for _, lane := range s.UnprovenCollectionSources {
		switch lane {
		case Definitions, Enrichment, Wire, Retention:
		default:
			return fmt.Errorf("%w: unproven lane %q", ErrAccounting, lane)
		}
		if lanes[lane] {
			return fmt.Errorf("%w: duplicate lane", ErrAccounting)
		}
		lanes[lane] = true
	}
	if len(lanes) != 4 {
		return fmt.Errorf("%w: unknown global lanes omitted", ErrAccounting)
	}
	return nil
}
