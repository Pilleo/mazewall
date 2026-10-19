package capture

import (
	"context"
	"errors"
	"syscall"
	"testing"

	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
	jsonoutput "io.mazewall/experiments/hotspot-agentless/internal/output"
)

func TestOwnedFailureDoesNotPublishSummary(t *testing.T) {
	for _, cfg := range []Config{{production: true}, {profile: wire.Observed25}} {
		sink := &recordSink{}
		cause := errors.Join(context.Canceled, syscall.EACCES)
		err := writeSessionSummary(cfg, sink, "failed", model.Summary{}, cause)
		if !errors.Is(err, context.Canceled) || !errors.Is(err, syscall.EACCES) || len(sink.snapshot()) != 0 {
			t.Fatal("owned failure serialized a misleading summary", err, sink.snapshot())
		}
	}
}

func TestSuccessfulSessionPublishesSummary(t *testing.T) {
	for _, cfg := range []Config{{}, {production: true}, {profile: wire.Observed25}} {
		sink := &recordSink{}
		if err := writeSessionSummary(cfg, sink, "success", model.Summary{TerminalAccountingComplete: true}, nil); err != nil {
			t.Fatal(err)
		}
		records := sink.snapshot()
		if len(records) != 1 || records[0].SchemaVersion != 1 || records[0].Kind != "session_summary" || records[0].SessionID != "success" || records[0].Summary == nil || !records[0].Summary.TerminalAccountingComplete {
			t.Fatal("successful summary lost", records)
		}
	}
}

func TestSchema1FailureStillPublishesDiagnosticSummary(t *testing.T) {
	sink := &recordSink{}
	cause := errors.Join(context.Canceled, syscall.EACCES)
	err := writeSessionSummary(Config{}, sink, "diagnostic", model.Summary{Errors: []string{cause.Error()}}, cause)
	records := sink.snapshot()
	if !errors.Is(err, context.Canceled) || !errors.Is(err, syscall.EACCES) || len(records) != 1 || records[0].Summary == nil || len(records[0].Summary.Errors) != 1 || records[0].Summary.Errors[0] != cause.Error() {
		t.Fatal("schema1 diagnostic summary or original cause lost", err, records)
	}
}

type failedSummaryWriter struct{}

func (failedSummaryWriter) Write([]byte) (int, error) { return 0, syscall.ENOSPC }

func TestSummaryPreservesWriterAndSessionFailures(t *testing.T) {
	for _, cfg := range []Config{{}, {production: true}, {profile: wire.Observed25}} {
		var sink model.Sink = jsonoutput.NewJSONL(failedSummaryWriter{})
		if cfg.production {
			sink = newProductionSink(failedSummaryWriter{})
		}
		if cfg.profile == wire.Observed25 {
			sink = newObservedSink(failedSummaryWriter{})
		}
		if err := writeSessionSummary(cfg, sink, "success", model.Summary{}, nil); !errors.Is(err, syscall.ENOSPC) {
			t.Fatal("summary writer failure lost", err)
		}
	}
	err := writeSessionSummary(Config{}, jsonoutput.NewJSONL(failedSummaryWriter{}), "diagnostic", model.Summary{}, syscall.EACCES)
	if !errors.Is(err, syscall.EACCES) || !errors.Is(err, syscall.ENOSPC) {
		t.Fatal("schema1 session/writer failure lost", err)
	}
}
