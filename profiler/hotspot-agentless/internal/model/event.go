package model

import (
	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"go.opentelemetry.io/ebpf-profiler/libpf"
)

// Event is an occurrence at the selected handler entry, not an open result.
// A defined runtime type keeps StackRef here; external aliases cannot own methods.
type Event wire.Event

type StackRef struct {
	HotSpotIdentity   libpf.HotSpotIdentity
	SessionID         string
	PID, TID          uint32
	ProcessStartTicks uint64
	CorrelationID     uint64
	CaptureBootNS     uint64
}

func (e Event) StackRef() StackRef {
	return StackRef{HotSpotIdentity: captureHotSpotIdentity(e.HotSpotIdentity), SessionID: e.SessionID, PID: e.PID, TID: e.TID, ProcessStartTicks: e.ProcessStartTicks, CorrelationID: e.CorrelationID, CaptureBootNS: e.CaptureBootNS}
}
