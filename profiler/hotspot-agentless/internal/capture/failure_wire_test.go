package capture

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"reflect"
	"syscall"
	"testing"

	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
	jsonoutput "io.mazewall/experiments/hotspot-agentless/internal/output"
)

func TestObservedFailureProducerRoundTripRetainsCompoundCauses(t *testing.T) {
	previousPatch := GadgetPatchSHA
	GadgetPatchSHA = "89b3599935df5206ff710292574e4480a09da535f8529b4b0462b8c29a7e0f50"
	t.Cleanup(func() { GadgetPatchSHA = previousPatch })
	for _, tc := range []struct {
		name    string
		cause   error
		primary int
		causes  []int
	}{
		{"no_errno", errors.New("no numeric cause"), 0, nil},
		{"single", syscall.EACCES, 13, nil},
		{"single_eperm", syscall.EPERM, 1, nil},
		{"single_eio", syscall.EIO, 5, nil},
		{"nonpermission_supplement", errors.Join(syscall.EIO, syscall.ENOSPC), 5, nil},
		{"eio_eacces", errors.Join(syscall.EIO, syscall.EACCES), 5, []int{5, 13}},
		{"eio_eperm", errors.Join(syscall.EIO, syscall.EPERM), 5, []int{5, 1}},
		{"both", errors.Join(syscall.EIO, syscall.EACCES, syscall.EPERM), 5, []int{5, 1, 13}},
		{"permission_primary", errors.Join(syscall.EACCES, syscall.EPERM), 13, []int{13, 1}},
		{"duplicates", errors.Join(syscall.EIO, syscall.EACCES, syscall.EACCES), 5, []int{5, 13}},
		{"cancel_first", errors.Join(context.Canceled, syscall.EIO, syscall.EACCES), 5, []int{5, 13}},
		{"cancel_last", errors.Join(syscall.EIO, syscall.EPERM, context.Canceled), 5, []int{5, 1}},
		{"cancel_single", errors.Join(context.Canceled, syscall.EACCES), 13, nil},
		{"wrapped", fmt.Errorf("reader: %w", errors.Join(syscall.EIO, syscall.EACCES)), 5, []int{5, 13}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			cfg := Config{sessionID: "failure-roundtrip", ExpectedTarget: wire.Target{PID: 123, StartTicks: 456, CgroupID: 789, MountNS: 42}}
			var out bytes.Buffer
			sink := newObservedSink(&out)
			start, err := observedLifecycleRecord("session_started", cfg.sessionID, cfg.ExpectedTarget, "", "")
			if err != nil {
				t.Fatal(err)
			}
			if err := sink.Write(context.Background(), start); err != nil {
				t.Fatal(err)
			}
			original := writeObservedFailure(cfg, sink, tc.cause)
			if !errors.Is(original, tc.cause) {
				t.Fatal("original error tree replaced", original)
			}
			lines := bytes.Split(bytes.TrimSpace(out.Bytes()), []byte{'\n'})
			if len(lines) != 2 {
				t.Fatal("failure emitted summary or lost record", out.String())
			}
			var failure wire.Record
			if err := json.Unmarshal(lines[1], &failure); err != nil {
				t.Fatal(err)
			}
			life := failure.Lifecycle
			if failure.Kind != "collector_error" || life == nil || life.Errno != tc.primary || life.Message != tc.cause.Error() || !reflect.DeepEqual(life.ErrnoCauses, tc.causes) {
				t.Fatal("producer changed primary/message or lost bounded causes", string(lines[1]))
			}
			if tc.causes == nil && bytes.Contains(lines[1], []byte("errno_causes")) {
				t.Fatal("ordinary encoding changed", string(lines[1]))
			}
			d := wire.NewDecoder(wire.Expected{Schema: 3, Profile: wire.Observed25, Target: cfg.ExpectedTarget, Provider: start.Lifecycle.Provider})
			if err := d.Accept(lines[0]); err != nil {
				t.Fatal(err)
			}
			err = d.Accept(lines[1])
			if !errors.Is(err, wire.ErrCollector) {
				t.Fatal("producer JSON rejected", err)
			}
			waitErr := errors.New("original reader/Wait failure")
			capture, err := d.Finish(waitErr)
			if !errors.Is(err, waitErr) || !errors.Is(err, wire.ErrProcess) || !reflect.DeepEqual(capture, wire.Capture{}) {
				t.Fatal("failed capture published or Wait cause lost", capture, err)
			}
			for _, errno := range []syscall.Errno{syscall.EIO, syscall.EPERM, syscall.EACCES} {
				if errors.Is(tc.cause, errno) != errors.Is(err, errno) {
					t.Fatal("native cause lost in actual SDK roundtrip", errno, err)
				}
			}
		})
	}
}

func TestObservedFailureRetainsWriterAndOriginalCauses(t *testing.T) {
	cause := errors.Join(context.Canceled, syscall.EIO, syscall.EACCES)
	err := writeObservedFailure(Config{}, newObservedSink(failedSummaryWriter{}), cause)
	if !errors.Is(err, cause) || !errors.Is(err, syscall.ENOSPC) {
		t.Fatal("failure writer or original tree lost", err)
	}
}

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
