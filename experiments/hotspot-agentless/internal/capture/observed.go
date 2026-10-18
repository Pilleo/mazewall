package capture

import (
	"context"
	"errors"
	"fmt"
	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"io"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
	jsonoutput "io.mazewall/experiments/hotspot-agentless/internal/output"
	"os"
)

const ObservationSchema = 3

var errObservedUnadmitted = errors.New("observed profile requires A-OBS qualification and separate operator admission review")

func RunObserved(context.Context, Config, io.Writer) error { return errObservedUnadmitted }
func RunObservedControlled(context.Context, Config, io.Writer, *os.File) error {
	return errObservedUnadmitted
}

type observedSink struct{ model.Sink }

func newObservedSink(w io.Writer) model.Sink { return observedSink{jsonoutput.NewJSONL(w)} }
func (s observedSink) Write(ctx context.Context, r model.Record) error {
	if r.Kind == "collector_attached" {
		return nil
	}
	r.SchemaVersion = ObservationSchema
	eligible := false
	r.PolicyEligibility = &eligible
	return s.Sink.Write(ctx, r)
}
func observedLifecycleRecord(kind, session string, target wire.Target, build, sha string) (model.Record, error) {
	r, err := lifecycleRecord(kind, session, target.PID, target.StartTicks)
	if err != nil {
		return r, err
	}
	r.SchemaVersion = ObservationSchema
	r.Lifecycle.RuntimeProfile = string(wire.Observed25)
	r.Lifecycle.ValidationMode = "upstream_generation_checks"
	r.Lifecycle.CgroupID, r.Lifecycle.MountNS = target.CgroupID, target.MountNS
	r.Lifecycle.JVMBuildID, r.Lifecycle.JVMSHA256 = build, sha
	return r, nil
}

func validateObservedEvent(expected wire.Target, event model.Event) error {
	if event.PID != expected.PID || event.ProcessStartTicks != expected.StartTicks || event.CgroupID != expected.CgroupID {
		return fmt.Errorf("captured event changed expected target PID/birth/cgroup")
	}
	return nil
}
