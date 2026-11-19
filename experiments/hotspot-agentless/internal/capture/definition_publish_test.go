package capture

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"strings"
	"sync"
	"time"
	"syscall"
	"testing"

	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/symbolizer"
	"go.opentelemetry.io/ebpf-profiler/libpf"
	"go.opentelemetry.io/ebpf-profiler/tracer"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
)

func capturePublishModelJoin(t *testing.T, name string, done <-chan struct{}) bool {
	t.Helper()
	select { case <-done: return true; case <-time.After(time.Second): t.Errorf("%s did not finish", name); return false }
}

func TestDefinitionCallbackReceiptPublishers(t *testing.T) {
	old := GadgetPatchSHA
	GadgetPatchSHA = "22f66bf56bb3b9a00c47186a53bff07ab932ca48dff8b3900666348685ff5035"
	t.Cleanup(func() { GadgetPatchSHA = old })
	for _, mode := range []string{"owned", "exec", "drain", "nil-final", "finish", "expiry", "prune", "resolver", "final"} {
		t.Run(mode, func(t *testing.T) {
			value := validObservationFixture(validBindingFixture())
			state := &definitionAccounting{expected: validBindingFixture()}
			var ops []string
			owner := &callbackTestOwner{observation: symbolizer.DefinitionStopObservation{TraceDefinition: &value}, operations: &ops}
			if mode == "drain" {
				owner.drainErr = syscall.EACCES
			}
			if mode == "nil-final" {
				owner.observation.TraceDefinition = nil
			}
			if mode == "prune" || mode == "resolver" {
				owner.stopErr = syscall.EACCES
			}
			checks := 0
			exec := func() error {
				checks++
				if mode == "exec" || (mode == "final" && checks == 2) {
					return syscall.EACCES
				}
				return nil
			}
			started, release, finishDone, callbackDone := make(chan struct{}), make(chan struct{}), make(chan struct{}), make(chan struct{})
			var releaseOnce sync.Once
			var expiryStarted bool
			var cancelExpiry context.CancelFunc
			t.Cleanup(func() {
				if cancelExpiry != nil { cancelExpiry() }
				releaseOnce.Do(func() { close(release) })
				if expiryStarted {
					capturePublishModelJoin(t, "expiry callback", callbackDone)
					select { case <-started: capturePublishModelJoin(t, "expiry finish", finishDone); default: }
				}
			})
			finish := func() error {
				if mode == "expiry" {
					defer close(finishDone)
					close(started)
					<-release
				}
				if mode == "finish" {
					return syscall.EACCES
				}
				return nil
			}
			local := &definitionLocalRun{}
			owned, cancelOwned := context.WithCancel(context.Background())
			defer cancelOwned()
			if mode == "owned" {
				cancelOwned()
			}
			fatal := func() error {
				if mode == "owned" {
					return syscall.EACCES
				}
				return nil
			}
			callback := newDefinitionAfterReadout(owned, fatal, exec, owner, &state, local, finish)
			var cause error
			if mode == "expiry" {
				ctx, cancel := context.WithCancel(context.Background())
				cancelExpiry = cancel
				expiryStarted = true
				go func() { defer close(callbackDone); cause = callback(ctx) }()
				if !capturePublishModelJoin(t, "expiry start", started) { return }
				cancel()
				if !capturePublishModelJoin(t, "expiry callback", callbackDone) { return }
				releaseOnce.Do(func() { close(release) })
				if !capturePublishModelJoin(t, "expiry finish", finishDone) { return }
			} else {
				cause = callback(context.Background())
			}
			if cause == nil {
				t.Fatal("callback unexpectedly succeeded")
			}
			summary := model.Summary{}
			diag := applyDefinitionAccounting(state, local, &summary)
			if mode == "final" && diag == nil {
				t.Fatal("trace-only final diagnostic acquired native authority")
			}
			cause = attachDefinitionReceipt(errors.Join(cause, diag), summary.Extensions["definition_accounting"], local)
			if mode != "nil-final" && mode != "expiry" && !errors.Is(cause, syscall.EACCES) {
				t.Fatalf("lost cause: %v", cause)
			}
			if mode == "expiry" && !errors.Is(cause, context.Canceled) {
				t.Fatalf("lost deadline: %v", cause)
			}
			var receipt definitionReceiptError
			if !errors.As(cause, &receipt) {
				t.Fatalf("missing attached receipt: %v", cause)
			}
			var envelope struct {
				Local   json.RawMessage         `json:"local"`
				Missing *definitionLocalMissing `json:"local_missing"`
			}
			if err := json.Unmarshal(receipt.raw, &envelope); err != nil {
				t.Fatal(err)
			}
			phases := map[string]definitionReadoutPhase{"owned": localOwnedCheck, "exec": localExecCheck, "drain": localDrainOrRecord, "nil-final": localDrainOrRecord, "finish": localEnrichment, "expiry": localEnrichment, "prune": localAuxStop, "resolver": localAuxStop, "final": localFinalCheck}
			if string(envelope.Local) != "null" || envelope.Missing == nil || envelope.Missing.Phase != phases[mode] {
				t.Fatalf("unexpected envelope: %s", receipt.raw)
			}
			for _, observed := range []bool{false, true} {
				summarySink := &recordSink{}
				summaryCfg := Config{production: !observed}
				if observed {
					summaryCfg.profile = wire.Observed25
				}
				if err := writeSessionSummary(summaryCfg, summarySink, "test", summary, cause); !errors.Is(err, cause) || len(summarySink.snapshot()) != 0 {
					t.Fatalf("summary emitted or cause lost: %v", err)
				}
				var out bytes.Buffer
				cfg := Config{sessionID: "test", PID: 123, production: !observed, ExpectedTarget: wire.Target{PID: 123, StartTicks: 456, CgroupID: 789, MountNS: 42}}
				var err error
				if observed {
					err = writeObservedFailure(cfg, newObservedSink(&out), cause)
				} else {
					err = writeProductionFailure(newProductionSink(&out), cfg, cause)
				}
				if !errors.Is(err, cause) {
					t.Fatalf("lost cause: %v", err)
				}
				var record wire.Record
				if err := json.Unmarshal(bytes.TrimSpace(out.Bytes()), &record); err != nil {
					t.Fatal(err)
				}
				if record.Lifecycle == nil || !strings.Contains(record.Lifecycle.Message, `"local":null`) || !strings.Contains(record.Lifecycle.Message, `"local_missing":`) {
					t.Fatalf("missing receipt: %s", out.String())
				}
				if mode != "nil-final" && mode != "expiry" && record.Lifecycle.Errno != int(syscall.EACCES) {
					t.Fatalf("lost errno: %s", out.String())
				}
				parts := strings.SplitN(record.Lifecycle.Message, "\nfailed_definition_receipt:", 2)
				if len(parts) != 2 || !json.Valid([]byte(parts[1])) {
					t.Fatalf("wire receipt is not JSON: %s", out.String())
				}
			}
		})
	}
}

