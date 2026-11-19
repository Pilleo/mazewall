package capture

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"reflect"
	"strings"
	"syscall"
	"testing"

	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"go.opentelemetry.io/ebpf-profiler/libpf"
	"io.mazewall/experiments/hotspot-agentless/internal/accounting"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
	jsonoutput "io.mazewall/experiments/hotspot-agentless/internal/output"
)

func TestObservedFailureProducerRoundTripRetainsCompoundCauses(t *testing.T) {
	previousPatch := GadgetPatchSHA
	GadgetPatchSHA = "22f66bf56bb3b9a00c47186a53bff07ab932ca48dff8b3900666348685ff5035"
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

func TestProductionFailureRetainsFailedDefinitionReceipt(t *testing.T) {
	previousPatch := GadgetPatchSHA
	GadgetPatchSHA = "22f66bf56bb3b9a00c47186a53bff07ab932ca48dff8b3900666348685ff5035"
	t.Cleanup(func() { GadgetPatchSHA = previousPatch })

	expectedBinding := validBindingFixture()

	cases := []struct {
		name   string
		mutFn  func(b libpf.TraceDefinitionBinding) libpf.TraceDefinitionObservation
		errMsg string
	}{
		{
			name: "identity_mismatch",
			mutFn: func(b libpf.TraceDefinitionBinding) libpf.TraceDefinitionObservation {
				obs := validObservationFixture(b)
				obs.Binding.HostTGID = 99999
				return obs
			},
			errMsg: "trace definition binding mismatch",
		},
		{
			name: "not_exercised_wrong_target",
			mutFn: func(b libpf.TraceDefinitionBinding) libpf.TraceDefinitionObservation {
				obs := libpf.TraceDefinitionObservation{Binding: b, Rows: make([]libpf.TraceDefinitionCPUCounts, b.PossibleCPUs), Outcome: libpf.TraceDefinitionNotExercised}
				obs.Binding.HostTGID++
				return obs
			},
			errMsg: "trace definition binding mismatch",
		},
		{
			name: "not_exercised_wrong_generation",
			mutFn: func(b libpf.TraceDefinitionBinding) libpf.TraceDefinitionObservation {
				obs := libpf.TraceDefinitionObservation{Binding: b, Rows: make([]libpf.TraceDefinitionCPUCounts, b.PossibleCPUs), Outcome: libpf.TraceDefinitionNotExercised}
				obs.Binding.Generation++
				return obs
			},
			errMsg: "trace definition binding mismatch",
		},
		{
			name: "not_exercised_wrong_cpu_cardinality",
			mutFn: func(b libpf.TraceDefinitionBinding) libpf.TraceDefinitionObservation {
				return libpf.TraceDefinitionObservation{Binding: b, Rows: make([]libpf.TraceDefinitionCPUCounts, b.PossibleCPUs+1), Outcome: libpf.TraceDefinitionNotExercised}
			},
			errMsg: "trace definition CPU cardinality mismatch",
		},
		{
			name: "not_exercised_nonzero_attempted",
			mutFn: func(b libpf.TraceDefinitionBinding) libpf.TraceDefinitionObservation {
				obs := validObservationFixture(b)
				obs.Outcome = libpf.TraceDefinitionNotExercised
				return obs
			},
			errMsg: "trace definition not exercised with nonzero attempted",
		},
		{
			name: "not_exercised_row_overflow",
			mutFn: func(b libpf.TraceDefinitionBinding) libpf.TraceDefinitionObservation {
				obs := libpf.TraceDefinitionObservation{
					Binding: b,
					Rows:    make([]libpf.TraceDefinitionCPUCounts, b.PossibleCPUs),
					Outcome: libpf.TraceDefinitionNotExercised,
				}
				obs.Rows[0].Overflow = 1
				return obs
			},
			errMsg: "trace definition native counter overflow",
		},
		{
			name: "not_exercised_reader_overflow",
			mutFn: func(b libpf.TraceDefinitionBinding) libpf.TraceDefinitionObservation {
				obs := libpf.TraceDefinitionObservation{
					Binding: b,
					Rows:    make([]libpf.TraceDefinitionCPUCounts, b.PossibleCPUs),
					Outcome: libpf.TraceDefinitionNotExercised,
				}
				obs.Reader.Overflow = true
				return obs
			},
			errMsg: "trace definition reader counter overflow",
		},
		{
			name: "not_exercised_totals_mismatch",
			mutFn: func(b libpf.TraceDefinitionBinding) libpf.TraceDefinitionObservation {
				obs := libpf.TraceDefinitionObservation{
					Binding:     b,
					Rows:        make([]libpf.TraceDefinitionCPUCounts, b.PossibleCPUs),
					Outcome:     libpf.TraceDefinitionNotExercised,
					Undelivered: 5,
				}
				return obs
			},
			errMsg: "trace definition observation totals mismatch",
		},
		{
			name: "exercised_row_overflow",
			mutFn: func(b libpf.TraceDefinitionBinding) libpf.TraceDefinitionObservation {
				obs := validObservationFixture(b)
				obs.Rows = append([]libpf.TraceDefinitionCPUCounts(nil), obs.Rows...)
				obs.Rows[0].Overflow = 1
				return obs
			},
			errMsg: "trace definition native counter overflow",
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			finalObs := tc.mutFn(expectedBinding)
			defAcc := &definitionAccounting{
				expected: expectedBinding,
				final:    finalObs,
			}

			summary := model.Summary{}
			diagErr := applyDefinitionAccounting(defAcc, nil, &summary)
			if diagErr == nil {
				t.Fatalf("case %s: expected applyDefinitionAccounting to fail", tc.name)
			}
			if !strings.Contains(diagErr.Error(), tc.errMsg) {
				t.Fatalf("case %s: expected error containing %q, got %q", tc.name, tc.errMsg, diagErr.Error())
			}

			joinedCause := errors.Join(diagErr, context.Canceled, syscall.EACCES)

			// 1. writeSessionSummary must return joinedCause and write zero records on failure
			for _, cfg := range []Config{{production: true}, {profile: wire.Observed25}} {
				sink := &recordSink{}
				err := writeSessionSummary(cfg, sink, "failed", summary, joinedCause)
				if !errors.Is(err, context.Canceled) || !errors.Is(err, syscall.EACCES) || !errors.Is(err, diagErr) {
					t.Fatalf("case %s: writeSessionSummary lost original cause tree: %v", tc.name, err)
				}
				if len(sink.snapshot()) != 0 {
					t.Fatalf("case %s: writeSessionSummary emitted %d records on owned failure", tc.name, len(sink.snapshot()))
				}
			}

			// 2. Test writeProductionFailure (wire.Strict25)
			{
				cfg := Config{production: true, sessionID: "failure-prod-" + tc.name, PID: 123}
				var out bytes.Buffer
				sink := newProductionSink(&out)
				start, err := lifecycleRecord("session_started", cfg.sessionID, uint32(cfg.PID), 456)
				if err != nil {
					t.Fatal(err)
				}
				if err := sink.Write(context.Background(), start); err != nil {
					t.Fatal(err)
				}
				origErr := writeProductionFailure(sink, cfg, joinedCause)
				if !errors.Is(origErr, context.Canceled) || !errors.Is(origErr, syscall.EACCES) || !errors.Is(origErr, diagErr) {
					t.Fatalf("case %s: writeProductionFailure lost cause tree: %v", tc.name, origErr)
				}

				lines := bytes.Split(bytes.TrimSpace(out.Bytes()), []byte{'\n'})
				if len(lines) != 2 {
					t.Fatalf("case %s: expected 2 lines from writeProductionFailure, got %d", tc.name, len(lines))
				}
				if len(lines[1]) > 1048576 {
					t.Fatalf("case %s: line 1 exceeded decoder limit: %d", tc.name, len(lines[1]))
				}

				var failure wire.Record
				if err := json.Unmarshal(lines[1], &failure); err != nil {
					t.Fatalf("case %s: unmarshal error: %v", tc.name, err)
				}
				if failure.Kind != "collector_error" || failure.Lifecycle == nil {
					t.Fatalf("case %s: invalid failure record: %v", tc.name, failure)
				}
				msg := failure.Lifecycle.Message
				if !strings.Contains(msg, "\nfailed_definition_receipt:") {
					t.Fatalf("case %s: message missing failed_definition_receipt: %q", tc.name, msg)
				}

				parts := strings.Split(msg, "\nfailed_definition_receipt:")
				if len(parts) != 2 {
					t.Fatalf("case %s: unexpected split parts: %d", tc.name, len(parts))
				}
				var decodedReceipt struct {
					Expected libpf.TraceDefinitionBinding     `json:"expected"`
					Final    libpf.TraceDefinitionObservation `json:"final"`
					Counts   *accounting.Counts               `json:"counts,omitempty"`
					Error    string                           `json:"error,omitempty"`
				}
				receiptJSON := parts[1]
				if idx := strings.IndexByte(receiptJSON, '\n'); idx != -1 {
					receiptJSON = receiptJSON[:idx]
				}
				if err := json.Unmarshal([]byte(receiptJSON), &decodedReceipt); err != nil {
					t.Fatalf("case %s: failed to unmarshal embedded receipt: %v", tc.name, err)
				}
				if decodedReceipt.Expected != expectedBinding {
					t.Fatalf("case %s: receipt expected binding mismatch", tc.name)
				}
				if decodedReceipt.Final.Binding != finalObs.Binding || len(decodedReceipt.Final.Rows) != len(finalObs.Rows) {
					t.Fatalf("case %s: receipt final observation mismatch", tc.name)
				}
				if decodedReceipt.Counts != nil {
					t.Fatalf("case %s: receipt unexpectedly published Counts", tc.name)
				}
				if decodedReceipt.Error == "" || !strings.Contains(decodedReceipt.Error, tc.errMsg) {
					t.Fatalf("case %s: receipt missing expected error: %q", tc.name, decodedReceipt.Error)
				}

				// Wire Decoder verification
				d := wire.NewDecoder(wire.Expected{
					Schema:   2,
					Profile:  wire.Strict25,
					Target:   wire.Target{PID: 123, StartTicks: 456},
					Provider: start.Lifecycle.Provider,
				})
				if err := d.Accept(lines[0]); err != nil {
					t.Fatalf("case %s: decoder start rejected: %v", tc.name, err)
				}
				decErr := d.Accept(lines[1])
				if !errors.Is(decErr, wire.ErrCollector) {
					t.Fatalf("case %s: expected ErrCollector, got %v", tc.name, decErr)
				}
				if !strings.Contains(decErr.Error(), "failed_definition_receipt:") {
					t.Fatalf("case %s: decoder error text missing receipt: %q", tc.name, decErr.Error())
				}
				waitErr := errors.New("parent process wait error")
				capture, finishErr := d.Finish(waitErr)
				if !errors.Is(finishErr, waitErr) || !errors.Is(finishErr, wire.ErrProcess) || !reflect.DeepEqual(capture, wire.Capture{}) {
					t.Fatalf("case %s: finish capture not empty or causes lost: %v, %v", tc.name, capture, finishErr)
				}
			}

			// 3. Test writeObservedFailure (wire.Observed25)
			{
				cfg := Config{
					sessionID:      "failure-obs-" + tc.name,
					ExpectedTarget: wire.Target{PID: 123, StartTicks: 456, CgroupID: 789, MountNS: 42},
				}
				var out bytes.Buffer
				sink := newObservedSink(&out)
				start, err := observedLifecycleRecord("session_started", cfg.sessionID, cfg.ExpectedTarget, "", "")
				if err != nil {
					t.Fatal(err)
				}
				if err := sink.Write(context.Background(), start); err != nil {
					t.Fatal(err)
				}
				origErr := writeObservedFailure(cfg, sink, joinedCause)
				if !errors.Is(origErr, context.Canceled) || !errors.Is(origErr, syscall.EACCES) || !errors.Is(origErr, diagErr) {
					t.Fatalf("case %s: writeObservedFailure lost cause tree: %v", tc.name, origErr)
				}

				lines := bytes.Split(bytes.TrimSpace(out.Bytes()), []byte{'\n'})
				if len(lines) != 2 {
					t.Fatalf("case %s: expected 2 lines from writeObservedFailure, got %d", tc.name, len(lines))
				}
				if len(lines[1]) > 1048576 {
					t.Fatalf("case %s: line 1 exceeded decoder limit: %d", tc.name, len(lines[1]))
				}

				var failure wire.Record
				if err := json.Unmarshal(lines[1], &failure); err != nil {
					t.Fatalf("case %s: unmarshal error: %v", tc.name, err)
				}
				if failure.Kind != "collector_error" || failure.Lifecycle == nil {
					t.Fatalf("case %s: invalid failure record: %v", tc.name, failure)
				}
				msg := failure.Lifecycle.Message
				if !strings.Contains(msg, "\nfailed_definition_receipt:") {
					t.Fatalf("case %s: message missing failed_definition_receipt: %q", tc.name, msg)
				}

				d := wire.NewDecoder(wire.Expected{
					Schema:   3,
					Profile:  wire.Observed25,
					Target:   cfg.ExpectedTarget,
					Provider: start.Lifecycle.Provider,
				})
				if err := d.Accept(lines[0]); err != nil {
					t.Fatalf("case %s: decoder start rejected: %v", tc.name, err)
				}
				decErr := d.Accept(lines[1])
				if !errors.Is(decErr, wire.ErrCollector) {
					t.Fatalf("case %s: expected ErrCollector, got %v", tc.name, decErr)
				}
				waitErr := errors.New("parent wait error")
				capture, finishErr := d.Finish(waitErr)
				if !errors.Is(finishErr, waitErr) || !errors.Is(finishErr, wire.ErrProcess) || !reflect.DeepEqual(capture, wire.Capture{}) {
					t.Fatalf("case %s: finish capture not empty or causes lost: %v, %v", tc.name, capture, finishErr)
				}
			}
		})
	}
}

