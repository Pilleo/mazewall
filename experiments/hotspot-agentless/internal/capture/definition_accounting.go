// Copyright 2026 The mazewall Authors
// SPDX-License-Identifier: Apache-2.0

package capture

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math/bits"
	"sync/atomic"
	"time"
	"unicode/utf8"

	cebpf "github.com/cilium/ebpf"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/symbolizer"
	"go.opentelemetry.io/ebpf-profiler/libpf"
	"go.opentelemetry.io/ebpf-profiler/periodiccaller"
	"go.opentelemetry.io/ebpf-profiler/tracer"
	"io.mazewall/experiments/hotspot-agentless/internal/accounting"
)

func withDefinitionStartup(enabled bool, provider any, ready func(*definitionAccounting) error) error {
	var state *definitionAccounting
	if enabled {
		binding, err := definitionExpected(provider)
		if err != nil {
			return err
		}
		state = &definitionAccounting{expected: binding}
	}
	return ready(state)
}

func withDefinitionDrain(ctx context.Context, drainer interface {
	DrainDefinitions(context.Context) (symbolizer.DefinitionStopObservation, error)
	StopDefinitions(context.Context) (symbolizer.DefinitionStopObservation, error)
}, state *definitionAccounting, next func(context.Context) error) error {
	if state != nil && state.status != definitionUnrecorded { return state.terminalErr }
	observation, err := drainer.DrainDefinitions(ctx)
	if state == nil {
		return errors.Join(err, fmt.Errorf("trace definition accounting missing expected binding"))
	}
	traceErr := state.rememberTrace(observation.TraceDefinition)
	if err != nil || traceErr != nil {
		return state.reject(errors.Join(err, traceErr))
	}
	if err := ctx.Err(); err != nil { return state.reject(err) }
	if next == nil { return state.reject(errors.New("definition continuation missing")) }
	if err := next(ctx); err != nil { return state.reject(err) }
	if err := ctx.Err(); err != nil { return state.reject(err) }
	stopped, stopErr := drainer.StopDefinitions(ctx)
	if err := ctx.Err(); err != nil { return state.rejectFinalObservation(stopped, errors.Join(stopErr, err)) }
	if stopErr != nil { return state.rejectFinalObservation(stopped, stopErr) }
	return state.recordFinal(stopped, stopErr)
}

type definitionBindingProvider interface {
	TraceDefinitionBinding() (libpf.TraceDefinitionBinding, error)
}

type definitionTerminalState uint8

const (
	definitionUnrecorded definitionTerminalState = iota
	definitionRejected
	definitionAccepted
)

type definitionAccounting struct {
	expected libpf.TraceDefinitionBinding
	final    libpf.TraceDefinitionObservation
	combined symbolizer.DefinitionStopObservation
	status definitionTerminalState
	terminalErr error
	ownerDiagnostic atomic.Pointer[definitionOwnerDiagnostic]
}

type definitionOwnerDiagnostics interface {
	DefinitionSourceDiagnostics() (json.RawMessage, error)
	DefinitionSourceDiagnosticsReady() <-chan struct{}
}

type definitionOwnerDiagnostic struct {
	State string `json:"state"`
	Reason string `json:"reason,omitempty"`
	Raw json.RawMessage `json:"raw,omitempty"`
	Rows *definitionOwnerRows `json:"-"`
}

type queueDiagnosticDecodeReason uint8

const (
	queueDiagnosticAbsent queueDiagnosticDecodeReason = iota
	queueDiagnosticMalformed
	queueDiagnosticOversize
	queueDiagnosticDecoded
)

// definitionQueueDiagnostic is the flat B1 queue leaf, not a source receipt.
type definitionQueueDiagnostic struct {
	Unit string `json:"unit"`
	RequestsOwned uint64 `json:"requests_owned"`
	QueueAccepted uint64 `json:"queue_accepted"`
	AdmissionHeld uint64 `json:"admission_held"`
	Refused uint64 `json:"refused"`
	Applied uint64 `json:"applied"`
	AppliedPublished uint64 `json:"applied_published"`
	AppliedNotPublished uint64 `json:"applied_not_published"`
	Failed uint64 `json:"failed"`
	Aborted uint64 `json:"aborted"`
	Pending uint64 `json:"pending"`
	CleanupFailed uint64 `json:"cleanup_failed"`
	PendingKnown bool `json:"pending_known"`
	AllWorkersEnded bool `json:"all_workers_ended"`
	CallerJoinedClaim bool `json:"caller_joined_claim"`
	ScopeJoinedWitness bool `json:"scope_joined_witness"`
	Held bool `json:"held"`
	LastUnavailable bool `json:"last_unavailable"`
	LastPublished bool `json:"last_published"`
	Outcome uint8 `json:"outcome"`
	ProofErrorPresent bool `json:"proof_error_present"`
	LastApplyErrorPresent bool `json:"last_apply_error_present"`
	LastCleanupErrorPresent bool `json:"last_cleanup_error_present"`
	Raw json.RawMessage `json:"-"`
}

func diagnosticObject(raw json.RawMessage, required, optional []string) (map[string]json.RawMessage, queueDiagnosticDecodeReason) {
	if len(raw) == 0 { return nil, queueDiagnosticAbsent }
	if len(raw) > 32768 { return nil, queueDiagnosticOversize }
	if !utf8.Valid(raw) || !json.Valid(raw) { return nil, queueDiagnosticMalformed }
	reader := json.NewDecoder(bytes.NewReader(raw))
	token, err := reader.Token()
	if err != nil || token != json.Delim('{') { return nil, queueDiagnosticMalformed }
	fields := make(map[string]json.RawMessage)
	for reader.More() {
		key, err := reader.Token()
		if err != nil { return nil, queueDiagnosticMalformed }
		name, ok := key.(string)
		if !ok { return nil, queueDiagnosticMalformed }
		if _, duplicate := fields[name]; duplicate { return nil, queueDiagnosticMalformed }
		allowed := false
		for _, candidate := range required { if name == candidate { allowed = true; break } }
		for _, candidate := range optional { if name == candidate { allowed = true; break } }
		if !allowed { return nil, queueDiagnosticMalformed }
		var value json.RawMessage
		if reader.Decode(&value) != nil || bytes.Equal(bytes.TrimSpace(value), []byte("null")) { return nil, queueDiagnosticMalformed }
		fields[name] = value
	}
	token, err = reader.Token()
	if err != nil || token != json.Delim('}') { return nil, queueDiagnosticMalformed }
	for _, key := range required { if _, present := fields[key]; !present { return nil, queueDiagnosticMalformed } }
	return fields, queueDiagnosticDecoded
}

func decodeQueueDiagnostic(raw json.RawMessage) (*definitionQueueDiagnostic, queueDiagnosticDecodeReason) {
	required := [...]string{"unit", "requests_owned", "queue_accepted", "admission_held", "refused", "applied", "applied_published", "applied_not_published", "failed", "aborted", "pending", "cleanup_failed", "pending_known", "all_workers_ended", "caller_joined_claim", "scope_joined_witness", "held", "last_unavailable", "last_published", "outcome", "proof_error_present", "last_apply_error_present", "last_cleanup_error_present"}
	if _, reason := diagnosticObject(raw, required[:], nil); reason != queueDiagnosticDecoded { return nil, reason }
	var result definitionQueueDiagnostic
	reader := json.NewDecoder(bytes.NewReader(raw))
	reader.DisallowUnknownFields()
	if reader.Decode(&result) != nil || result.Unit != "owned-map-update-request" || result.Outcome < 1 || result.Outcome > 3 { return nil, queueDiagnosticMalformed }
	result.Raw = append(json.RawMessage(nil), raw...)
	return &result, queueDiagnosticDecoded
}

type definitionProcessingBindingState uint8

const (
	processingBindingAbsent definitionProcessingBindingState = iota
	processingBindingUnrepresentable
	processingBindingRepresented
)

type definitionProcessingBinding struct {
	Source uint8 `json:"source"`
	Session string `json:"session"`
	HostTGID uint32 `json:"host_tgid"`
	Birth uint64 `json:"birth"`
	CounterMapID uint32 `json:"counter_map_id"`
	PerfMapID uint32 `json:"perf_map_id"`
	PossibleCPUs int64 `json:"possible_cpus"`
	Generation uint64 `json:"generation"`
}
type definitionProcessingTarget struct {
	PID uint32 `json:"pid"`
	ProcessStartTicks uint64 `json:"process_start_ticks"`
	CounterAddress uint64 `json:"counter_address"`
	InitialEpoch uint64 `json:"initial_epoch"`
}
type definitionProcessingIdentity struct {
	Epoch uint64 `json:"epoch"`
	State uint8 `json:"state"`
	ReadError int32 `json:"read_error"`
}
type definitionProcessingTrace struct {
	PID uint32 `json:"pid"`
	TID uint32 `json:"tid"`
	CPU int64 `json:"cpu"`
	Origin uint64 `json:"origin"`
	KTime int64 `json:"ktime"`
	CorrelationID uint64 `json:"correlation_id"`
	HotSpotIdentity definitionProcessingIdentity `json:"hotspot_identity"`
}
type definitionProcessingDiagnostic struct {
	Unit string `json:"unit"`
	HasBinding bool `json:"has_binding"`
	ExpectedBinding *definitionProcessingBinding `json:"expected_binding,omitempty"`
	EpochTarget definitionProcessingTarget `json:"epoch_target"`
	ObservedPID uint32 `json:"observed_pid"`
	Admitted uint64 `json:"admitted"`
	PreReporterRejected uint64 `json:"pre_reporter_rejected"`
	ReporterOK uint64 `json:"reporter_ok"`
	ReporterError uint64 `json:"reporter_error"`
	InFlight uint64 `json:"in_flight"`
	TargetBound uint64 `json:"target_bound"`
	ForeignToBinding uint64 `json:"foreign_to_binding"`
	BindingUnknown uint64 `json:"binding_unknown"`
	Overflow bool `json:"overflow"`
	HasLastAdmitted bool `json:"has_last_admitted"`
	LastAdmitted *definitionProcessingTrace `json:"last_admitted,omitempty"`
	HasLastClassified bool `json:"has_last_classified"`
	LastClassified *definitionProcessingTrace `json:"last_classified,omitempty"`
	LastTerminal uint8 `json:"last_terminal"`
	Outcome uint8 `json:"outcome"`
	ReporterErrorPresent bool `json:"reporter_error_present"`
	ProofErrorPresent bool `json:"proof_error_present"`
	GetterErrorPresent bool `json:"getter_error_present"`
	BindingState definitionProcessingBindingState `json:"-"`
	Raw json.RawMessage `json:"-"`
}