func TestDefinitionFallbackAndCap(t *testing.T) {
	marker := &definitionLocalRun{}
	err := attachDefinitionReceipt(syscall.EACCES, nil, marker)
	if !errors.Is(err, syscall.EACCES) || !strings.Contains(err.Error(), `"expected":null`) || !strings.Contains(err.Error(), `"local":null`) {
		t.Fatal(err)
	}
	for _, n := range []int{262144, 262145} {
		raw := bytes.Repeat([]byte("a"), n)
		err := attachDefinitionReceipt(syscall.EPERM, raw, marker)
		raw[0] = 'z'
		if !errors.Is(err, syscall.EPERM) {
			t.Fatal(err)
		}
		if n == 262144 && !strings.Contains(err.Error(), "failed_definition_receipt:a") {
			t.Fatal("receipt was not copied")
		}
		if n == 262145 && !strings.Contains(err.Error(), "omitted_over_budget") {
			t.Fatal("missing omission marker")
		}
	}
}

func TestDefinitionRunReturnDeferBeforeNativeStartup(t *testing.T) {
	old := GadgetPatchSHA
	GadgetPatchSHA = ""
	t.Cleanup(func() { GadgetPatchSHA = old })
	cfg := Config{PID: 123, Image: "image@sha256:" + strings.Repeat("a", 64), Store: "unused"}
	if err := cfg.Validate(); err != nil {
		t.Fatal(err)
	}
	err := Run(context.Background(), cfg, io.Discard)
	var receipt definitionReceiptError
	if !errors.As(err, &receipt) || !strings.Contains(err.Error(), "provenance-pinned make build") {
		t.Fatalf("missing cause or receipt: %v", err)
	}
	var decoded struct {
		Expected json.RawMessage         `json:"expected"`
		Final    json.RawMessage         `json:"final"`
		Counts   json.RawMessage         `json:"counts"`
		Local    json.RawMessage         `json:"local"`
		Missing  *definitionLocalMissing `json:"local_missing"`
	}
	if err := json.Unmarshal(receipt.raw, &decoded); err != nil {
		t.Fatal(err)
	}
	if string(decoded.Expected) != "null" || string(decoded.Final) != "null" || string(decoded.Counts) != "null" || string(decoded.Local) != "null" || decoded.Missing == nil || decoded.Missing.Phase != localNotEntered {
		t.Fatalf("unexpected fallback: %s", receipt.raw)
	}
}