func TestNotExercisedWithoutCombinedReceiptFailsClosed(t *testing.T) {
	previousPatch := GadgetPatchSHA
	GadgetPatchSHA = "22f66bf56bb3b9a00c47186a53bff07ab932ca48dff8b3900666348685ff5035"
	t.Cleanup(func() { GadgetPatchSHA = previousPatch })
	expectedBinding := validBindingFixture()
	unexercisedObs := libpf.TraceDefinitionObservation{
		Binding: expectedBinding,
		Rows:    make([]libpf.TraceDefinitionCPUCounts, expectedBinding.PossibleCPUs),
		Outcome: libpf.TraceDefinitionNotExercised,
	}
	defAcc := &definitionAccounting{
		expected: expectedBinding,
		final:    unexercisedObs,
	}

	summary := model.Summary{}
	err := applyDefinitionAccounting(defAcc, nil, &summary)
	if err == nil || !strings.Contains(err.Error(), "combined definition receipt not accepted") {
		t.Fatalf("missing combined qualification error: %v", err)
	}
	if defAcc.status == definitionAccepted { t.Fatal("unqualified receipt was accepted") }

	// The local zero trace is retained as diagnostic evidence, not all-source authority.
	raw, ok := summary.Extensions["definition_accounting"]
	if !ok || len(raw) == 0 {
		t.Fatal("definition_accounting extension missing on legitimate NotExercised")
	}
	var decoded struct {
		Expected libpf.TraceDefinitionBinding     `json:"expected"`
		Final    libpf.TraceDefinitionObservation `json:"final"`
		Counts   *accounting.Counts               `json:"counts,omitempty"`
		Error    string                           `json:"error,omitempty"`
	}
	if err := json.Unmarshal(raw, &decoded); err != nil {
		t.Fatalf("unmarshal error: %v", err)
	}
	if !strings.Contains(decoded.Error, "combined definition receipt not accepted") {
		t.Fatalf("missing qualification error: %q", decoded.Error)
	}
	if decoded.Counts != nil {
		t.Fatal("NotExercised unexpectedly published Counts")
	}
	if decoded.Expected != expectedBinding || !reflect.DeepEqual(decoded.Final, unexercisedObs) || len(decoded.Final.Rows) != expectedBinding.PossibleCPUs {
		t.Fatalf("local zero-trace evidence changed: %+v", decoded.Final)
	}
	var attached definitionReceiptError
	if !errors.As(err, &attached) || !bytes.Equal(attached.raw, raw) { t.Fatal("error did not retain exact diagnostic receipt") }

	// An owned failure must not publish a success summary.
	for _, cfg := range []Config{{production: true}, {profile: wire.Observed25}} {
		sink := &recordSink{}
		if got := writeSessionSummary(cfg, sink, "failed", summary, err); !errors.Is(got, err) {
			t.Fatalf("writeSessionSummary lost qualification error: %v", got)
		}
		if len(sink.snapshot()) != 0 {
			t.Fatalf("failure published %d summary records", len(sink.snapshot()))
		}
	}
	cfg := Config{production: true, sessionID: "not-exercised-unqualified", PID: 123}
	var out bytes.Buffer
	sink := newProductionSink(&out)
	start, startErr := lifecycleRecord("session_started", cfg.sessionID, uint32(cfg.PID), 456)
	if startErr != nil { t.Fatal(startErr) }
	if startErr = sink.Write(context.Background(), start); startErr != nil { t.Fatal(startErr) }
	if got := writeProductionFailure(sink, cfg, err); !errors.Is(got, err) { t.Fatalf("failure lost cause: %v", got) }
	lines := bytes.Split(bytes.TrimSpace(out.Bytes()), []byte{'\n'})
	if len(lines) != 2 { t.Fatalf("expected start and failure, got %d lines", len(lines)) }
	var failure wire.Record
	if decodeErr := json.Unmarshal(lines[1], &failure); decodeErr != nil { t.Fatal(decodeErr) }
	if failure.Kind != "collector_error" || failure.Lifecycle == nil || !strings.Contains(failure.Lifecycle.Message, "\nfailed_definition_receipt:"+string(raw)) { t.Fatal("failure wire lost raw receipt") }
	decoder := wire.NewDecoder(wire.Expected{Schema: 2, Profile: wire.Strict25, Target: wire.Target{PID: 123, StartTicks: 456}, Provider: start.Lifecycle.Provider})
	if decodeErr := decoder.Accept(lines[0]); decodeErr != nil { t.Fatal(decodeErr) }
	if decodeErr := decoder.Accept(lines[1]); !errors.Is(decodeErr, wire.ErrCollector) { t.Fatalf("failure not classified as collector error: %v", decodeErr) }
	waitErr := errors.New("failed capture process")
	capture, finishErr := decoder.Finish(waitErr)
	if !errors.Is(finishErr, waitErr) || !errors.Is(finishErr, wire.ErrProcess) || !reflect.DeepEqual(capture, wire.Capture{}) { t.Fatalf("unqualified capture published: %v, %v", capture, finishErr) }
}