func decodeProcessingDiagnostic(raw json.RawMessage) (*definitionProcessingDiagnostic, queueDiagnosticDecodeReason) {
	required := []string{"unit", "has_binding", "epoch_target", "observed_pid", "admitted", "pre_reporter_rejected", "reporter_ok", "reporter_error", "in_flight", "target_bound", "foreign_to_binding", "binding_unknown", "overflow", "has_last_admitted", "has_last_classified", "last_terminal", "outcome", "reporter_error_present", "proof_error_present", "getter_error_present"}
	fields, reason := diagnosticObject(raw, required, []string{"expected_binding", "last_admitted", "last_classified"})
	if reason != queueDiagnosticDecoded { return nil, reason }
	if _, reason = diagnosticObject(fields["epoch_target"], []string{"pid", "process_start_ticks", "counter_address", "initial_epoch"}, nil); reason != queueDiagnosticDecoded { return nil, queueDiagnosticMalformed }
	if binding, present := fields["expected_binding"]; present {
		if _, reason = diagnosticObject(binding, []string{"source", "session", "host_tgid", "birth", "counter_map_id", "perf_map_id", "possible_cpus", "generation"}, nil); reason != queueDiagnosticDecoded { return nil, queueDiagnosticMalformed }
	}
	for _, key := range []string{"last_admitted", "last_classified"} {
		if trace, present := fields[key]; present {
			parts, reason := diagnosticObject(trace, []string{"pid", "tid", "cpu", "origin", "ktime", "correlation_id", "hotspot_identity"}, nil)
			if reason != queueDiagnosticDecoded { return nil, queueDiagnosticMalformed }
			if _, reason = diagnosticObject(parts["hotspot_identity"], []string{"epoch", "state", "read_error"}, nil); reason != queueDiagnosticDecoded { return nil, queueDiagnosticMalformed }
		}
	}
	var result definitionProcessingDiagnostic
	reader := json.NewDecoder(bytes.NewReader(raw)); reader.DisallowUnknownFields()
	if reader.Decode(&result) != nil || result.Unit != "handle-trace-input" || result.Outcome < 1 || result.Outcome > 3 || result.LastTerminal > 3 { return nil, queueDiagnosticMalformed }
	if result.HasLastAdmitted != (result.LastAdmitted != nil) || result.HasLastClassified != (result.LastClassified != nil) { return nil, queueDiagnosticMalformed }
	for _, trace := range []*definitionProcessingTrace{result.LastAdmitted, result.LastClassified} {
		if trace != nil && trace.HotSpotIdentity.State > 6 { return nil, queueDiagnosticMalformed }
	}
	if result.ExpectedBinding != nil {
		if !result.HasBinding || len(result.ExpectedBinding.Session) > 256 || !utf8.ValidString(result.ExpectedBinding.Session) || result.ExpectedBinding.PossibleCPUs < 0 { return nil, queueDiagnosticMalformed }
		result.BindingState = processingBindingRepresented
	} else if result.HasBinding {
		result.BindingState = processingBindingUnrepresentable
	}
	result.Raw = append(json.RawMessage(nil), raw...)
	return &result, queueDiagnosticDecoded
}

type ownerLeafLocalReason uint8

const (
	ownerLeafUnknown ownerLeafLocalReason = iota
	ownerLeafOverflow
	ownerLeafInvalid
	ownerLeafEquationMismatch
)

type definitionOwnerIdentity struct {
	Epoch uint64 `json:"epoch"`
	State uint64 `json:"state"`
	ReadError int32 `json:"read_error"`
}
type definitionChannelLast struct {
	PID uint32 `json:"pid"`
	TID uint32 `json:"tid"`
	CPU int64 `json:"cpu"`
	Origin uint64 `json:"origin"`
	KTime int64 `json:"k_time"`
	CorrelationID uint64 `json:"correlation_id"`
	HotSpotIdentity definitionOwnerIdentity `json:"hotspot_identity"`
}
type definitionChannelDiagnostic struct {
	Unit string `json:"unit"`
	NilBarrier uint64 `json:"nil_barrier"`
	NonNilToHandleTrace uint64 `json:"non_nil_to_handle_trace"`
	CloseExit uint64 `json:"close_exit"`
	CancelExit uint64 `json:"cancel_exit"`
	Overflow bool `json:"overflow"`
	Retired bool `json:"retired"`
	Last definitionChannelLast `json:"last"`
	LocalReason ownerLeafLocalReason `json:"-"`
	Raw json.RawMessage `json:"-"`
}
type definitionStoreLast struct {
	ID uint64 `json:"id"`
	PID uint32 `json:"pid"`
	TID uint32 `json:"tid"`
	HotSpotIdentity definitionOwnerIdentity `json:"hotspot_identity"`
	Terminal uint64 `json:"terminal"`
}
type definitionStoreDiagnostic struct {
	Unit string `json:"unit"`
	Inputs uint64 `json:"inputs"`
	InFlight uint64 `json:"in_flight"`
	InvalidIdentity uint64 `json:"invalid_identity"`
	IdentityValidate uint64 `json:"identity_validate"`
	InvalidChecked uint64 `json:"invalid_checked"`
	Mismatch uint64 `json:"mismatch"`
	NewDistinct uint64 `json:"new_distinct"`
	PreserveChecked uint64 `json:"preserve_checked"`
	Dedup uint64 `json:"dedup"`
	Overflow bool `json:"overflow"`
	Invalid bool `json:"invalid"`
	Last definitionStoreLast `json:"last"`
	LocalReason ownerLeafLocalReason `json:"-"`
	Raw json.RawMessage `json:"-"`
}

func decodeChannelDiagnostic(raw json.RawMessage) (*definitionChannelDiagnostic, queueDiagnosticDecodeReason) {
	fields, reason := diagnosticObject(raw, []string{"unit", "nil_barrier", "non_nil_to_handle_trace", "close_exit", "cancel_exit", "overflow", "retired", "last"}, nil)
	if reason != queueDiagnosticDecoded { return nil, reason }
	last, reason := diagnosticObject(fields["last"], []string{"pid", "tid", "cpu", "origin", "k_time", "correlation_id", "hotspot_identity"}, nil)
	if reason != queueDiagnosticDecoded { return nil, queueDiagnosticMalformed }
	if _, reason = diagnosticObject(last["hotspot_identity"], []string{"epoch", "state", "read_error"}, nil); reason != queueDiagnosticDecoded { return nil, queueDiagnosticMalformed }
	var result definitionChannelDiagnostic
	reader := json.NewDecoder(bytes.NewReader(raw)); reader.DisallowUnknownFields()
	if reader.Decode(&result) != nil || result.Unit != "completed-receive" || result.Last.HotSpotIdentity.State > 6 { return nil, queueDiagnosticMalformed }
	if result.Overflow { result.LocalReason = ownerLeafOverflow }
	result.Raw = append(json.RawMessage(nil), raw...)
	return &result, queueDiagnosticDecoded
}

func decodeStoreDiagnostic(raw json.RawMessage) (*definitionStoreDiagnostic, queueDiagnosticDecodeReason) {
	fields, reason := diagnosticObject(raw, []string{"unit", "inputs", "in_flight", "invalid_identity", "identity_validate", "invalid_checked", "mismatch", "new_distinct", "preserve_checked", "dedup", "overflow", "invalid", "last"}, nil)
	if reason != queueDiagnosticDecoded { return nil, reason }
	last, reason := diagnosticObject(fields["last"], []string{"id", "pid", "tid", "hotspot_identity", "terminal"}, nil)
	if reason != queueDiagnosticDecoded { return nil, queueDiagnosticMalformed }
	if _, reason = diagnosticObject(last["hotspot_identity"], []string{"epoch", "state", "read_error"}, nil); reason != queueDiagnosticDecoded { return nil, queueDiagnosticMalformed }
	var result definitionStoreDiagnostic
	reader := json.NewDecoder(bytes.NewReader(raw)); reader.DisallowUnknownFields()
	if reader.Decode(&result) != nil || result.Unit != "store-call" || result.Last.HotSpotIdentity.State > 6 || result.Last.Terminal > 6 { return nil, queueDiagnosticMalformed }
	if result.Overflow { result.LocalReason = ownerLeafOverflow } else if result.Invalid { result.LocalReason = ownerLeafInvalid } else {
		total := result.InFlight
		for _, n := range [...]uint64{result.InvalidIdentity, result.IdentityValidate, result.InvalidChecked, result.Mismatch, result.NewDistinct, result.PreserveChecked, result.Dedup} {
			var carry uint64
			total, carry = bits.Add64(total, n, 0)
			if carry != 0 { result.LocalReason = ownerLeafOverflow; break }
		}
		if result.LocalReason == ownerLeafUnknown && total != result.Inputs { result.LocalReason = ownerLeafEquationMismatch }
	}
	result.Raw = append(json.RawMessage(nil), raw...)
	return &result, queueDiagnosticDecoded
}