func TestDefinitionObservedPendingGuardAndLateCallback(t *testing.T) {
	old := GadgetPatchSHA
	GadgetPatchSHA = "22f66bf56bb3b9a00c47186a53bff07ab932ca48dff8b3900666348685ff5035"
	t.Cleanup(func() { GadgetPatchSHA = old })
	value := validObservationFixture(validBindingFixture())
	state := &definitionAccounting{expected: validBindingFixture()}
	var ops []string
	owner := &callbackTestOwner{observation: symbolizer.DefinitionStopObservation{TraceDefinition: &value}, operations: &ops}
	marker := &definitionLocalRun{}
	started, release, done, finishDone := make(chan struct{}), make(chan struct{}), make(chan struct{}), make(chan struct{})
	ctx, cancel := context.WithCancel(context.Background())
	var releaseOnce sync.Once
	var callbackStarted bool
	var callbackErr error
	t.Cleanup(func() {
		cancel(); releaseOnce.Do(func() { close(release) })
		if callbackStarted {
			capturePublishModelJoin(t, "pending callback", done)
			select { case <-started: capturePublishModelJoin(t, "pending finish", finishDone); default: }
		}
	})
	ok := func() error { return nil }
	callback := newDefinitionAfterReadout(ctx, ok, ok, owner, &state, marker, func() error { defer close(finishDone); close(started); <-release; return nil })
	callbackStarted = true
	go func() { defer close(done); callbackErr = callback(ctx) }()
	if !capturePublishModelJoin(t, "pending start", started) { return }
	first := marker.closeMissing()
	pending := attachDefinitionReceipt(errors.Join(errReadoutPending, syscall.EACCES), nil, marker)
	cfg := Config{ExpectedTarget: wire.Target{PID: 123, StartTicks: 456, CgroupID: 789, MountNS: 42}}
	var output bytes.Buffer
	err := runObservedWith(context.Background(), cfg, &output, func(context.Context, Config, io.Writer) error { return pending })
	if !errors.Is(err, errReadoutPending) || !errors.Is(err, syscall.EACCES) || !strings.Contains(err.Error(), `"final":null`) {
		t.Fatalf("lost pending receipt: %v", err)
	}
	lines := bytes.Split(bytes.TrimSpace(output.Bytes()), []byte{'\n'})
	if len(lines) != 1 {
		t.Fatalf("terminal record while pending: %s", output.String())
	}
	var record wire.Record
	if err := json.Unmarshal(lines[0], &record); err != nil || record.Kind != "session_started" {
		t.Fatalf("not startup only: %s %v", output.String(), err)
	}
	var production bytes.Buffer
	if err := writeProductionFailure(newProductionSink(&production), Config{}, pending); !errors.Is(err, errReadoutPending) || production.Len() != 0 {
		t.Fatalf("production published pending: %v %q", err, production.String())
	}
	releaseOnce.Do(func() { close(release) })
	finishJoined := capturePublishModelJoin(t, "pending finish", finishDone)
	callbackJoined := capturePublishModelJoin(t, "pending callback", done)
	if !finishJoined || !callbackJoined { return }
	if callbackErr == nil { t.Fatal("late trace-only callback acquired combined authority") }
	if marker.closeMissing() != first || first.Phase != localEnrichment {
		t.Fatalf("late callback changed marker: %+v", marker.closeMissing())
	}
	output.Reset()
	nonpending := attachDefinitionReceipt(syscall.EACCES, nil, marker)
	err = runObservedWith(context.Background(), cfg, &output, func(context.Context, Config, io.Writer) error { return nonpending })
	lines = bytes.Split(bytes.TrimSpace(output.Bytes()), []byte{'\n'})
	if !errors.Is(err, syscall.EACCES) || len(lines) != 2 {
		t.Fatalf("missing terminal failure: %v %s", err, output.String())
	}
	if err := json.Unmarshal(lines[1], &record); err != nil || record.Kind != "collector_error" || record.Lifecycle == nil || record.Lifecycle.Errno != int(syscall.EACCES) {
		t.Fatalf("invalid terminal failure: %s %v", output.String(), err)
	}
}