func TestProductionFailureReceiptCapAndBoundBehavior(t *testing.T) {
	expectedBinding := validBindingFixture()

	// 1. Realistic max 8192 CPUs observation: exceeds 262,144 byte budget
	t.Run("max_8192_cpus_omitted_over_budget", func(t *testing.T) {
		b8192 := expectedBinding
		b8192.PossibleCPUs = 8192
		rows := make([]libpf.TraceDefinitionCPUCounts, 8192)
		for i := range rows {
			rows[i] = libpf.TraceDefinitionCPUCounts{
				Attempted: 1,
				Submitted: 1,
			}
		}
		obs8192 := libpf.TraceDefinitionObservation{
			Binding: b8192,
			Rows:    rows,
			Reader: libpf.TraceDefinitionReaderCounts{
				Received:  8192,
				HandedOff: 8192,
			},
			Outcome: libpf.TraceDefinitionFinal,
		}
		// Mutate binding to trigger failure
		obs8192.Binding.HostTGID = 99999
		defAcc := &definitionAccounting{
			expected: b8192,
			final:    obs8192,
		}

		var summary model.Summary
		diagErr := applyDefinitionAccounting(defAcc, nil, &summary)
		if diagErr == nil {
			t.Fatal("expected failure on 8192 CPU binding mismatch")
		}

		// Message must contain omit marker and NOT raw JSON
		msg := diagErr.Error()
		if !strings.Contains(msg, "\nfailed_definition_receipt:omitted_over_budget") {
			t.Fatalf("expected omitted_over_budget marker, got %q", msg)
		}
		if strings.Contains(msg, "failed_definition_receipt:{") {
			t.Fatal("over budget receipt unexpectedly embedded raw JSON")
		}

		// Ensure publisher outputs <= 1048576 line
		cfg := Config{production: true, sessionID: "failure-8192", PID: 123}
		var out bytes.Buffer
		sink := newProductionSink(&out)
		start, err := lifecycleRecord("session_started", cfg.sessionID, uint32(cfg.PID), 456)
		if err != nil {
			t.Fatal(err)
		}
		if err := sink.Write(context.Background(), start); err != nil {
			t.Fatal(err)
		}
		if err := writeProductionFailure(sink, cfg, diagErr); !errors.Is(err, diagErr) {
			t.Fatalf("writeProductionFailure lost cause: %v", err)
		}
		lines := bytes.Split(bytes.TrimSpace(out.Bytes()), []byte{'\n'})
		if len(lines) != 2 {
			t.Fatalf("expected 2 lines, got %d", len(lines))
		}
		if len(lines[1]) > 1048576 {
			t.Fatalf("8192 CPU line exceeded 1048576 bytes: %d", len(lines[1]))
		}

		var rec wire.Record
		if err := json.Unmarshal(lines[1], &rec); err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(rec.Lifecycle.Message, "failed_definition_receipt:omitted_over_budget") {
			t.Fatalf("record lifecycle message missing omit marker: %q", rec.Lifecycle.Message)
		}
	})

	// 2. Escaping near cap: quote, backslash, control, nonASCII
	t.Run("special_characters_escaping", func(t *testing.T) {
		bEsc := expectedBinding
		bEsc.Session = "test-\"quote\\backslash\tcontrol\nnewline-ü-✓"
		obsEsc := validObservationFixture(bEsc)
		obsEsc.Binding.HostTGID = 99999

		defAcc := &definitionAccounting{
			expected: bEsc,
			final:    obsEsc,
		}
		var summary model.Summary
		diagErr := applyDefinitionAccounting(defAcc, nil, &summary)
		if diagErr == nil {
			t.Fatal("expected failure")
		}

		cfg := Config{production: true, sessionID: "failure-esc", PID: 123}
		var out bytes.Buffer
		sink := newProductionSink(&out)
		start, err := lifecycleRecord("session_started", cfg.sessionID, uint32(cfg.PID), 456)
		if err != nil {
			t.Fatal(err)
		}
		if err := sink.Write(context.Background(), start); err != nil {
			t.Fatal(err)
		}
		if err := writeProductionFailure(sink, cfg, diagErr); !errors.Is(err, diagErr) {
			t.Fatalf("writeProductionFailure lost cause: %v", err)
		}
		lines := bytes.Split(bytes.TrimSpace(out.Bytes()), []byte{'\n'})
		if len(lines[1]) > 1048576 {
			t.Fatalf("escaped line exceeded limit: %d", len(lines[1]))
		}

		var rec wire.Record
		if err := json.Unmarshal(lines[1], &rec); err != nil {
			t.Fatalf("failed to unmarshal escaped wire record: %v", err)
		}
		parts := strings.Split(rec.Lifecycle.Message, "\nfailed_definition_receipt:")
		if len(parts) != 2 {
			t.Fatalf("unexpected parts: %d", len(parts))
		}
		var decoded struct {
			Expected libpf.TraceDefinitionBinding `json:"expected"`
		}
		if err := json.Unmarshal([]byte(parts[1]), &decoded); err != nil {
			t.Fatalf("failed to unmarshal decoded receipt: %v", err)
		}
		if decoded.Expected.Session != bEsc.Session {
			t.Fatalf("roundtrip session mismatch: got %q, want %q", decoded.Expected.Session, bEsc.Session)
		}
	})
}