type definitionPruneBinding struct {
	Source uint64 `json:"source"`
	Session string `json:"session"`
	HostTGID uint32 `json:"host_tgid"`
	Birth uint64 `json:"birth"`
	Generation uint64 `json:"generation"`
}
type definitionPruneCounts struct {
	Admitted uint64 `json:"admitted"`
	Completed uint64 `json:"completed"`
	Failed uint64 `json:"failed"`
	Aborted uint64 `json:"aborted"`
	Skipped uint64 `json:"skipped"`
	Incomplete uint64 `json:"incomplete"`
	InFlight uint64 `json:"in_flight"`
	Overflow bool `json:"overflow"`
}
type definitionPruneRow struct {
	Source uint64 `json:"source"`
	Observed bool `json:"observed"`
	HasBinding bool `json:"has_binding"`
	BindingUnrepresentable bool `json:"binding_unrepresentable"`
	Binding definitionPruneBinding `json:"binding"`
	Counts definitionPruneCounts `json:"counts"`
	Outcome uint64 `json:"outcome"`
	HeldWork bool `json:"held_work"`
	ObservationErrorPresent bool `json:"observation_error_present"`
}
type definitionPruneDiagnostic struct {
	Unit string `json:"unit"`
	LoopState string `json:"loop_state"`
	BookPresent bool `json:"book_present"`
	BookFrozen bool `json:"book_frozen"`
	Rows [4]definitionPruneRow `json:"rows"`
	Raw json.RawMessage `json:"-"`
}
type definitionPruneFacts struct {
	Unit string `json:"unit"`
	LoopState string `json:"loop_state"`
	BookPresent bool `json:"book_present"`
	BookFrozen bool `json:"book_frozen"`
	Row definitionPruneRow `json:"row"`
}

func decodePruneDiagnostic(raw json.RawMessage) (*definitionPruneDiagnostic, queueDiagnosticDecodeReason) {
	fields, reason := diagnosticObject(raw, []string{"unit", "loop_state", "book_present", "book_frozen", "rows"}, nil)
	if reason != queueDiagnosticDecoded { return nil, reason }
	var elements []json.RawMessage
	if json.Unmarshal(fields["rows"], &elements) != nil || len(elements) != 4 { return nil, queueDiagnosticMalformed }
	for _, element := range elements {
		row, reason := diagnosticObject(element, []string{"source", "observed", "has_binding", "binding_unrepresentable", "binding", "counts", "outcome", "held_work", "observation_error_present"}, nil)
		if reason != queueDiagnosticDecoded { return nil, queueDiagnosticMalformed }
		if _, reason = diagnosticObject(row["binding"], []string{"source", "session", "host_tgid", "birth", "generation"}, nil); reason != queueDiagnosticDecoded { return nil, queueDiagnosticMalformed }
		if _, reason = diagnosticObject(row["counts"], []string{"admitted", "completed", "failed", "aborted", "skipped", "incomplete", "in_flight", "overflow"}, nil); reason != queueDiagnosticDecoded { return nil, queueDiagnosticMalformed }
	}
	var result definitionPruneDiagnostic
	reader := json.NewDecoder(bytes.NewReader(raw)); reader.DisallowUnknownFields()
	if reader.Decode(&result) != nil || result.Unit != "admitted-prune-callback" { return nil, queueDiagnosticMalformed }
	switch result.LoopState { case "unknown", "not-started", "joined", "first-timeout": default: return nil, queueDiagnosticMalformed }
	for i, row := range result.Rows {
		if row.Source != uint64(i+5) || len(row.Binding.Session) > 256 || !utf8.ValidString(row.Binding.Session) || (row.Observed && (row.Outcome < 1 || row.Outcome > 3)) || (!row.Observed && row.Outcome != 0) { return nil, queueDiagnosticMalformed }
	}
	result.Raw = append(json.RawMessage(nil), raw...)
	return &result, queueDiagnosticDecoded
}

type definitionOwnerRows struct {
	Expected libpf.TraceDefinitionBinding
	Queue *definitionQueueDiagnostic
	Processing *definitionProcessingDiagnostic
	Channel *definitionChannelDiagnostic
	Store *definitionStoreDiagnostic
	Prune *definitionPruneDiagnostic
	QueueInvalid, ProcessingInvalid, ChannelInvalid, StoreInvalid bool
	PruneInvalid bool
	EnvelopeInvalid bool
	Missing []string
}

// Decode the known flat queue/PM/OTel and Symbolizer envelope only.
func decodeDefinitionOwnerRows(raw json.RawMessage, expected libpf.TraceDefinitionBinding) definitionOwnerRows {
	result := definitionOwnerRows{Expected: expected}
	if len(expected.Session) > 1024 { result.Expected = libpf.TraceDefinitionBinding{} }
	fields, reason := diagnosticObject(raw, []string{"schema", "missing"}, []string{"queue", "processing", "channel", "store", "prune"})
	if reason != queueDiagnosticDecoded { result.EnvelopeInvalid = true; return result }
	var schema uint8
	if json.Unmarshal(fields["schema"], &schema) != nil || schema != 1 || json.Unmarshal(fields["missing"], &result.Missing) != nil || result.Missing == nil || len(result.Missing) > 16 {
		result.EnvelopeInvalid = true; return result
	}
	seen := make(map[string]bool)
	for _, missing := range result.Missing {
		switch missing {
		case "queue-binding-absent", "queue-pool-missing", "queue-getter-error", "queue-capability-absent", "processing-source-unverified", "processing-join-unverified", "processing-binding-absent", "epoch-target-absent", "observed-pid-absent", "binding-unrepresentable", "local-snapshot-missing", "first-caller-closed", "child-not-invoked", "child-capability-absent", "child-cache-absent", "child-diagnostic-unknown":
		default: result.EnvelopeInvalid = true; return result
		}
		if seen[missing] { result.EnvelopeInvalid = true; return result }; seen[missing] = true
	}
	if value, ok := fields["queue"]; ok { result.Queue, reason = decodeQueueDiagnostic(value); result.QueueInvalid = reason != queueDiagnosticDecoded }
	if value, ok := fields["processing"]; ok { result.Processing, reason = decodeProcessingDiagnostic(value); result.ProcessingInvalid = reason != queueDiagnosticDecoded }
	if value, ok := fields["channel"]; ok { result.Channel, reason = decodeChannelDiagnostic(value); result.ChannelInvalid = reason != queueDiagnosticDecoded }
	if value, ok := fields["store"]; ok { result.Store, reason = decodeStoreDiagnostic(value); result.StoreInvalid = reason != queueDiagnosticDecoded }
	if value, ok := fields["prune"]; ok { result.Prune, reason = decodePruneDiagnostic(value); result.PruneInvalid = reason != queueDiagnosticDecoded }
	return result
}

type definitionOwnerFacts struct {
	Queue *definitionQueueDiagnostic `json:"queue,omitempty"`
	Processing *definitionProcessingDiagnostic `json:"processing,omitempty"`
	ProcessingBinding string `json:"processing_binding,omitempty"`
	Channel *definitionChannelDiagnostic `json:"channel,omitempty"`
	Store *definitionStoreDiagnostic `json:"store,omitempty"`
	Prune *definitionPruneFacts `json:"prune,omitempty"`
}

