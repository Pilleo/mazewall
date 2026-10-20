package main

import (
	"flag"
	"fmt"
	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"io"
	"io.mazewall/experiments/hotspot-agentless/internal/capture"
	"time"
)

type Request struct {
	Protocol                        int
	Profile                         wire.Profile
	Target                          wire.Target
	Duration                        time.Duration
	StrictEpoch, DrainFull, Control bool
	preflight, debugRendered        bool
	config                          capture.Config
}

// Parsing is pure: no target inspection, signal registration, pipes or native owners.
func parseCaptureRequest(args []string) (Request, error) {
	var r Request
	f := flag.NewFlagSet("mw-hotspot-collector", flag.ContinueOnError)
	f.SetOutput(io.Discard)
	f.IntVar(&r.config.PID, "pid", 0, "target host PID")
	f.BoolVar(&r.preflight, "preflight", false, "inspect environment")
	capturing := f.Bool("capture", false, "capture")
	f.BoolVar(&r.debugRendered, "debug-rendered", false, "legacy rendering")
	identity := f.String("hotspot-identity", "unknown", "unknown or safepoint-epoch")
	collection := f.String("collection-mode", "full", "full or events")
	f.BoolVar(&r.config.DrainEvents, "drain-events", false, "owned event stop")
	f.BoolVar(&r.DrainFull, "drain-full", false, "owned full stop")
	f.IntVar(&r.config.Enrichment.Workers, "workers", 4, "enrichment workers")
	f.IntVar(&r.config.Enrichment.MaxPending, "max-pending", 1024, "pending events")
	f.IntVar(&r.config.Enrichment.MaxPendingBytes, "max-pending-bytes", 16*1024*1024, "pending bytes")
	f.DurationVar(&r.config.Enrichment.Deadline, "enrichment-deadline", time.Second, "enrichment budget")
	f.StringVar(&r.config.Image, "image", "", "digest-pinned gadget")
	f.StringVar(&r.config.Store, "store", "", "local OCI store")
	f.DurationVar(&r.Duration, "duration", 15*time.Second, "capture duration")
	f.IntVar(&r.Protocol, "protocol-version", 1, "1 experimental, 2 strict, 3 observed request")
	profile := f.String("runtime-profile", "", "explicit runtime profile")
	f.Uint64Var(&r.Target.StartTicks, "expected-start-ticks", 0, "expected process birth")
	f.Uint64Var(&r.Target.CgroupID, "expected-cgroup-id", 0, "expected cgroup")
	f.Uint64Var(&r.Target.MountNS, "expected-mntns", 0, "expected mount namespace")
	f.BoolVar(&r.Control, "control-stdin", false, "owned parent control")
	if err := f.Parse(args); err != nil {
		return r, err
	}
	if *identity != "unknown" && *identity != "safepoint-epoch" {
		return r, fmt.Errorf("invalid HotSpot identity mode")
	}
	r.StrictEpoch = *identity == "safepoint-epoch"
	if *profile != "" && *profile != string(wire.Strict25) && *profile != string(wire.Observed25) {
		return r, fmt.Errorf("invalid runtime profile")
	}
	r.Profile = wire.Profile(*profile)
	if r.Profile == "" && r.Protocol == 2 {
		r.Profile = wire.Strict25
	}
	switch r.Protocol {
	case 1:
		if r.Profile != "" || r.Control || r.Target != (wire.Target{}) {
			return r, fmt.Errorf("experimental schema1 has no owned profile negotiation")
		}
	case 2:
		if r.Profile != wire.Strict25 {
			return r, fmt.Errorf("unsupported profile/schema pairing")
		}
		if r.Target != (wire.Target{}) {
			return r, fmt.Errorf("expected target fields require observed schema3")
		}
		if !*capturing || !r.DrainFull || !r.StrictEpoch || r.debugRendered {
			return r, fmt.Errorf("unsupported production capture/control scope")
		}
	case 3:
		if r.Profile != wire.Observed25 {
			return r, fmt.Errorf("unsupported profile/schema pairing")
		}
		if !*capturing || r.StrictEpoch || !r.DrainFull || !r.Control || r.debugRendered || *collection != "full" || r.config.DrainEvents {
			return r, fmt.Errorf("unsupported observed negotiation")
		}
		if r.config.PID <= 0 || r.config.PID > 1<<31-1 || r.Target.StartTicks == 0 || r.Target.CgroupID == 0 || r.Target.MountNS == 0 {
			return r, fmt.Errorf("missing/invalid expected target")
		}
		if r.Duration > time.Minute || r.config.Enrichment != capture.DefaultEnrichmentOptions() {
			return r, fmt.Errorf("observed negotiation exceeds verified bounds")
		}
	default:
		return r, fmt.Errorf("unsupported production schema")
	}
	if r.StrictEpoch && (r.debugRendered || r.Duration > time.Minute) {
		return r, fmt.Errorf("invalid rendering mode or strict session duration")
	}
	if (*collection != "full" && *collection != "events") || (*collection == "events" && (r.StrictEpoch || r.debugRendered)) {
		return r, fmt.Errorf("invalid collection mode or incompatible identity/rendering option")
	}
	if r.config.DrainEvents && (!*capturing || *collection != "events" || r.debugRendered) {
		return r, fmt.Errorf("--drain-events requires structured events-only capture")
	}
	if r.DrainFull && (!*capturing || *collection != "full" || (!r.StrictEpoch && r.Protocol != 3) || r.debugRendered || r.config.DrainEvents) {
		return r, fmt.Errorf("--drain-full requires owned structured full capture")
	}
	if r.preflight == *capturing || f.NArg() != 0 || r.Duration <= 0 {
		return r, fmt.Errorf("choose --preflight or --capture; --capture requires --pid, --image and --store")
	}
	r.Target.PID = uint32(r.config.PID)
	if r.Protocol == 3 {
		r.config.ExpectedTarget = r.Target
	}
	r.config.StrictEpoch, r.config.DrainFull, r.config.EventsOnly = r.StrictEpoch, r.DrainFull, *collection == "events"
	return r, nil
}
