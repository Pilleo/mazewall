package capture

import (
	"context"
	"fmt"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/operators"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
)

func eventDeliveryContext(ctx context.Context, cfg Config) context.Context {
	if cfg.EventsOnly && cfg.DrainEvents {
		return context.WithoutCancel(ctx)
	}
	return ctx
}

type eventFinalizationObserver interface {
	EventFinalization() (operators.EventFinalizationObservation, bool)
}

func eventFinalizationSnapshot(gc operators.GadgetContext, stopCause string) (*model.EventFinalizationObservation, error) {
	value, ok := gc.GetVar("ebpfInstance")
	if !ok {
		return nil, fmt.Errorf("owned event finalization instance missing")
	}
	observer, ok := value.(eventFinalizationObserver)
	if !ok {
		return nil, fmt.Errorf("owned event finalization capability missing")
	}
	r, active := observer.EventFinalization()
	if !active {
		return nil, fmt.Errorf("owned event finalization was not activated")
	}
	o := &model.EventFinalizationObservation{Scope: r.Scope, Phase: r.Phase, StopCause: stopCause, ProducersQuiesced: r.ProducersQuiesced, BufferDrained: r.BufferDrained, ReceiverJoined: r.ReceiverJoined, FinalCounterRead: r.FinalCounterRead, NativeReserveFailures: r.NativeReserveFailures, BackgroundLossSamplerStarted: r.BackgroundLossSamplerStarted, ResourcesRetired: r.ResourcesRetired, LossComplete: r.LossComplete, Error: r.Error}
	return o, nil
}