func applyDefinitionOwnerRows(p *definitionSourceConservation, rows *definitionOwnerRows) {
	if rows == nil { return }
	var qrow, crow, prow, srow *definitionSourceRow
	var pruneRows [4]*definitionSourceRow
	for i := range p.Rows {
		switch p.Rows[i].Source {
		case "async-queue": qrow = &p.Rows[i]
		case "channel": crow = &p.Rows[i]
		case "processing": prow = &p.Rows[i]
		case "store": srow = &p.Rows[i]
		case "prune-cycle": pruneRows[0] = &p.Rows[i]
		case "prune-scan-otel": pruneRows[1] = &p.Rows[i]
		case "prune-scan-symtab": pruneRows[2] = &p.Rows[i]
		case "prune-scan-debuginfod": pruneRows[3] = &p.Rows[i]
		}
	}
	if qrow == nil || crow == nil || prow == nil || srow == nil { p.Disposition = definitionSourceRejected; p.Missing = append(p.Missing, "owner-row-missing"); return }
	for _, row := range pruneRows { if row == nil { p.Disposition = definitionSourceRejected; p.Missing = append(p.Missing, "prune-row-missing"); return } }
	if rows.EnvelopeInvalid {
		p.Missing = append(p.Missing, "unsupported-or-malformed-owner-envelope")
		p.Disposition = definitionSourceRejected
		for _, row := range []*definitionSourceRow{qrow, crow, prow, srow} { row.Disposition = definitionSourceRejected; row.Missing = append(row.Missing, "unsupported-or-malformed-owner-envelope") }
		for _, row := range pruneRows { row.Disposition = definitionSourceRejected; row.Missing = append(row.Missing, "unsupported-or-malformed-owner-envelope") }
		return
	}
	p.Missing = append(p.Missing, rows.Missing...)
	reject := func(row *definitionSourceRow, reason string) { row.Disposition = definitionSourceRejected; p.Disposition = definitionSourceRejected; row.Missing = append(row.Missing, reason) }
	markAvailable := func(row *definitionSourceRow) { if len(row.Missing) == 0 { row.Missing = append(row.Missing, "native-denominator-unqualified") } else { row.Missing[0] = "native-denominator-unqualified" } }
	check := func(want uint64, values ...uint64) string {
		var total uint64
		for _, value := range values { var carry uint64; total, carry = bits.Add64(total, value, 0); if carry != 0 { return "counter-overflow" } }
		if total != want { return "local-equation-mismatch" }; return ""
	}
	for _, missing := range rows.Missing {
		switch missing {
		case "queue-binding-absent", "queue-pool-missing", "queue-getter-error", "queue-capability-absent": qrow.Missing = append(qrow.Missing, missing)
		case "processing-source-unverified", "processing-join-unverified", "processing-binding-absent", "epoch-target-absent", "observed-pid-absent", "binding-unrepresentable": prow.Missing = append(prow.Missing, missing)
		}
	}
	if rows.QueueInvalid { reject(qrow, "malformed-queue-leaf") } else if rows.Queue != nil {
		q := *rows.Queue; q.Raw = nil; qrow.OwnerFacts = &definitionOwnerFacts{Queue: &q}; markAvailable(qrow)
		if reason := check(q.RequestsOwned, q.QueueAccepted, q.Refused, q.AdmissionHeld); reason != "" { reject(qrow, reason) }
		if q.PendingKnown && !q.Held { if reason := check(q.QueueAccepted, q.Applied, q.AppliedPublished, q.AppliedNotPublished, q.Failed, q.Aborted, q.Pending); reason != "" { reject(qrow, reason) } }
		if q.ProofErrorPresent { reject(qrow, "local-read-failure") }
		if q.Held || q.AdmissionHeld != 0 || q.Pending != 0 { reject(qrow, "held-work") }
	}
	if rows.ChannelInvalid { reject(crow, "malformed-channel-leaf") } else if rows.Channel != nil {
		c := *rows.Channel; c.Raw = nil; crow.OwnerFacts = &definitionOwnerFacts{Channel: &c}; markAvailable(crow)
		if c.Overflow { reject(crow, "counter-overflow") }
		if !c.Retired { crow.Missing = append(crow.Missing, "channel-not-retired") }
	}
	if rows.ProcessingInvalid { reject(prow, "malformed-processing-leaf") } else if rows.Processing != nil {
		v := *rows.Processing; v.Raw = nil
		if v.ExpectedBinding != nil { copy := *v.ExpectedBinding; v.ExpectedBinding = &copy }
		if v.LastAdmitted != nil { copy := *v.LastAdmitted; v.LastAdmitted = &copy }
		if v.LastClassified != nil { copy := *v.LastClassified; v.LastClassified = &copy }
		binding := "absent"; if v.BindingState == processingBindingUnrepresentable { binding = "unrepresentable" }; if v.BindingState == processingBindingRepresented { binding = "represented" }
		prow.OwnerFacts = &definitionOwnerFacts{Processing: &v, ProcessingBinding: binding}; markAvailable(prow)
		if v.Overflow { reject(prow, "counter-overflow") }
		if v.GetterErrorPresent || v.ProofErrorPresent { reject(prow, "local-read-failure") }
		if v.InFlight != 0 { reject(prow, "held-work") }
		if reason := check(v.Admitted, v.PreReporterRejected, v.ReporterOK, v.ReporterError, v.InFlight); reason != "" { reject(prow, reason) }
		if reason := check(v.Admitted, v.TargetBound, v.ForeignToBinding, v.BindingUnknown); reason != "" { reject(prow, reason) }
		if v.BindingState == processingBindingUnrepresentable { prow.Missing = append(prow.Missing, "binding-unrepresentable") } else if v.BindingState == processingBindingAbsent { prow.Missing = append(prow.Missing, "processing-binding-absent") } else if validateTraceDefinitionBinding(rows.Expected) == nil {
			b := v.ExpectedBinding; e := rows.Expected
			if b.Source != uint8(e.Source) || b.Session != e.Session || b.HostTGID != e.HostTGID || b.Birth != e.Birth || b.CounterMapID != e.CounterMapID || b.PerfMapID != e.PerfMapID || b.PossibleCPUs != int64(e.PossibleCPUs) || b.Generation != e.Generation { prow.Binding = definitionSourceRejected; reject(prow, "binding-mismatch") }
		} else { prow.Missing = append(prow.Missing, "binding-expected-unavailable") }
		if validateTraceDefinitionBinding(rows.Expected) == nil && ((v.EpochTarget.PID != 0 && v.EpochTarget.PID != rows.Expected.HostTGID) || (v.EpochTarget.ProcessStartTicks != 0 && v.EpochTarget.ProcessStartTicks != rows.Expected.Birth) || (v.ObservedPID != 0 && v.ObservedPID != rows.Expected.HostTGID)) { reject(prow, "target-mismatch") }
	}
	if rows.StoreInvalid { reject(srow, "malformed-store-leaf") } else if rows.Store != nil {
		s := *rows.Store; s.Raw = nil; srow.OwnerFacts = &definitionOwnerFacts{Store: &s}; markAvailable(srow)
		switch s.LocalReason { case ownerLeafOverflow: reject(srow, "counter-overflow"); case ownerLeafInvalid: reject(srow, "local-invalid"); case ownerLeafEquationMismatch: reject(srow, "local-equation-mismatch") }
		if s.InFlight != 0 { reject(srow, "held-work") }
	}
	if rows.PruneInvalid {
		for _, row := range pruneRows { reject(row, "malformed-prune-leaf") }
	} else if rows.Prune == nil {
		for _, row := range pruneRows { row.Missing = append(row.Missing, "prune-leaf-not-forwarded") }
	} else {
		filtered := p.Missing[:0]
		for _, missing := range p.Missing { if missing != "prune-not-forwarded" { filtered = append(filtered, missing) } }
		p.Missing = filtered
		prune := rows.Prune
		for i, fact := range prune.Rows {
			row := pruneRows[i]
			row.OwnerFacts = &definitionOwnerFacts{Prune: &definitionPruneFacts{Unit: prune.Unit, LoopState: prune.LoopState, BookPresent: prune.BookPresent, BookFrozen: prune.BookFrozen, Row: fact}}
			if !prune.BookPresent { row.Missing = append(row.Missing, "prune-book-not-present") }
			if !prune.BookFrozen { row.Missing = append(row.Missing, "prune-book-not-frozen") }
			if prune.BookFrozen && !prune.BookPresent { reject(row, "prune-book-contradiction") }
			if !fact.Observed {
				row.Missing = append(row.Missing, "prune-kind-not-observed")
				if fact.HasBinding || fact.BindingUnrepresentable || fact.Binding != (definitionPruneBinding{}) || fact.Counts != (definitionPruneCounts{}) || fact.HeldWork || fact.ObservationErrorPresent { reject(row, "prune-unobserved-contradiction") }
				continue
			}
			if !prune.BookFrozen { reject(row, "prune-observation-not-frozen") }
			if fact.HasBinding && fact.BindingUnrepresentable { row.Binding = definitionSourceRejected; reject(row, "binding-mismatch")
			} else if fact.BindingUnrepresentable { row.Missing = append(row.Missing, "prune-binding-unrepresentable"); if fact.Binding != (definitionPruneBinding{}) { row.Binding = definitionSourceRejected; reject(row, "binding-mismatch") }
			} else if !fact.HasBinding { row.Missing = append(row.Missing, "prune-binding-absent"); if fact.Binding != (definitionPruneBinding{}) { row.Binding = definitionSourceRejected; reject(row, "binding-mismatch") }
			} else if fact.Binding.Source != fact.Source || fact.Binding.Session == "" || fact.Binding.HostTGID == 0 || fact.Binding.Birth == 0 || fact.Binding.Generation == 0 { row.Binding = definitionSourceRejected; reject(row, "binding-mismatch") }
			if fact.Counts.Overflow { reject(row, "counter-overflow") }
			if fact.HeldWork || fact.Counts.InFlight != 0 { reject(row, "held-work") }
			if fact.ObservationErrorPresent { reject(row, "local-read-failure") }
			if reason := check(fact.Counts.Admitted, fact.Counts.Completed, fact.Counts.Failed, fact.Counts.Aborted, fact.Counts.Skipped, fact.Counts.Incomplete, fact.Counts.InFlight); reason != "" { reject(row, reason) }
		}
	}
}

// validOwnerDiagnosticHeader checks only the agreed outer schema. Nested
// producer rows remain opaque until their actual schema is available.
func validOwnerDiagnosticHeader(raw json.RawMessage) bool {
	if len(raw) == 0 || len(raw) > 32768 || !utf8.Valid(raw) || !json.Valid(raw) { return false }
	d := json.NewDecoder(bytes.NewReader(raw))
	token, err := d.Token()
	if err != nil || token != json.Delim('{') { return false }
	seen := false
	for d.More() {
		key, err := d.Token()
		if err != nil { return false }
		var value json.RawMessage
		if d.Decode(&value) != nil { return false }
		if key == "schema" {
			if seen || !bytes.Equal(bytes.TrimSpace(value), []byte("1")) { return false }
			seen = true
		}
	}
	token, err = d.Token()
	return err == nil && token == json.Delim('}') && seen
}

