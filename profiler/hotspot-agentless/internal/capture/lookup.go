package capture

import (
	"context"
	"errors"
	"fmt"
	"go.opentelemetry.io/ebpf-profiler/libpf"
	"syscall"

	"github.com/inspektor-gadget/inspektor-gadget/pkg/symbolizer"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
	"io.mazewall/experiments/hotspot-agentless/internal/quality"
)

// Set by the reproducible build, never inferred from a rendered stack.
var GadgetPatchSHA string

func providerIdentity() model.Provider {
	return model.Provider{Name: "otel-ebpf-profiler-via-gadget", GadgetRevision: "7097db144ccd8dab15373f27ec98d2c3d22e4588", ProfilerRevision: "ef6cf29492c8a980f889052903fa2dd0c0765cc0", PatchSHA256: GadgetPatchSHA}
}

type gadgetLookup struct {
	strictEpoch bool
	execBarrier interface{ Check() error }
	symbolizer  *symbolizer.Symbolizer
	sessionID   string
	pid         uint32
	startTicks  uint64
}

func (l gadgetLookup) Lookup(ctx context.Context, ref model.StackRef) (model.StackEvidence, error) {
	if l.strictEpoch {
		if l.execBarrier == nil {
			return model.StackEvidence{}, fmt.Errorf("strict lookup missing exec barrier")
		}
		if err := l.execBarrier.Check(); err != nil {
			return model.StackEvidence{}, err
		}
		if err := ref.HotSpotIdentity.Validate(); err != nil {
			return model.StackEvidence{}, err
		}
		if ref.HotSpotIdentity.State != libpf.HotSpotEpochStable {
			if ref.HotSpotIdentity.ReadError == -int32(syscall.EPERM) || ref.HotSpotIdentity.ReadError == -int32(syscall.EACCES) || ref.HotSpotIdentity.ReadError == -int32(syscall.EIO) {
				return model.StackEvidence{}, syscall.Errno(-ref.HotSpotIdentity.ReadError)
			}
			err := &libpf.HotSpotEpochError{State: ref.HotSpotIdentity.State}
			if libpf.IsHotSpotEpochFatal(err) {
				return model.StackEvidence{}, err
			}
			e := model.Unavailable(epochReason(err), err)
			e.Provider = providerIdentity()
			e.HotSpotIdentity = model.CapturedHotSpotIdentity(ref.HotSpotIdentity)
			return e, nil
		}
	}
	if ref.SessionID != l.sessionID || ref.PID != l.pid || ref.ProcessStartTicks != l.startTicks {
		return model.StackEvidence{}, fmt.Errorf("stack reference session/process generation mismatch")
	}
	result, err := l.symbolizer.ResolveStructured(ctx, symbolizer.Task{Tgid: ref.PID, PidNumbers: []symbolizer.PidNumbers{{Pid: ref.TID}}, CorrelationID: ref.CorrelationID, EventBootTimestamp: ref.CaptureBootNS, HotSpotIdentity: ref.HotSpotIdentity})
	if l.strictEpoch {
		if barrierErr := l.execBarrier.Check(); barrierErr != nil {
			return model.StackEvidence{}, barrierErr
		}
	}
	var epochErr *libpf.HotSpotEpochError
	if errors.As(err, &epochErr) && !libpf.IsHotSpotEpochFatal(err) {
		e := model.Unavailable(epochReason(err), err)
		e.Provider = providerIdentity()
		e.Frames = model.Normalize(result.Frames)
		e.HotSpotIdentity = model.CapturedHotSpotIdentity(ref.HotSpotIdentity)
		return e, nil
	}
	if errors.Is(err, symbolizer.ErrStackUnavailable) {
		e := model.Unavailable("definition_unavailable", err)
		e.Provider = providerIdentity()
		return e, nil
	}
	if err != nil {
		return model.StackEvidence{}, err
	}
	frames := model.Normalize(result.Frames)
	c := quality.Classify(frames)
	e := model.StackEvidence{Attribution: "event_correlated", Walk: c.Walk, ScopeLookup: c.ScopeLookup, RawErrors: c.RawErrors, Frames: frames, Provider: providerIdentity()}
	if l.strictEpoch {
		if result.HotSpotValidation.State != libpf.HotSpotValidationChecked {
			return model.StackEvidence{}, fmt.Errorf("strict result without checked validation")
		}
		e.HotSpotIdentity = model.CheckedHotSpotIdentity(result.HotSpotIdentity)
	}
	return e, nil
}

func (l gadgetLookup) RecoverDefinition(ctx context.Context, ref model.StackRef) (bool, error) {
	if l.strictEpoch {
		if l.execBarrier == nil {
			return false, fmt.Errorf("strict recovery missing exec barrier")
		}
		if err := l.execBarrier.Check(); err != nil {
			return false, err
		}
	}
	if ref.SessionID != l.sessionID || ref.PID != l.pid || ref.ProcessStartTicks != l.startTicks {
		return false, fmt.Errorf("recovery reference session/process generation mismatch")
	}
	return l.symbolizer.RecoverDefinition(ctx, symbolizer.Task{Tgid: ref.PID, PidNumbers: []symbolizer.PidNumbers{{Pid: ref.TID}}, CorrelationID: ref.CorrelationID, HotSpotIdentity: ref.HotSpotIdentity})
}

func epochReason(err error) string {
	var e *libpf.HotSpotEpochError
	if errors.As(err, &e) {
		switch e.State {
		case libpf.HotSpotEpochInProgress:
			return "hotspot_epoch_in_progress"
		case libpf.HotSpotEpochChanged:
			return "hotspot_epoch_changed"
		case libpf.HotSpotEpochUnsupported:
			return "hotspot_epoch_unsupported"
		}
	}
	return "hotspot_epoch_unavailable"
}
