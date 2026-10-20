package model

import (
	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"go.opentelemetry.io/ebpf-profiler/libpf"
	"io.mazewall/experiments/hotspot-agentless/internal/identity"
)

type HotSpotIdentity = wire.HotSpotIdentity

func CapturedHotSpotIdentity(c libpf.HotSpotIdentity) *HotSpotIdentity {
	if c.State == libpf.HotSpotEpochUnknown {
		return nil
	}
	names := []string{"unknown", "stable", "read_error", "in_progress", "changed", "unsupported", "regressed"}
	name := "invalid"
	if uint32(c.State) < uint32(len(names)) {
		name = names[c.State]
	}
	i := &HotSpotIdentity{State: name, Profile: identity.ProfileName}
	if c.State == libpf.HotSpotEpochStable {
		e := c.Epoch
		i.Epoch = &e
	}
	if c.ReadError != 0 {
		r := c.ReadError
		i.ReadError = &r
	}
	return i
}
func CheckedHotSpotIdentity(c libpf.HotSpotIdentity) *HotSpotIdentity {
	i := CapturedHotSpotIdentity(c)
	if i != nil && c.State == libpf.HotSpotEpochStable {
		i.State = "safepoint_epoch_checked"
	}
	return i
}
func captureHotSpotIdentity(i *HotSpotIdentity) libpf.HotSpotIdentity {
	if i == nil {
		return libpf.HotSpotIdentity{}
	}
	s := libpf.HotSpotEpochState(255)
	switch i.State {
	case "unknown":
		s = libpf.HotSpotEpochUnknown
	case "stable":
		if i.Epoch != nil {
			s = libpf.HotSpotEpochStable
		}
	case "read_error":
		s = libpf.HotSpotEpochReadError
	case "in_progress":
		s = libpf.HotSpotEpochInProgress
	case "changed":
		s = libpf.HotSpotEpochChanged
	case "unsupported":
		s = libpf.HotSpotEpochUnsupported
	case "regressed":
		s = libpf.HotSpotEpochRegressed
	}
	c := libpf.HotSpotIdentity{State: s}
	if i.Epoch != nil {
		c.Epoch = *i.Epoch
	}
	if i.ReadError != nil {
		c.ReadError = *i.ReadError
	}
	return c
}