func (d *definitionAccounting) retainOwnerDiagnostics(ctx context.Context, owner any, local *definitionLocalRun) {
	d.retainOwnerDiagnosticsAt(ctx, owner, local, time.Now)
}

func (d *definitionAccounting) retainOwnerDiagnosticsAt(ctx context.Context, owner any, local *definitionLocalRun, now func() time.Time) {
	if d == nil { return }
	deadline, hasDeadline := ctx.Deadline()
	done := ctx.Done()
	expired := func() bool {
		if ctx.Err() != nil || (hasDeadline && !now().Before(deadline)) { return true }
		select { case <-done: return true; default: return false }
	}
	pending := &definitionOwnerDiagnostic{State: "pending", Reason: "owner-diagnostic-not-terminal"}
	if !d.ownerDiagnostic.CompareAndSwap(nil, pending) { return }
	if local != nil { local.ownerDiagnostic.Store(pending) }
	commit := func(reason string, raw json.RawMessage) {
		if expired() { reason, raw = "budget-expired", nil }
		result := &definitionOwnerDiagnostic{State: "unknown", Reason: reason}
		if raw != nil {
			copy := append(json.RawMessage(nil), raw...)
			rows := decodeDefinitionOwnerRows(copy, d.expected)
			if expired() { result.Reason = "budget-expired" } else { result.State, result.Reason, result.Raw, result.Rows = "captured", "", copy, &rows }
		}
		d.ownerDiagnostic.Store(result)
		if local != nil { local.ownerDiagnostic.Store(result) }
	}
	if expired() { commit("budget-expired", nil); return }
	if !hasDeadline || done == nil { commit("budget-unavailable", nil); return }
	provider, ok := owner.(definitionOwnerDiagnostics)
	if !ok { commit("capability-absent", nil); return }
	for phase := 0; phase < 8; phase++ {
		if expired() { commit("budget-expired", nil); return }
		ready := provider.DefinitionSourceDiagnosticsReady()
		if expired() { commit("budget-expired", nil); return }
		if ready == nil { commit("unclaimed", nil); return }
		select {
		case <-done: commit("budget-expired", nil); return
		case <-ready:
		}
		if expired() { commit("budget-expired", nil); return }
		next := provider.DefinitionSourceDiagnosticsReady()
		if expired() { commit("budget-expired", nil); return }
		if next == nil { commit("unclaimed", nil); return }
		if next != ready { continue }
		raw, err := provider.DefinitionSourceDiagnostics()
		if expired() { commit("budget-expired", nil); return }
		if err != nil || len(raw) == 0 { commit("child-cache-invalid", nil); return }
		if !validOwnerDiagnosticHeader(raw) { commit("invalid-packet", nil); return }
		if expired() { commit("budget-expired", nil); return }
		next = provider.DefinitionSourceDiagnosticsReady()
		if expired() { commit("budget-expired", nil); return }
		if next == nil { commit("unclaimed", nil); return }
		if next != ready { continue }
		commit("", raw)
		return
	}
	commit("readiness-contract-invalid", nil)
}

// These dispositions cannot certify a source: owner and cross-stage authority
// are not available at the capture boundary.
type definitionSourceDisposition string

const (
	definitionSourceUnknown  definitionSourceDisposition = "unknown"
	definitionSourceRejected definitionSourceDisposition = "rejected"
)

type definitionSourceRow struct {
	Source       string                      `json:"source"`
	Unit         string                      `json:"unit"`
	BindingDomain string                     `json:"binding_domain"`
	Disposition  definitionSourceDisposition `json:"disposition"`
	Enablement   definitionSourceDisposition `json:"enablement"`
	Binding      definitionSourceDisposition `json:"binding_validation"`
	Upstream     definitionSourceDisposition `json:"upstream_to_local"`
	Next         definitionSourceDisposition `json:"local_to_next"`
	Missing      []string                    `json:"missing"`
	ExpectedAux  *libpf.AuxiliaryBinding     `json:"expected_auxiliary_binding,omitempty"`
	Trace        *libpf.TraceDefinitionObservation `json:"trace,omitempty"`
	Scope        *periodiccaller.ScopeLocalSnapshot `json:"scope,omitempty"`
	Kind         *periodiccaller.KindLocalSnapshot `json:"kind,omitempty"`
	KindState    libpf.LocalCopyState `json:"kind_copy_state,omitempty"`
	Mailbox      *tracer.PIDMailboxLocalSnapshot `json:"mailbox,omitempty"`
	Process      *tracer.PIDProcessLocalSnapshot `json:"process,omitempty"`
	KobjectDisposition tracer.KobjectWitnessDisposition `json:"kobject_disposition,omitempty"`
	KobjectPresent bool `json:"kobject_present,omitempty"`
	KobjectErrorPresent bool `json:"kobject_error_present,omitempty"`
	OwnerFacts *definitionOwnerFacts `json:"owner_facts,omitempty"`
}

type definitionSourceConservation struct {
	Disposition definitionSourceDisposition `json:"disposition"`
	Expected libpf.TraceDefinitionBinding `json:"expected_trace_binding"`
	ReaderStopped bool `json:"reader_stopped"`
	ConsumerStopped bool `json:"consumer_stopped"`
	BuffersDrained bool `json:"buffers_drained"`
	AuxiliaryStopped bool `json:"auxiliary_stopped"`
	TerminalErrorPresent bool `json:"terminal_error_present"`
	AuxiliaryPresent bool `json:"auxiliary_present"`
	AuxiliaryDisposition tracer.DefinitionAuxiliaryDisposition `json:"auxiliary_disposition"`
	AuxiliaryReason tracer.DefinitionAuxiliaryReason `json:"auxiliary_reason"`
	Missing []string `json:"missing"`
	Rows []definitionSourceRow `json:"rows"`
	OwnerDiagnostic *definitionOwnerDiagnostic `json:"owner_diagnostic,omitempty"`
}

type definitionSourceAuxiliaryFacts struct {
	Scope periodiccaller.ScopeLocalSnapshot
	Kinds periodiccaller.KindsLocalSnapshot
	Mailbox tracer.PIDMailboxLocalSnapshot
	Process tracer.PIDProcessLocalSnapshot
	ScopePresent, KindsPresent, MailboxPresent, ProcessPresent bool
}