func TestDefinitionReceiptUTF8CapAndWriterFailures(t *testing.T) {
	for _, size := range []int{262144, 262145} {
		prefix, suffix := `{"expected":null,"final":null,"counts":null,"local":null,"local_missing":{"phase":0,"reason":"local_readout_not_installed_safety_gate"},"value":"`, `"}`
		raw := []byte(prefix + strings.Repeat("a", size-len(prefix)-len(suffix)-2) + "\u00e9" + suffix)
		if len(raw) != size || !json.Valid(raw) {
			t.Fatal("bad boundary fixture")
		}
		err := attachDefinitionReceipt(syscall.EACCES, raw, &definitionLocalRun{})
		var receipt definitionReceiptError
		if !errors.As(err, &receipt) || !errors.Is(err, syscall.EACCES) {
			t.Fatal(err)
		}
		if size == 262144 && !bytes.Equal(receipt.raw, raw) {
			t.Fatal("boundary receipt changed")
		}
		if size == 262145 && (receipt.raw != nil || !strings.Contains(err.Error(), "omitted_over_budget")) {
			t.Fatal("missing actual budget omission")
		}
	}
	cause := attachDefinitionReceipt(errors.Join(syscall.EACCES, context.Canceled), nil, &definitionLocalRun{})
	again := attachDefinitionReceipt(cause, []byte("ignored"), &definitionLocalRun{})
	if !errors.Is(again, syscall.EACCES) || strings.Count(again.Error(), "failed_definition_receipt:") != 1 || attachDefinitionReceipt(nil, nil, &definitionLocalRun{}) != nil {
		t.Fatal("duplicate receipt or invented error")
	}
	for _, observed := range []bool{false, true} {
		var err error
		if observed {
			err = writeObservedFailure(Config{}, newObservedSink(failedSummaryWriter{}), cause)
		} else {
			err = writeProductionFailure(newProductionSink(failedSummaryWriter{}), Config{}, cause)
		}
		if !errors.Is(err, syscall.EACCES) || !errors.Is(err, context.Canceled) || !errors.Is(err, syscall.ENOSPC) {
			t.Fatalf("lost writer or original error: %v", err)
		}
	}
	marshalCause := errors.Join(syscall.EPERM, errDefinitionReceiptMarshal)
	got := attachDefinitionReceipt(marshalCause, nil, &definitionLocalRun{})
	if !errors.Is(got, syscall.EPERM) || !errors.Is(got, errDefinitionReceiptMarshal) || strings.Contains(got.Error(), "omitted_over_budget") {
		t.Fatal(got)
	}
}

