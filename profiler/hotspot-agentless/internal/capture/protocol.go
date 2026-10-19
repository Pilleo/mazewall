package capture

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"io"
	"os"
	"syscall"

	"io.mazewall/experiments/hotspot-agentless/internal/identity"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
	jsonoutput "io.mazewall/experiments/hotspot-agentless/internal/output"
)

const ProductionSchema = 2

var errReadoutPending = errors.New("owned event readout still pending")

type productionSink struct{ model.Sink }

func newProductionSink(writer io.Writer) model.Sink {
	return productionSink{jsonoutput.NewJSONL(writer)}
}

func (s productionSink) Write(ctx context.Context, record model.Record) error {
	if record.Kind == "collector_attached" {
		return nil
	}
	record.SchemaVersion = ProductionSchema
	return s.Sink.Write(ctx, record)
}

func lifecycleRecord(kind, session string, pid uint32, ticks uint64) (model.Record, error) {
	boot, err := (LinuxBootClock{}).Now()
	if err != nil {
		return model.Record{}, err
	}
	return model.Record{SchemaVersion: ProductionSchema, Kind: kind, SessionID: session, Lifecycle: &model.Lifecycle{
		PID: pid, ProcessStartTicks: ticks, ClockDomain: "CLOCK_BOOTTIME", BootNS: boot,
		RuntimeProfile: identity.ProfileName, JVMBuildID: identity.JVMBuildID, JVMSHA256: identity.JVMSHA256,
		Provider: providerIdentity(),
	}}, nil
}

func writeProductionReady(ctx context.Context, sink model.Sink, session string, pid uint32, ticks uint64, check func() error) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if err := check(); err != nil {
		return err
	}
	record, err := lifecycleRecord("collector_ready", session, pid, ticks)
	if err != nil {
		return err
	}
	return sink.Write(ctx, record)
}

// RunProduction explicitly selects the schema-2 strict, owned production scope.
// Schema-1 experiment entrypoints remain available through Run only.
func RunProduction(ctx context.Context, cfg Config, writer io.Writer) error {
	if err := validateProductionConfig(cfg); err != nil {
		return err
	}
	return runProduction(ctx, cfg, writer)
}

func validateProductionConfig(cfg Config) error {
	if cfg.profile != "" && cfg.profile != "temurin25_xint_serial" {
		return fmt.Errorf("production capture requires strict profile")
	}
	if !cfg.StrictEpoch || !cfg.DrainFull || cfg.EventsOnly || cfg.DrainEvents {
		return fmt.Errorf("production capture requires strict epoch and full owned drain")
	}
	if cfg.Enrichment != (EnrichmentOptions{}) && cfg.Enrichment != DefaultEnrichmentOptions() {
		return fmt.Errorf("production capture requires verified native enrichment bounds")
	}
	if err := cfg.Validate(); err != nil {
		return err
	}
	return nil
}

func runProduction(ctx context.Context, cfg Config, writer io.Writer) error {
	var random [16]byte
	if _, err := rand.Read(random[:]); err != nil {
		return err
	}
	cfg.sessionID, cfg.production = fmt.Sprintf("%x", random), true
	sink := newProductionSink(writer)
	started, err := lifecycleRecord("session_started", cfg.sessionID, uint32(cfg.PID), 0)
	if err != nil {
		return err
	}
	// Runtime/build fields on session_started describe the requested profile, not admission.
	started.Lifecycle.JVMBuildID, started.Lifecycle.JVMSHA256 = "", ""
	if err := sink.Write(ctx, started); err != nil {
		return err
	}
	err = Run(ctx, cfg, writer)
	if err != nil {
		if errors.Is(err, errReadoutPending) {
			return err
		}
		record, clockErr := lifecycleRecord("collector_error", cfg.sessionID, uint32(cfg.PID), 0)
		if clockErr != nil {
			return errors.Join(err, clockErr)
		}
		record.Lifecycle.Message = err.Error()
		record.Lifecycle.JVMBuildID, record.Lifecycle.JVMSHA256 = "", ""
		var errno syscall.Errno
		if errors.As(err, &errno) {
			record.Lifecycle.Errno = int(errno)
		}
		return errors.Join(err, sink.Write(context.Background(), record))
	}
	return nil
}

// Stdin is an explicitly borrowed parent-lifetime pipe. Its owner keeps it open
// through finalization, sends exactly stop\n for graceful drain, and closes it on loss.
func RunProductionControlled(ctx context.Context, cfg Config, writer io.Writer, control *os.File) (result error) {
	if err := validateProductionConfig(cfg); err != nil {
		return err
	}
	return runControlled(ctx, cfg, writer, control, runProduction)
}

func runControlled(ctx context.Context, cfg Config, writer io.Writer, control *os.File, run func(context.Context, Config, io.Writer) error) (result error) {
	if control == nil {
		return fmt.Errorf("owned capture requires parent control pipe")
	}
	request, stopRequest := context.WithCancel(ctx)
	defer stopRequest()
	abort, fail := context.WithCancelCause(context.Background())
	defer fail(nil)
	watch, stopWatch := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		done <- watchControl(watch, int(control.Fd()), stopRequest, func(err error) { fail(err); stopRequest() })
	}()
	defer func() { stopWatch(); result = errors.Join(result, <-done) }()
	cfg.AbortContext = abort
	return run(request, cfg, writer)
}