func projectDefinitionSources(d *definitionAccounting) definitionSourceConservation {
	p := definitionSourceConservation{Disposition: definitionSourceUnknown, Missing: []string{"enabled-source-authority-absent", "unknown-extra-source"}}
	add := func(source, unit, missing string) {
		p.Rows = append(p.Rows, definitionSourceRow{Source: source, Unit: unit, Disposition: definitionSourceUnknown, Enablement: definitionSourceUnknown, Binding: definitionSourceUnknown, Upstream: definitionSourceUnknown, Next: definitionSourceUnknown, Missing: []string{missing, "join-unproven"}})
	}
	add("selected", "selected-producer-attempt", "separate-selected-lane")
	add("native-trace", "send-trace-producer-attempt", "native-denominator-unqualified")
	add("native-report-pid", "report-pid-producer-transition", "producer-readout-not-forwarded")
	add("native-report-output", "report-events-output-attempt", "producer-readout-not-forwarded")
	add("pid-mailbox", "returned-pid-key", "mailbox-not-forwarded")
	add("pid-processing", "consumer-input", "processing-not-forwarded")
	add("scope", "admitted-operation", "scope-not-forwarded")
	for _, row := range [][2]string{
		{"metrics", "admitted-callback"}, {"pid-cleanup", "admitted-callback"}, {"interpreter-metrics", "admitted-callback"},
		{"prune-cycle", "scheduled-cycle"}, {"prune-scan-otel", "resolver-scan"}, {"prune-scan-symtab", "resolver-scan"}, {"prune-scan-debuginfod", "resolver-scan"},
		{"kobject-receive", "admitted-receive"}, {"module-queued", "scheduled-request"}, {"module-coalesced", "coalesced-request"},
		{"symbol-queued", "scheduled-request"}, {"symbol-coalesced", "coalesced-request"}, {"module-exec", "executed-job"}, {"symbol-exec", "executed-job"}, {"report-reload", "observed-record"},
	} {
		add(row[0], row[1], "kind-not-forwarded")
	}
	add("async-queue", "owned-map-update-request", "queue-not-forwarded")
	add("channel", "completed-receive", "channel-not-forwarded")
	add("processing", "handle-trace-input", "processing-not-forwarded")
	add("store", "store-call", "store-not-forwarded")
	p.Rows[0].BindingDomain = "selected-lane"
	p.Rows[1].BindingDomain = "native-trace-generation-and-per-cpu-map"
	p.Rows[2].BindingDomain = "global-producer; target-partition-unqualified"
	p.Rows[3].BindingDomain = "global-output; target-partition-unqualified"
	p.Rows[4].BindingDomain = "pid-mailbox-map-and-scope-generation"
	p.Rows[5].BindingDomain = "scope-generation"
	for i := 6; i <= 21; i++ { p.Rows[i].BindingDomain = "scope-generation" }
	for i := 22; i < len(p.Rows); i++ { p.Rows[i].BindingDomain = "owner-binding-not-forwarded" }
	p.Missing = append(p.Missing, "prune-not-forwarded")
	if d == nil {
		p.Missing = append(p.Missing, "combined-not-forwarded")
		return p
	}
	p.Expected = d.expected
	if diagnostic := d.ownerDiagnostic.Load(); diagnostic != nil {
		copy := *diagnostic
		copy.Raw = append(json.RawMessage(nil), diagnostic.Raw...)
		copy.Rows = nil
		p.OwnerDiagnostic = &copy
		applyDefinitionOwnerRows(&p, diagnostic.Rows)
		if diagnostic.Rows == nil {
			for i := range p.Rows { switch p.Rows[i].Source { case "async-queue", "channel", "processing", "store": p.Rows[i].Missing = append(p.Rows[i].Missing, diagnostic.Reason) } }
		}
	} else {
		p.OwnerDiagnostic = &definitionOwnerDiagnostic{State: "unknown", Reason: "not-invoked"}
		for i := range p.Rows { switch p.Rows[i].Source { case "async-queue", "channel", "processing", "store": p.Rows[i].Missing = append(p.Rows[i].Missing, "not-invoked") } }
	}
	if d.status == definitionRejected {
		p.Disposition = definitionSourceRejected
	}
	p.TerminalErrorPresent = d.terminalErr != nil
	if validateTraceDefinitionBinding(d.expected) != nil {
		p.Disposition = definitionSourceRejected
		p.Rows[1].Disposition, p.Rows[1].Binding = definitionSourceRejected, definitionSourceRejected
		p.Rows[1].Missing = append(p.Rows[1].Missing, "expected-binding-invalid")
	}
	s := d.combined
	p.ReaderStopped, p.ConsumerStopped, p.BuffersDrained, p.AuxiliaryStopped = s.ReaderStopped, s.ConsumerStopped, s.BuffersDrained, s.AuxiliaryStopped
	// Bound the owned strings and CPU slice before copying or encoding them.
	if len(d.expected.Session) > 1024 || (s.TraceDefinition != nil && (len(s.TraceDefinition.Binding.Session) > 1024 || len(s.TraceDefinition.Rows) > 4096)) {
		p.Expected = libpf.TraceDefinitionBinding{}
		p.Missing = append(p.Missing, "diagnostic-omitted-over-budget")
		return p
	}
	if s.TraceDefinition != nil {
		t := *s.TraceDefinition
		t.Rows = append([]libpf.TraceDefinitionCPUCounts(nil), t.Rows...)
		p.Rows[1].Trace = &t
		if t.Reader.Overflow {
			p.Disposition = definitionSourceRejected
			p.Rows[1].Disposition = definitionSourceRejected
			p.Rows[1].Missing = append(p.Rows[1].Missing, "held-or-overflow")
		}
		for _, cpu := range t.Rows {
			if cpu.Overflow != 0 {
				p.Disposition = definitionSourceRejected
				p.Rows[1].Disposition = definitionSourceRejected
				p.Rows[1].Missing = append(p.Rows[1].Missing, "held-or-overflow")
				break
			}
		}
		if t.Binding != d.expected {
			p.Disposition = definitionSourceRejected
			p.Rows[1].Disposition, p.Rows[1].Binding = definitionSourceRejected, definitionSourceRejected
			p.Rows[1].Missing = append(p.Rows[1].Missing, "binding-mismatch")
		}
	} else {
		p.Rows[1].Missing = append(p.Rows[1].Missing, "trace-not-forwarded")
	}
	if s.Auxiliary == nil {
		p.Missing = append(p.Missing, "auxiliary-not-forwarded")
		return p
	}
	a := *s.Auxiliary
	p.AuxiliaryPresent, p.AuxiliaryDisposition, p.AuxiliaryReason = true, a.Disposition(), a.Reason()
	scope, scopeOK := a.Scope()
	kinds, kindsOK := a.Kinds()
	mailbox, mailboxOK := a.Mailbox()
	process, processOK := a.Process()
	projectDefinitionAuxiliaryRows(&p, d.expected, definitionSourceAuxiliaryFacts{
		Scope: scope, Kinds: kinds, Mailbox: mailbox, Process: process,
		ScopePresent: scopeOK, KindsPresent: kindsOK, MailboxPresent: mailboxOK, ProcessPresent: processOK,
	})
	if k, ok := a.Kobject(); ok {
		row := &p.Rows[14]
		row.KobjectPresent, row.KobjectDisposition, row.KobjectErrorPresent = true, k.Disposition(), k.Err() != nil
		if row.KobjectErrorPresent || k.Disposition() == tracer.KobjectWitnessRejected {
			p.Disposition, row.Disposition = definitionSourceRejected, definitionSourceRejected
			row.Missing = append(row.Missing, "kobject-read-failure")
		}
	}
	return p
}

func projectDefinitionAuxiliaryRows(p *definitionSourceConservation, expected libpf.TraceDefinitionBinding, facts definitionSourceAuxiliaryFacts) {
	scope, scopeOK := facts.Scope, facts.ScopePresent
	kinds, kindsOK := facts.Kinds, facts.KindsPresent
	mailbox, mailboxOK := facts.Mailbox, facts.MailboxPresent
	process, processOK := facts.Process, facts.ProcessPresent
	if (scopeOK && len(scope.Observation.Binding.Session) > 1024) || (mailboxOK && (len(mailbox.ExpectedBinding.Session) > 1024 || len(mailbox.LifetimeSession) > 1024)) || (processOK && len(process.Binding.Session) > 1024) {
		p.Missing = append(p.Missing, "auxiliary-diagnostic-omitted-over-budget")
		return
	}
	for _, k := range kinds.Kinds {
		if kindsOK && k.Present && len(k.Binding.Session) > 1024 {
			p.Missing = append(p.Missing, "auxiliary-diagnostic-omitted-over-budget")
			return
		}
	}
	reject := func(row *definitionSourceRow, reason string) {
		p.Disposition = definitionSourceRejected
		row.Disposition = definitionSourceRejected
		row.Missing = append(row.Missing, reason)
	}
	if scopeOK {
		row := &p.Rows[6]
		row.Scope = &scope
		row.Missing[0] = "native-denominator-unqualified"
		b := scope.Observation.Binding
		if scope.State == libpf.LocalCopied && (b.Session == "" || b.HostTGID == 0 || b.Birth == 0 || b.Generation == 0 || b.Session != expected.Session || b.HostTGID != expected.HostTGID || b.Birth != expected.Birth || b.Source != libpf.AuxiliarySourcePeriodicCaller) {
			row.Binding = definitionSourceRejected
			reject(row, "binding-mismatch")
		}
		if scope.State != libpf.LocalCopied || scope.ErrorPresent { reject(row, "local-read-failure") }
		if scope.Observation.HeldWork || scope.Observation.Counts.Overflow || scope.Observation.Counts.InFlight != 0 { reject(row, "held-or-overflow") }
	}
	if kindsOK {
		for i, kind := range kinds.Kinds {
			row := &p.Rows[7+i]
			row.KindState = kinds.State
			if kinds.State != libpf.LocalCopied { reject(row, "local-read-failure") }
			if !kind.Present { continue }
			k := kind
			row.Kind = &k
			row.Missing[0] = "native-denominator-unqualified"
			if kind.Source != libpf.AuxiliarySource(i+2) {
				row.Binding = definitionSourceRejected
				reject(row, "binding-mismatch")
			}
			if scopeOK && scope.State == libpf.LocalCopied {
				expected := scope.Observation.Binding
				expected.Source = libpf.AuxiliarySource(i + 2)
				row.ExpectedAux = &expected
				if (kind.Binding != (libpf.AuxiliaryBinding{}) || kind.Counts.Admitted != 0) && kind.Binding != expected {
					row.Binding = definitionSourceRejected
					reject(row, "binding-mismatch")
				}
			}
			if kind.ErrorPresent { reject(row, "local-read-failure") }
			if kind.HeldWork || kind.Counts.Overflow || kind.Counts.InFlight != 0 { reject(row, "held-or-overflow") }
		}
	}
	if mailboxOK {
		row := &p.Rows[4]
		row.Mailbox = &mailbox
		row.Missing[0] = "producer-readout-not-forwarded"
		if mailbox.State != libpf.LocalCopied || mailbox.ErrorPresent || mailbox.InfoError || mailbox.ScanIncomplete || mailbox.UnpolledResidualUnknown || mailbox.Outcome == tracer.PIDMailboxFailed { reject(row, "local-read-failure") }
		if mailbox.Counts.Overflow || mailbox.Counts.HeldResidual != 0 { reject(row, "held-or-overflow") }
		m := mailbox.ExpectedBinding
		if mailbox.State == libpf.LocalCopied && (m.Session != expected.Session || m.HostTGID != expected.HostTGID || m.Birth != expected.Birth || m.MapID == 0 || m.Generation == 0 || (scopeOK && scope.State == libpf.LocalCopied && m.Generation != scope.Observation.Binding.Generation)) {
			row.Binding = definitionSourceRejected
			reject(row, "binding-mismatch")
		}
		if mailbox.State == libpf.LocalCopied && !mailbox.InfoReturned { reject(row, "map-readout-missing") } else if mailbox.State == libpf.LocalCopied && mailbox.InfoReturned && (mailbox.InfoID != m.MapID || mailbox.InfoType != uint32(cebpf.Hash) || mailbox.InfoKeySize != 8 || mailbox.InfoValueSize != 1 || mailbox.InfoMaxEntries == 0) {
			row.Binding = definitionSourceRejected
			reject(row, "map-readout-mismatch")
		}
		if mailbox.State == libpf.LocalCopied && !mailbox.LifetimeReturned { reject(row, "lifetime-readout-missing") } else if mailbox.State == libpf.LocalCopied && mailbox.LifetimeReturned && (mailbox.LifetimeSession != m.Session || mailbox.LifetimeTGID != m.HostTGID || mailbox.LifetimeBirth != m.Birth) {
			row.Binding = definitionSourceRejected
			reject(row, "lifetime-binding-mismatch")
		}
	}
	if processOK {
		row := &p.Rows[5]
		row.Process = &process
		row.Missing[0] = "producer-readout-not-forwarded"
		if process.State != libpf.LocalCopied || process.ErrorPresent || process.Outcome == tracer.PIDProcessFailed { reject(row, "local-read-failure") }
		if process.Counts.Overflow || process.Counts.InFlight != 0 { reject(row, "held-or-overflow") }
		c := process.Counts
		classified, sumErr := libpf.SumAuxiliaryCounts(c.ExitWaiting, c.PermissionIgnored, c.NoMappings, c.NotExistExited, c.ESRCHExited, c.OtherMappingErrorExited, c.MappingsError, c.PartialOrEmpty, c.OrdinaryNil, c.Context, c.InFlight)
		if process.State == libpf.LocalCopied && (sumErr != nil || classified > c.Received) { reject(row, "local-classification-mismatch") }
		b := process.Binding
		if process.State == libpf.LocalCopied && (b.Source != libpf.AuxiliarySourcePeriodicCaller || b.Session != expected.Session || b.HostTGID != expected.HostTGID || b.Birth != expected.Birth || (scopeOK && scope.State == libpf.LocalCopied && b != scope.Observation.Binding)) {
			row.Binding = definitionSourceRejected
			reject(row, "binding-mismatch")
		}
	}
}

