package capture

import (
	"context"
	"errors"
	"fmt"

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
	symbolizer *symbolizer.Symbolizer
	sessionID  string
	pid        uint32
	startTicks uint64
}

func (l gadgetLookup) Lookup(ctx context.Context, ref model.StackRef) (model.StackEvidence, error) {
	if ref.SessionID != l.sessionID || ref.PID != l.pid || ref.ProcessStartTicks != l.startTicks {
		return model.StackEvidence{}, fmt.Errorf("stack reference session/process generation mismatch")
	}
	result, err := l.symbolizer.ResolveStructured(ctx, symbolizer.Task{Tgid: ref.PID, PidNumbers: []symbolizer.PidNumbers{{Pid: ref.TID}}, CorrelationID: ref.CorrelationID, EventBootTimestamp: ref.CaptureBootNS})
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
	return model.StackEvidence{Attribution: "event_correlated", Walk: c.Walk, ScopeLookup: c.ScopeLookup, RawErrors: c.RawErrors, Frames: frames, Provider: providerIdentity()}, nil
}

func (l gadgetLookup) RecoverDefinition(ctx context.Context, ref model.StackRef) (bool, error) {
	if ref.SessionID != l.sessionID || ref.PID != l.pid || ref.ProcessStartTicks != l.startTicks {
		return false, fmt.Errorf("recovery reference session/process generation mismatch")
	}
	return l.symbolizer.RecoverDefinition(ctx, symbolizer.Task{Tgid: ref.PID, PidNumbers: []symbolizer.PidNumbers{{Pid: ref.TID}}, CorrelationID: ref.CorrelationID})
}