func TestDefinitionReturnBoundaryPreservesRawAndCleanupCauses(t *testing.T) {
	old := GadgetPatchSHA
	GadgetPatchSHA = "22f66bf56bb3b9a00c47186a53bff07ab932ca48dff8b3900666348685ff5035"
	t.Cleanup(func() { GadgetPatchSHA = old })
	cfg := Config{PID: 123, Image: "image@sha256:" + strings.Repeat("a", 64), Store: "unused"}
	cleanup, fatal := errors.New("cleanup failure"), errors.New("fatal failure")
	var assembled []byte
	var summary model.Summary
	err := runWithDefinitionReceipt(context.Background(), cfg, io.Discard, func(_ context.Context, _ Config, _ io.Writer, local *definitionLocalRun, raw *[]byte) (result error) {
		defer func() { result = errors.Join(result, cleanup) }()
		defer func() { result = errors.Join(result, fatal) }()
		value := validObservationFixture(validBindingFixture())
		state := &definitionAccounting{expected: validBindingFixture()}
		var ops []string
		owner := &callbackTestOwner{observation: symbolizer.DefinitionStopObservation{TraceDefinition: &value}, operations: &ops}
		checks := 0
		exec := func() error {
			checks++
			if checks == 2 {
				return syscall.EACCES
			}
			return nil
		}
		ok := func() error { return nil }
		result = newDefinitionAfterReadout(context.Background(), ok, exec, owner, &state, local, ok)(context.Background())
		if !errors.Is(result, syscall.EACCES) {
			t.Errorf("callback lost operational cause: %v", result)
		}
		if err := applyDefinitionAccounting(state, local, &summary); err == nil {
			t.Error("trace-only diagnostic acquired native authority")
		}
		assembled = append([]byte(nil), summary.Extensions["definition_accounting"]...)
		*raw = append([]byte(nil), assembled...)
		return result
	})
	for _, cause := range []error{syscall.EACCES, cleanup, fatal} {
		if !errors.Is(err, cause) {
			t.Fatalf("lost %v: %v", cause, err)
		}
	}
	var receipt definitionReceiptError
	if !errors.As(err, &receipt) || len(assembled) == 0 || !bytes.Equal(receipt.raw, assembled) || strings.Count(err.Error(), "failed_definition_receipt:") != 1 {
		t.Fatalf("return boundary changed or duplicated receipt: %v", err)
	}
	for _, observed := range []bool{false, true} {
		t.Run(map[bool]string{false: "production", true: "observed"}[observed], func(t *testing.T) {
			summarySink := &recordSink{}
			summaryCfg := Config{production: !observed}
			if observed {
				summaryCfg.profile = wire.Observed25
			}
			if got := writeSessionSummary(summaryCfg, summarySink, "test", summary, err); !errors.Is(got, cleanup) || len(summarySink.snapshot()) != 0 {
				t.Fatalf("summary suppression lost cause: %v", got)
			}
			cfg := Config{sessionID: "test", PID: 123, ExpectedTarget: wire.Target{PID: 123, StartTicks: 456, CgroupID: 789, MountNS: 42}}
			var out bytes.Buffer
			var published, failed error
			if observed {
				published = writeObservedFailure(cfg, newObservedSink(&out), err)
				failed = writeObservedFailure(cfg, newObservedSink(failedSummaryWriter{}), err)
			} else {
				published = writeProductionFailure(newProductionSink(&out), cfg, err)
				failed = writeProductionFailure(newProductionSink(failedSummaryWriter{}), cfg, err)
			}
			for _, cause := range []error{syscall.EACCES, cleanup, fatal} {
				if !errors.Is(published, cause) || !errors.Is(failed, cause) {
					t.Fatalf("publisher lost %v: %v / %v", cause, published, failed)
				}
			}
			if !errors.Is(failed, syscall.ENOSPC) {
				t.Fatalf("lost writer error: %v", failed)
			}
			var record wire.Record
			if e := json.Unmarshal(bytes.TrimSpace(out.Bytes()), &record); e != nil || record.Lifecycle == nil || record.Lifecycle.Errno != int(syscall.EACCES) {
				t.Fatalf("invalid failure record: %s (%v)", out.String(), e)
			}
			parts := strings.SplitN(record.Lifecycle.Message, "\nfailed_definition_receipt:", 2)
			if len(parts) != 2 || !bytes.Equal([]byte(parts[1]), assembled) {
				t.Fatalf("wire receipt changed: %s", out.String())
			}
		})
	}
}