func (d *definitionAccounting) Expected() libpf.TraceDefinitionBinding {
	if d == nil {
		return libpf.TraceDefinitionBinding{}
	}
	return d.expected
}

func (d *definitionAccounting) Final() libpf.TraceDefinitionObservation {
	if d == nil {
		return libpf.TraceDefinitionObservation{}
	}
	r := d.final
	r.Rows = append([]libpf.TraceDefinitionCPUCounts(nil), d.final.Rows...)
	return r
}

func (d *definitionAccounting) Diagnostic() (accounting.Counts, error) {
	if d == nil {
		return accounting.Counts{}, fmt.Errorf("trace definition accounting missing")
	}
	counts, err := definitionDiagnostic(d.expected, d.final)
	if err != nil { return accounting.Counts{}, errors.Join(err, d.terminalErr) }
	if err := d.requireAcceptedCombined(); err != nil { return accounting.Counts{}, err }
	return counts, nil
}

func (d *definitionAccounting) requireAcceptedCombined() error {
	if d == nil { return fmt.Errorf("trace definition accounting missing") }
	if d.terminalErr != nil { return d.terminalErr }
	if d.status != definitionAccepted { return fmt.Errorf("combined definition receipt not accepted") }
	return validateStoppedDefinitions(d.expected, d.combined)
}

func definitionExpected(sym any) (libpf.TraceDefinitionBinding, error) {
	if sym == nil {
		return libpf.TraceDefinitionBinding{}, fmt.Errorf("trace definition symbolizer missing")
	}
	provider, ok := sym.(definitionBindingProvider)
	if !ok {
		return libpf.TraceDefinitionBinding{}, fmt.Errorf("trace definition binding capability missing")
	}
	binding, err := provider.TraceDefinitionBinding()
	if err != nil {
		return libpf.TraceDefinitionBinding{}, err
	}
	if err := validateTraceDefinitionBinding(binding); err != nil {
		return libpf.TraceDefinitionBinding{}, err
	}
	return binding, nil
}

func validateTraceDefinitionBinding(b libpf.TraceDefinitionBinding) error {
	if b.Source != libpf.TraceDefinitionPerfOutputV1 || b.Session == "" || b.HostTGID == 0 || b.Birth == 0 || b.CounterMapID == 0 || b.PerfMapID == 0 || b.CounterMapID == b.PerfMapID || b.PossibleCPUs <= 0 || b.Generation == 0 {
		return fmt.Errorf("invalid trace definition binding")
	}
	return nil
}

func (d *definitionAccounting) rememberTrace(observation *libpf.TraceDefinitionObservation) error {
	if observation == nil { return fmt.Errorf("trace definition observation missing") }
	if d.expected.PossibleCPUs <= 0 || len(observation.Rows) > d.expected.PossibleCPUs {
		return fmt.Errorf("trace definition observation exceeds expected CPU domain")
	}
	d.final = *observation
	d.final.Rows = append([]libpf.TraceDefinitionCPUCounts(nil), observation.Rows...)
	if len(observation.Rows) != d.expected.PossibleCPUs { return fmt.Errorf("trace definition CPU cardinality mismatch") }
	return nil
}

func (d *definitionAccounting) reject(err error) error {
	if d.status == definitionUnrecorded {
		d.status = definitionRejected
		d.terminalErr = err
	}
	return d.terminalErr
}

func (d *definitionAccounting) retainCombined(observation symbolizer.DefinitionStopObservation) error {
	traceErr := d.rememberTrace(observation.TraceDefinition)
	if observation.TraceDefinition == nil || d.expected.PossibleCPUs <= 0 || len(observation.TraceDefinition.Rows) > d.expected.PossibleCPUs { observation.TraceDefinition = nil } else {
		copy := d.final
		copy.Rows = append([]libpf.TraceDefinitionCPUCounts(nil), d.final.Rows...)
		observation.TraceDefinition = &copy
	}
	if observation.Auxiliary != nil { copy := *observation.Auxiliary; observation.Auxiliary = &copy }
	d.combined = observation
	return traceErr
}

func (d *definitionAccounting) rejectFinalObservation(observation symbolizer.DefinitionStopObservation, err error) error {
	if d == nil { return errors.Join(err, fmt.Errorf("trace definition accounting missing")) }
	if d.status != definitionUnrecorded { return d.terminalErr }
	traceErr := d.retainCombined(observation)
	return d.reject(errors.Join(err, traceErr))
}

func (d *definitionAccounting) recordFinal(observation symbolizer.DefinitionStopObservation, stopErr error) error {
	if d == nil { return errors.Join(stopErr, fmt.Errorf("trace definition accounting missing")) }
	if d.status != definitionUnrecorded { return d.terminalErr }
	traceErr := d.retainCombined(observation)
	validationErr := validateStoppedDefinitions(d.expected, d.combined)
	if err := errors.Join(stopErr, traceErr, validationErr); err != nil { return d.reject(err) }
	d.status = definitionAccepted
	return nil
}

// validateStoppedDefinitions consumes only detached receipt values and startup identity.
func validateStoppedDefinitions(expected libpf.TraceDefinitionBinding, stopped symbolizer.DefinitionStopObservation) error {
	if stopped.TraceDefinition == nil { return errors.New("combined trace observation missing") }
	if _, err := definitionDiagnostic(expected, *stopped.TraceDefinition); err != nil { return err }
	if !stopped.ReaderStopped || !stopped.ConsumerStopped || !stopped.BuffersDrained || !stopped.AuxiliaryStopped {
		return errors.New("combined definition stop incomplete")
	}
	if stopped.Auxiliary == nil { return errors.New("combined auxiliary observation missing") }
	aux := *stopped.Auxiliary
	scope, scopeOK := aux.Scope()
	kinds, kindsOK := aux.Kinds()
	mailbox, mailboxOK := aux.Mailbox()
	process, processOK := aux.Process()
	if !scopeOK || !kindsOK || !mailboxOK || !processOK { return errors.New("combined auxiliary facts missing") }
	if err := validateAuxiliaryFacts(expected, scope, kinds, mailbox, process); err != nil { return err }
	switch aux.Disposition() {
	case tracer.DefinitionAuxiliaryMissing:
		return errors.New("auxiliary observation missing")
	case tracer.DefinitionAuxiliaryFrozenNonfinal:
		return errors.New("auxiliary source closure not qualified")
	case tracer.DefinitionAuxiliaryFrozenFinal:
	default:
		return errors.New("unknown auxiliary disposition")
	}
	// Every currently defined reason is rejecting; none authorizes a final receipt.
	switch aux.Reason() {
	case tracer.DefinitionAuxiliaryReasonMissingFacts, tracer.DefinitionAuxiliaryReasonStopFailure,
		tracer.DefinitionAuxiliaryReasonInvalidBindingOrCounters, tracer.DefinitionAuxiliaryReasonUnqualifiedSourceClosure:
		return errors.New("auxiliary final success reason unavailable")
	default:
		return errors.New("unknown auxiliary reason")
	}
}

func validAuxiliaryOutcome(outcome libpf.AuxiliaryOutcome, admitted uint64) bool {
	switch outcome {
	case libpf.AuxiliaryFinal: return admitted != 0
	case libpf.AuxiliaryNotExercised: return admitted == 0
	default: return false
	}
}

