package capture

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"io"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
	jsonoutput "io.mazewall/experiments/hotspot-agentless/internal/output"
	"os"
	"syscall"
)

const ObservationSchema = 3

// RunObserved collects generation-checked observations, never policy evidence.
func RunObserved(ctx context.Context, cfg Config, writer io.Writer) error {
	cfg.profile = wire.Observed25
	if err := cfg.Validate(); err != nil {
		return err
	}
	return runObserved(ctx, cfg, writer)
}

func RunObservedControlled(ctx context.Context, cfg Config, writer io.Writer, control *os.File) error {
	cfg.profile = wire.Observed25
	if err := cfg.Validate(); err != nil {
		return err
	}
	return runControlled(ctx, cfg, writer, control, runObserved)
}

func runObserved(ctx context.Context, cfg Config, writer io.Writer) error {
	var random [16]byte
	if _, err := rand.Read(random[:]); err != nil {
		return err
	}
	cfg.sessionID = fmt.Sprintf("%x", random)
	sink := newObservedSink(writer)
	r, err := observedLifecycleRecord("session_started", cfg.sessionID, cfg.ExpectedTarget, "", "")
	if err != nil {
		return err
	}
	if err := sink.Write(ctx, r); err != nil {
		return err
	}
	err = Run(ctx, cfg, writer)
	if err == nil || errors.Is(err, errReadoutPending) {
		return err
	}
	return writeObservedFailure(cfg, sink, err)
}

func writeObservedFailure(cfg Config, sink model.Sink, err error) error {
	r, clockErr := observedLifecycleRecord("collector_error", cfg.sessionID, cfg.ExpectedTarget, "", "")
	if clockErr != nil {
		return errors.Join(err, clockErr)
	}
	r.Lifecycle.Message = err.Error()
	var errno syscall.Errno
	if errors.As(err, &errno) {
		r.Lifecycle.Errno = int(errno)
		causes := []int{int(errno)}
		for _, permission := range []syscall.Errno{syscall.EPERM, syscall.EACCES} {
			if permission != errno && errors.Is(err, permission) {
				causes = append(causes, int(permission))
			}
		}
		if len(causes) > 1 {
			r.Lifecycle.ErrnoCauses = causes
		}
	}
	return errors.Join(err, sink.Write(context.Background(), r))
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