func TestDefinitionReturnBoundaryPendingSuppression(t *testing.T) {
	old := GadgetPatchSHA
	GadgetPatchSHA = "22f66bf56bb3b9a00c47186a53bff07ab932ca48dff8b3900666348685ff5035"
	t.Cleanup(func() { GadgetPatchSHA = old })
	cfg := Config{PID: 123, Image: "image@sha256:" + strings.Repeat("a", 64), Store: "unused"}
	cleanup := errors.New("pending cleanup")
	err := runWithDefinitionReceipt(context.Background(), cfg, io.Discard, func(_ context.Context, _ Config, _ io.Writer, _ *definitionLocalRun, _ *[]byte) (result error) {
		defer func() { result = errors.Join(result, cleanup) }()
		return errors.Join(errReadoutPending, syscall.EACCES)
	})
	var receipt definitionReceiptError
	if !errors.As(err, &receipt) || !errors.Is(err, errReadoutPending) || !errors.Is(err, syscall.EACCES) || !errors.Is(err, cleanup) || !strings.Contains(string(receipt.raw), `"expected":null`) || !strings.Contains(string(receipt.raw), `"final":null`) {
		t.Fatalf("lost pending cause or honest receipt: %v", err)
	}
	var prod bytes.Buffer
	if got := writeProductionFailure(newProductionSink(&prod), cfg, err); !errors.Is(got, cleanup) || prod.Len() != 0 {
		t.Fatalf("pending production emitted terminal record: %v %s", got, prod.String())
	}
	var observed bytes.Buffer
	cfg.ExpectedTarget = wire.Target{PID: 123, StartTicks: 456, CgroupID: 789, MountNS: 42}
	got := runObservedWith(context.Background(), cfg, &observed, func(context.Context, Config, io.Writer) error { return err })
	lines := bytes.Split(bytes.TrimSpace(observed.Bytes()), []byte{'\n'})
	if !errors.Is(got, errReadoutPending) || !errors.Is(got, cleanup) || len(lines) != 1 {
		t.Fatalf("pending observed emitted terminal record: %v %s", got, observed.String())
	}
	var record wire.Record
	if e := json.Unmarshal(lines[0], &record); e != nil || record.Kind != "session_started" {
		t.Fatalf("unexpected pending record: %s (%v)", observed.String(), e)
	}
}

func TestDefinitionPublishMissingTransferPendingReceipt(t *testing.T) {
	// Rejection coverage: missing cross-package transfer keeps definitions pending; does NOT admit all-source Definitions.
	cfg := Config{
		ExpectedTarget: wire.Target{PID: 1234, StartTicks: 5678, CgroupID: 9012, MountNS: 42},
	}
	var out bytes.Buffer
	receiptErr := definitionReceiptError{
		cause: errReadoutPending,
		raw:   []byte(`{"expected":null,"final":null,"missing_transfer":true}`),
	}
	got := runObservedWith(context.Background(), cfg, &out, func(context.Context, Config, io.Writer) error {
		return receiptErr
	})
	if !errors.Is(got, errReadoutPending) {
		t.Fatalf("expected pending error, got %v", got)
	}
}