// validateAuxiliaryFacts checks local copies, not upstream source qualification.
func validateAuxiliaryFacts(expected libpf.TraceDefinitionBinding, scope periodiccaller.ScopeLocalSnapshot, kinds periodiccaller.KindsLocalSnapshot, mailbox tracer.PIDMailboxLocalSnapshot, process tracer.PIDProcessLocalSnapshot) error {
	if scope.State != libpf.LocalCopied || kinds.State != libpf.LocalCopied || mailbox.State != libpf.LocalCopied || process.State != libpf.LocalCopied {
		return errors.New("auxiliary copy state invalid or unavailable")
	}
	s := scope.Observation
	b, c := s.Binding, s.Counts
	if scope.ErrorPresent || s.HeldWork || b.Source != libpf.AuxiliarySourcePeriodicCaller || b.Session == "" || b.HostTGID == 0 || b.Birth == 0 || b.Generation == 0 ||
		b.Session != expected.Session || b.HostTGID != expected.HostTGID || b.Birth != expected.Birth ||
		libpf.ReconcileAuxiliaryCounts(c) != nil || c.Failed != 0 || c.Aborted != 0 || c.InFlight != 0 || !validAuxiliaryOutcome(s.Outcome, c.Admitted) {
		return errors.New("auxiliary scope facts invalid")
	}
	for i, row := range kinds.Kinds {
		k := row.Counts
		if !row.Present || row.Source != libpf.AuxiliarySource(i+2) || row.ErrorPresent || row.HeldWork ||
			libpf.ReconcileKindLedger(k) != nil || k.Failed != 0 || k.Aborted != 0 || k.Skipped != 0 || k.Incomplete != 0 || k.InFlight != 0 || !validAuxiliaryOutcome(row.Outcome, k.Admitted) {
			return errors.New("auxiliary kind facts invalid")
		}
		kindBinding := b
		kindBinding.Source = row.Source
		if (k.Admitted != 0 || row.Binding != (libpf.AuxiliaryBinding{})) && libpf.ValidateAuxiliaryBinding(kindBinding, row.Binding) != nil {
			return errors.New("auxiliary kind binding mismatch")
		}
	}
	m, mc := mailbox.ExpectedBinding, mailbox.Counts
	if mailbox.Outcome != tracer.PIDMailboxObservedSealed || mailbox.ErrorPresent || mailbox.InfoError || !mailbox.InfoReturned || !mailbox.LifetimeReturned || mailbox.ScanIncomplete || mailbox.UnpolledResidualUnknown ||
		m.Session != b.Session || m.HostTGID != b.HostTGID || m.Birth != b.Birth || m.Generation != b.Generation || m.MapID == 0 ||
		mailbox.InfoID != m.MapID || mailbox.InfoType != uint32(cebpf.Hash) || mailbox.InfoKeySize != 8 || mailbox.InfoValueSize != 1 || mailbox.InfoMaxEntries == 0 ||
		mailbox.LifetimeSession != m.Session || mailbox.LifetimeTGID != m.HostTGID || mailbox.LifetimeBirth != m.Birth {
		return errors.New("auxiliary mailbox facts invalid")
	}
	terminals, err := libpf.SumAuxiliaryCounts(mc.Queued, mc.DeletionFailed, mc.Aborted, mc.HeldResidual)
	if err != nil || mc.Overflow || terminals != mc.Admitted || mc.DeletionFailed != 0 || mc.Aborted != 0 || mc.HeldResidual != 0 || mc.Unpartitioned != 0 { return errors.New("auxiliary mailbox terminal counts invalid") }
	partitions, err := libpf.SumAuxiliaryCounts(mc.TargetAdmitted, mc.ForeignAdmitted, mc.Unpartitioned)
	if err != nil || partitions != mc.Admitted { return errors.New("auxiliary mailbox partition invalid") }
	p := process.Counts
	if process.Outcome != tracer.PIDProcessObservedSealed || process.ErrorPresent || process.Binding != b || p.Overflow || p.InFlight != 0 || p.Context != 0 { return errors.New("auxiliary process facts invalid") }
	total, err := libpf.SumAuxiliaryCounts(p.ExitWaiting, p.PermissionIgnored, p.NoMappings, p.NotExistExited, p.ESRCHExited, p.OtherMappingErrorExited, p.MappingsError, p.PartialOrEmpty, p.OrdinaryNil, p.Context, p.InFlight)
	if err != nil || total != p.Received { return errors.New("auxiliary process counts invalid") }
	return nil
}

type definitionObservationTotals struct {
	totalAttempted uint64
	totalSubmitted uint64
	reserveFailed  uint64
	terminals      uint64
}

func validateDefinitionObservation(final libpf.TraceDefinitionObservation) (definitionObservationTotals, error) {
	if final.Reader.Overflow {
		return definitionObservationTotals{}, fmt.Errorf("trace definition reader counter overflow")
	}

	var (
		totalAttempted, totalSubmitted, totalOutputFailed, totalRejectedSize uint64
		c1, c2, carry                                                        uint64
		terminals                                                            uint64
	)
	for _, row := range final.Rows {
		if row.Overflow != 0 {
			return definitionObservationTotals{}, fmt.Errorf("trace definition native counter overflow")
		}
		classified, carry1 := bits.Add64(row.Submitted, row.OutputFailed, 0)
		classified, carry2 := bits.Add64(classified, row.RejectedSize, 0)
		if carry1 != 0 || carry2 != 0 {
			return definitionObservationTotals{}, fmt.Errorf("trace definition row count overflow")
		}
		if row.Attempted != classified {
			return definitionObservationTotals{}, fmt.Errorf("trace definition per-CPU conservation mismatch")
		}

		totalAttempted, carry = bits.Add64(totalAttempted, row.Attempted, 0)
		if carry != 0 {
			return definitionObservationTotals{}, fmt.Errorf("trace definition aggregate attempted overflow")
		}
		totalSubmitted, carry = bits.Add64(totalSubmitted, row.Submitted, 0)
		if carry != 0 {
			return definitionObservationTotals{}, fmt.Errorf("trace definition aggregate submitted overflow")
		}
		totalOutputFailed, carry = bits.Add64(totalOutputFailed, row.OutputFailed, 0)
		if carry != 0 {
			return definitionObservationTotals{}, fmt.Errorf("trace definition aggregate output failed overflow")
		}
		totalRejectedSize, carry = bits.Add64(totalRejectedSize, row.RejectedSize, 0)
		if carry != 0 {
			return definitionObservationTotals{}, fmt.Errorf("trace definition aggregate rejected size overflow")
		}
	}

	totalClassified, c1 := bits.Add64(totalSubmitted, totalOutputFailed, 0)
	totalClassified, c2 = bits.Add64(totalClassified, totalRejectedSize, 0)
	if c1 != 0 || c2 != 0 {
		return definitionObservationTotals{}, fmt.Errorf("trace definition aggregate count overflow")
	}
	if totalAttempted != totalClassified {
		return definitionObservationTotals{}, fmt.Errorf("trace definition aggregate conservation mismatch")
	}

	terminals, c1 = bits.Add64(final.Reader.HandedOff, final.Reader.RejectedRecord, 0)
	terminals, c2 = bits.Add64(terminals, final.Reader.HandoffAborted, 0)
	if c1 != 0 || c2 != 0 {
		return definitionObservationTotals{}, fmt.Errorf("trace definition reader terminals overflow")
	}
	if terminals != final.Reader.Received {
		return definitionObservationTotals{}, fmt.Errorf("trace definition reader conservation mismatch")
	}
	if final.Reader.Received > totalSubmitted {
		return definitionObservationTotals{}, fmt.Errorf("trace definition reader received exceeds submitted")
	}

	reserveFailed, carry := bits.Add64(totalOutputFailed, totalRejectedSize, 0)
	if carry != 0 {
		return definitionObservationTotals{}, fmt.Errorf("trace definition reserve failed overflow")
	}
	undelivered := totalSubmitted - final.Reader.Received
	emissionLoss, carry := bits.Add64(reserveFailed, undelivered, 0)
	if carry != 0 {
		return definitionObservationTotals{}, fmt.Errorf("trace definition emission loss overflow")
	}

	if final.Undelivered != undelivered || final.EmissionLoss != emissionLoss {
		return definitionObservationTotals{}, fmt.Errorf("trace definition observation totals mismatch")
	}

	return definitionObservationTotals{
		totalAttempted: totalAttempted,
		totalSubmitted: totalSubmitted,
		reserveFailed:  reserveFailed,
		terminals:      terminals,
	}, nil
}

func definitionDiagnostic(expected libpf.TraceDefinitionBinding, final libpf.TraceDefinitionObservation) (accounting.Counts, error) {
	if err := validateTraceDefinitionBinding(expected); err != nil {
		return accounting.Counts{}, fmt.Errorf("trace definition expected binding invalid: %w", err)
	}
	if expected != final.Binding {
		return accounting.Counts{}, fmt.Errorf("trace definition binding mismatch")
	}
	if final.Outcome != libpf.TraceDefinitionFinal {
		return accounting.Counts{}, fmt.Errorf("trace definition outcome not final: %v", final.Outcome)
	}
	if len(final.Rows) != expected.PossibleCPUs {
		return accounting.Counts{}, fmt.Errorf("trace definition CPU cardinality mismatch: expected %d, got %d", expected.PossibleCPUs, len(final.Rows))
	}
	totals, err := validateDefinitionObservation(final)
	if err != nil {
		return accounting.Counts{}, err
	}

	return accounting.Counts{
		Known:         true,
		Quiesced:      true,
		Joined:        true,
		Attempted:     totals.totalAttempted,
		Submitted:     totals.totalSubmitted,
		ReserveFailed: totals.reserveFailed,
		Received:      final.Reader.Received,
		Terminals:     totals.terminals,
	}, nil
}