func TestCapturePublishModelUnexercisedMissingNativeRetainsRawReceipt(t *testing.T) {
	binding := validBindingFixture()
	trace := validObservationFixture(binding)
	for n := range trace.Rows { trace.Rows[n] = libpf.TraceDefinitionCPUCounts{} }
	trace.Reader = libpf.TraceDefinitionReaderCounts{}
	trace.Undelivered, trace.EmissionLoss = 0, 0
	trace.Outcome = libpf.TraceDefinitionNotExercised
	aux := tracer.DefinitionAuxiliaryObservation{}
	state := &definitionAccounting{expected: binding}
	obs := symbolizer.DefinitionStopObservation{ReaderStopped: true, ConsumerStopped: true, BuffersDrained: true, AuxiliaryStopped: true, TraceDefinition: &trace, Auxiliary: &aux}
	if err := state.recordFinal(obs, nil); err == nil { t.Fatal("unexercised Missing native receipt was accepted") }
	var summary model.Summary
	err := applyDefinitionAccounting(state, nil, &summary)
	if err == nil { t.Fatal("unaccepted combined receipt published as known inventory") }
	raw := summary.Extensions["definition_accounting"]
	if len(raw) == 0 { t.Fatal("raw receipt missing") }
	var receipt definitionReceiptError
	if !errors.As(err, &receipt) || !bytes.Equal(receipt.raw, raw) { t.Fatalf("original receipt bytes not retained: %v", err) }
	var decoded struct {
		Expected libpf.TraceDefinitionBinding `json:"expected"`
		Final libpf.TraceDefinitionObservation `json:"final"`
		Counts json.RawMessage `json:"counts"`
		Error string `json:"error"`
	}
	if err := json.Unmarshal(raw, &decoded); err != nil { t.Fatal(err) }
	if decoded.Expected != binding || decoded.Final.Outcome != libpf.TraceDefinitionNotExercised || len(decoded.Final.Rows) != binding.PossibleCPUs || len(decoded.Counts) != 0 || decoded.Error == "" { t.Fatalf("unexercised receipt changed: %+v", decoded) }
	var fields map[string]json.RawMessage
	if err := json.Unmarshal(raw, &fields); err != nil { t.Fatal(err) }
	if _, exists := fields["auxiliary"]; exists { t.Fatal("opaque auxiliary leaked into receipt JSON") }
}

func TestCapturePublishModelUnknownRootAndOriginalReceiptBudget(t *testing.T) {
	binding := validBindingFixture()
	trace := validObservationFixture(binding)
	expectedJSON, err := json.Marshal(binding)
	if err != nil { t.Fatal(err) }
	traceJSON, err := json.Marshal(trace)
	if err != nil { t.Fatal(err) }
	// The extra object is untrusted input, not a native receipt or a schema field.
	raw := bytes.Join([][]byte{[]byte("  { \"expected\": "), expectedJSON, []byte(", \"final\": "), traceJSON, []byte(", \"untrusted_extension\": { \"claim\": \"complete\", \"label\": \"é\" } } ")}, nil)
	var input struct {
		Expected libpf.TraceDefinitionBinding `json:"expected"`
		Final libpf.TraceDefinitionObservation `json:"final"`
	}
	if err := json.Unmarshal(raw, &input); err != nil { t.Fatal(err) }
	if input.Expected != binding { t.Fatalf("external expected binding changed: %+v", input.Expected) }
	if _, err := definitionDiagnostic(binding, input.Final); err != nil { t.Fatalf("trace facts invalid: %v", err) }
	state := &definitionAccounting{expected: binding}
	if err := state.recordFinal(symbolizer.DefinitionStopObservation{ReaderStopped: true, ConsumerStopped: true, BuffersDrained: true, AuxiliaryStopped: true, TraceDefinition: &input.Final}, nil); err == nil { t.Fatal("unknown root promoted trace to native authority") }
	if _, err := state.Diagnostic(); err == nil { t.Fatal("unknown root produced accepted diagnostic") }
	cause := attachDefinitionReceipt(syscall.EACCES, raw, &definitionLocalRun{})
	var receipt definitionReceiptError
	if !errors.As(cause, &receipt) || !errors.Is(cause, syscall.EACCES) || !bytes.Equal(receipt.raw, raw) { t.Fatalf("original bytes changed: %v", cause) }
	oversized := append(append([]byte(nil), raw...), bytes.Repeat([]byte(" "), 262145)...)
	var compact bytes.Buffer
	if err := json.Compact(&compact, oversized); err != nil { t.Fatal(err) }
	if len(compact.Bytes()) > 262144 || len(oversized) <= 262144 { t.Fatal("bad original-byte budget fixture") }
	cause = attachDefinitionReceipt(syscall.EACCES, oversized, &definitionLocalRun{})
	if !errors.As(cause, &receipt) || !errors.Is(cause, syscall.EACCES) || receipt.raw != nil { t.Fatalf("budget used projected rather than original bytes: %v", cause) }
}
