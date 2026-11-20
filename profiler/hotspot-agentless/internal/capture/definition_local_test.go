// Copyright 2026 The mazewall Authors
// SPDX-License-Identifier: Apache-2.0

package capture

import (
	"context"
	"errors"
	"reflect"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/inspektor-gadget/inspektor-gadget/pkg/symbolizer"
	"go.opentelemetry.io/ebpf-profiler/tracer"
	"golang.org/x/sys/unix"
)

func TestDefinitionLocalMissingFirstCloseWins(t *testing.T) {
	for _, callerFirst := range []bool{false, true} {
		t.Run(map[bool]string{false: "callback", true: "caller"}[callerFirst], func(t *testing.T) {
			r := &definitionLocalRun{}
			r.enter(localOwnedCheck)
			ready, proceed, done := make(chan struct{}), make(chan struct{}), make(chan struct{})
			var release sync.Once
			var callback *definitionLocalMissing
			launched := false
			t.Cleanup(func() {
				release.Do(func() { close(proceed) })
				if launched { select { case <-done: case <-time.After(5*time.Second): t.Errorf("marker caller did not return") } }
			})
			launched = true
			go func() {
				close(ready)
				<-proceed
				r.enter(localCompleted)
				callback = r.closeMissing()
				close(done)
			}()
			select { case <-ready: case <-time.After(5*time.Second): t.Fatal("marker caller not ready") }
			var first *definitionLocalMissing
			if callerFirst {
				first = r.closeMissing()
			}
			release.Do(func() { close(proceed) })
			select { case <-done: case <-time.After(5*time.Second): t.Fatal("marker caller did not return") }
			if !callerFirst {
				first = r.closeMissing()
			}
			want := localCompleted
			if callerFirst {
				want = localOwnedCheck
			}
			if first != callback || first.Phase != want || first.Reason != "local_readout_not_installed_safety_gate" {
				t.Fatalf("first=%+v callback=%+v", first, callback)
			}
		})
	}
	var nilRun *definitionLocalRun
	nilRun.enter(localCompleted)
	if nilRun.closeMissing() != nil {
		t.Fatal("nil marker returned a record")
	}
}

type callbackTestOwner struct {
	observation       symbolizer.DefinitionStopObservation
	drainErr, stopErr error
	operations        *[]string
	combined          *symbolizer.DefinitionStopObservation
	combinedErr       error
	onStop            func()
}

func (o *callbackTestOwner) DrainDefinitions(context.Context) (symbolizer.DefinitionStopObservation, error) {
	*o.operations = append(*o.operations, "drain")
	return o.observation, o.drainErr
}

func (o *callbackTestOwner) StopAuxiliary(context.Context) error {
	*o.operations = append(*o.operations, "stop")
	if o.onStop != nil { o.onStop() }
	return o.stopErr
}

func (o *callbackTestOwner) StopDefinitions(context.Context) (symbolizer.DefinitionStopObservation, error) {
	*o.operations = append(*o.operations, "combined")
	if o.combined != nil { return *o.combined, o.combinedErr }
	return o.observation, o.combinedErr
}

func (o *callbackTestOwner) LocalReadout() { panic("unexpected local getter") }

type t9DeadlineFenceOwner struct {
	trace, combined symbolizer.DefinitionStopObservation
	onDrain, onCombined func(context.Context)
	drainCalls, stopCalls, combinedCalls int
	combinedErr error
}

func (o *t9DeadlineFenceOwner) DrainDefinitions(ctx context.Context) (symbolizer.DefinitionStopObservation, error) {
	o.drainCalls++
	if o.onDrain != nil { o.onDrain(ctx) }
	return o.trace, nil
}

func (o *t9DeadlineFenceOwner) StopAuxiliary(context.Context) error { o.stopCalls++; return nil }

func (o *t9DeadlineFenceOwner) StopDefinitions(ctx context.Context) (symbolizer.DefinitionStopObservation, error) {
	o.combinedCalls++
	if o.onCombined != nil { o.onCombined(ctx) }
	return o.combined, o.combinedErr
}

func TestDefinitionDrainCancellationAfterTraceVetoesContinuation(t *testing.T) {
	value := validObservationFixture(validBindingFixture())
	state := &definitionAccounting{expected: validBindingFixture()}
	owner := &t9DeadlineFenceOwner{trace: symbolizer.DefinitionStopObservation{TraceDefinition: &value}}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	owner.onDrain = func(actual context.Context) {
		if actual != ctx || actual.Err() != nil { t.Errorf("drain did not receive the live first context") }
		cancel()
	}
	finishCalls, stopCalls, execCalls := 0, 0, 0
	next := func(context.Context) error { finishCalls++; stopCalls++; execCalls++; return nil }
	err := withDefinitionDrain(ctx, owner, state, next)
	if !errors.Is(err, context.Canceled) || state.status != definitionRejected { t.Fatalf("post-drain cancellation lost: %v", err) }
	if owner.drainCalls != 1 || owner.combinedCalls != 0 || owner.stopCalls != 0 || finishCalls != 0 || stopCalls != 0 || execCalls != 0 { t.Fatal("continuation or combined getter ran after cancellation") }
	if state.combined.TraceDefinition != nil || state.Final().Binding != value.Binding || len(state.Final().Rows) != len(value.Rows) { t.Fatal("trace diagnostic lost or combined result recorded") }
	later := errors.New("later candidate error")
	lateAux := tracer.DefinitionAuxiliaryObservation{}
	late := symbolizer.DefinitionStopObservation{TraceDefinition: &value, Auxiliary: &lateAux}
	if err := state.rejectFinalObservation(late, later); !errors.Is(err, context.Canceled) || errors.Is(err, later) || state.combined.TraceDefinition != nil || state.combined.Auxiliary != nil { t.Fatalf("pre-fetch rejection was filled or replaced: %v", err) }
	retry, stopRetry := context.WithTimeout(context.Background(), 5*time.Second)
	defer stopRetry()
	if err := withDefinitionDrain(retry, owner, state, next); !errors.Is(err, context.Canceled) || owner.drainCalls != 1 || owner.combinedCalls != 0 || finishCalls != 0 { t.Fatalf("new context renewed rejected readout: %v", err) }
}

func TestDefinitionReadoutCancellationInsideCombinedGetterJoinsCause(t *testing.T) {
	value := validObservationFixture(validBindingFixture())
	candidate := validObservationFixture(validBindingFixture())
	candidate.Rows[0].Attempted = 37
	candidate.Rows[0].OutputFailed = 29
	candidate.EmissionLoss = 32
	if _, err := definitionDiagnostic(validBindingFixture(), candidate); err != nil { t.Fatalf("candidate trace invalid: %v", err) }
	aux := tracer.DefinitionAuxiliaryObservation{}
	state := &definitionAccounting{expected: validBindingFixture()}
	owner := &t9DeadlineFenceOwner{
		trace: symbolizer.DefinitionStopObservation{TraceDefinition: &value},
		combined: symbolizer.DefinitionStopObservation{ReaderStopped: true, ConsumerStopped: true, BuffersDrained: true, AuxiliaryStopped: true, TraceDefinition: &candidate, Auxiliary: &aux},
		combinedErr: unix.EACCES,
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	owner.onCombined = func(actual context.Context) {
		if actual != ctx || actual.Err() != nil { t.Errorf("getter did not receive the live first context") }
		cancel()
	}
	finishEntered, finishDone := make(chan struct{}), make(chan struct{})
	t.Cleanup(func() {
		cancel()
		select { case <-finishEntered: select { case <-finishDone: case <-time.After(5*time.Second): t.Errorf("finish did not return") }; default: }
	})
	finishCalls, execCalls, fatalCalls := 0, 0, 0
	finish := func() error { close(finishEntered); defer close(finishDone); finishCalls++; return nil }
	exec := func() error { execCalls++; return nil }
	fatal := func() error { fatalCalls++; return nil }
	err := newDefinitionAfterReadout(context.Background(), fatal, exec, owner, &state, &definitionLocalRun{}, finish)(ctx)
	if !errors.Is(err, context.Canceled) || !errors.Is(err, unix.EACCES) || state.status != definitionRejected { t.Fatalf("getter cancellation or EACCES lost: %v", err) }
	if owner.drainCalls != 1 || owner.stopCalls != 1 || owner.combinedCalls != 1 || finishCalls != 1 || execCalls != 2 || fatalCalls != 1 { t.Fatalf("wrong readout path: drain=%d stop=%d getter=%d finish=%d exec=%d fatal=%d", owner.drainCalls, owner.stopCalls, owner.combinedCalls, finishCalls, execCalls, fatalCalls) }
	if state.combined.TraceDefinition == nil || state.combined.TraceDefinition == &candidate || len(state.combined.TraceDefinition.Rows) != len(candidate.Rows) || state.combined.TraceDefinition.Rows[0].Attempted != 37 || state.combined.TraceDefinition.Binding != candidate.Binding || state.combined.Auxiliary == nil || state.combined.Auxiliary == &aux || state.combined.Auxiliary.Disposition() != tracer.DefinitionAuxiliaryMissing { t.Fatal("canceled getter did not retain detached nonfinal candidate") }
	if _, diagnosticErr := state.Diagnostic(); !errors.Is(diagnosticErr, context.Canceled) || !errors.Is(diagnosticErr, unix.EACCES) { t.Fatalf("rejected candidate diagnostic lost terminal causes: %v", diagnosticErr) }
	candidate.Rows[0].Attempted = 99
	candidate.Binding.Session = "changed after return"
	*owner.combined.Auxiliary = tracer.DefinitionAuxiliaryObservation{}
	owner.combined.Auxiliary = nil
	firstCopy := state.Final()
	if firstCopy.Rows[0].Attempted != 37 || firstCopy.Binding != validBindingFixture() || state.combined.Auxiliary == nil { t.Fatal("retained candidate changed with source") }
	firstCopy.Rows[0].Attempted = 101
	if state.Final().Rows[0].Attempted != 37 { t.Fatal("Final returned aliased rows") }
	later := errors.New("later candidate error")
	laterValue := validObservationFixture(validBindingFixture())
	laterValue.Rows[0].Attempted = 222
	laterCandidate := symbolizer.DefinitionStopObservation{TraceDefinition: &laterValue}
	if laterErr := state.rejectFinalObservation(laterCandidate, later); !errors.Is(laterErr, context.Canceled) || !errors.Is(laterErr, unix.EACCES) || errors.Is(laterErr, later) || state.combined.TraceDefinition.Rows[0].Attempted != 37 || state.combined.Auxiliary == nil { t.Fatalf("later candidate replaced first terminal data: %v", laterErr) }
	retry, stopRetry := context.WithTimeout(context.Background(), 5*time.Second)
	defer stopRetry()
	if err := withDefinitionDrain(retry, owner, state, func(context.Context) error { t.Error("unexpected retry continuation"); return nil }); !errors.Is(err, context.Canceled) || !errors.Is(err, unix.EACCES) || owner.drainCalls != 1 || owner.combinedCalls != 1 { t.Fatalf("rejected getter was retried: %v", err) }
}

func TestDefinitionGetterErrorRetainsCandidateWithLiveContext(t *testing.T) {
	value := validObservationFixture(validBindingFixture())
	candidate := validObservationFixture(validBindingFixture())
	candidate.Rows[0].Attempted = 51
	candidate.Rows[0].OutputFailed = 43
	candidate.EmissionLoss = 46
	if _, err := definitionDiagnostic(validBindingFixture(), candidate); err != nil { t.Fatalf("candidate trace invalid: %v", err) }
	aux := tracer.DefinitionAuxiliaryObservation{}
	owner := &t9DeadlineFenceOwner{
		trace: symbolizer.DefinitionStopObservation{TraceDefinition: &value},
		combined: symbolizer.DefinitionStopObservation{TraceDefinition: &candidate, Auxiliary: &aux},
		combinedErr: unix.EACCES,
	}
	state := &definitionAccounting{expected: validBindingFixture()}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	nextCalls := 0
	err := withDefinitionDrain(ctx, owner, state, func(context.Context) error { nextCalls++; return nil })
	if !errors.Is(err, unix.EACCES) || errors.Is(err, context.Canceled) || state.status != definitionRejected || owner.drainCalls != 1 || owner.combinedCalls != 1 || nextCalls != 1 { t.Fatalf("live getter failure lost: %v", err) }
	if state.combined.TraceDefinition == nil || state.combined.TraceDefinition == &candidate || len(state.combined.TraceDefinition.Rows) != len(candidate.Rows) || state.combined.TraceDefinition.Rows[0].Attempted != 51 || state.combined.Auxiliary == nil || state.combined.Auxiliary == &aux { t.Fatal("live getter candidate not detached") }
	candidate.Rows[0].Attempted = 52
	candidate.Binding.Session = "changed"
	owner.combined.Auxiliary = nil
	if state.Final().Rows[0].Attempted != 51 || state.Final().Binding != validBindingFixture() || state.combined.Auxiliary == nil { t.Fatal("live getter retained source aliases") }
	if _, err := state.Diagnostic(); !errors.Is(err, unix.EACCES) { t.Fatalf("getter failure diagnostic lost cause: %v", err) }
}

func TestDefinitionAfterReadoutReadsStartupStateAtInvocation(t *testing.T) {
	var state *definitionAccounting
	value := validObservationFixture(validBindingFixture())
	var operations []string
	owner := &callbackTestOwner{observation: symbolizer.DefinitionStopObservation{TraceDefinition: &value}, operations: &operations}
	marker := &definitionLocalRun{}
	ok := func() error { return nil }
	callback := newDefinitionAfterReadout(context.Background(), ok, ok, owner, &state, marker, ok)
	provider := &mockBindingSymbolizer{binding: validBindingFixture()}
	if err := withDefinitionStartup(true, provider, func(s *definitionAccounting) error { state = s; return nil }); err != nil {
		t.Fatal(err)
	}
	if err := callback(context.Background()); err == nil { t.Fatal("missing combined auxiliary accepted") }
	if state.Final().Binding != value.Binding || !reflect.DeepEqual(operations, []string{"drain", "stop", "combined"}) || marker.closeMissing().Phase != localFinalCheck {
		t.Fatalf("state=%+v operations=%v missing=%+v", state.Final(), operations, marker.closeMissing())
	}
}

func TestDefinitionAfterReadoutActualExitMatrix(t *testing.T) {
	sentinel := errors.New("operation failed")
	for _, tc := range []struct {
		name  string
		phase definitionReadoutPhase
		ops   []string
	}{
		{"owned", localOwnedCheck, []string{"fatal"}},
		{"exec", localExecCheck, []string{"exec"}},
		{"drain", localDrainOrRecord, []string{"exec", "drain"}},
		{"missing-state", localDrainOrRecord, []string{"exec", "drain"}},
		{"nil-final", localDrainOrRecord, []string{"exec", "drain"}},
		{"finish", localEnrichment, []string{"exec", "drain", "finish"}},
		{"prune-failure", localAuxStop, []string{"exec", "drain", "finish", "stop"}},
		{"resolver-failure", localAuxStop, []string{"exec", "drain", "finish", "stop"}},
		{"final", localFinalCheck, []string{"exec", "drain", "finish", "stop", "fatal", "exec"}},
		{"nonfinal", localFinalCheck, []string{"exec", "drain", "finish", "stop", "fatal", "exec", "combined"}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var ops []string
			value := validObservationFixture(validBindingFixture())
			state := &definitionAccounting{expected: validBindingFixture()}
			owner := &callbackTestOwner{observation: symbolizer.DefinitionStopObservation{TraceDefinition: &value}, operations: &ops}
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			if tc.name == "owned" {
				cancel()
			}
			if tc.name == "drain" {
				owner.drainErr = sentinel
			}
			if tc.name == "prune-failure" || tc.name == "resolver-failure" {
				owner.stopErr = sentinel
			}
			if tc.name == "missing-state" {
				state = nil
			}
			if tc.name == "nil-final" {
				owner.observation.TraceDefinition = nil
			}
			execCalls := 0
			exec := func() error {
				ops = append(ops, "exec")
				execCalls++
				if tc.name == "exec" || (tc.name == "final" && execCalls == 2) {
					return sentinel
				}
				return nil
			}
			fatal := func() error {
				ops = append(ops, "fatal")
				if tc.name == "owned" {
					return sentinel
				}
				return nil
			}
			finish := func() error {
				ops = append(ops, "finish")
				if tc.name == "finish" {
					return sentinel
				}
				return nil
			}
			marker := &definitionLocalRun{}
			err := newDefinitionAfterReadout(ctx, fatal, exec, owner, &state, marker, finish)(context.Background())
			if err == nil {
				t.Fatal("expected failure")
			}
			if tc.name == "owned" || tc.name == "exec" || tc.name == "drain" || tc.name == "finish" || tc.name == "prune-failure" || tc.name == "resolver-failure" || tc.name == "final" {
				if !errors.Is(err, sentinel) {
					t.Fatalf("lost original cause: %v", err)
				}
			}
			if !reflect.DeepEqual(ops, tc.ops) || marker.closeMissing().Phase != tc.phase {
				t.Fatalf("ops=%v missing=%+v err=%v", ops, marker.closeMissing(), err)
			}
		})
	}
}

func TestDefinitionAfterReadoutDeadlineAndLateClose(t *testing.T) {
	value := validObservationFixture(validBindingFixture())
	state := &definitionAccounting{expected: validBindingFixture()}
	var ops []string
	owner := &callbackTestOwner{observation: symbolizer.DefinitionStopObservation{TraceDefinition: &value}, operations: &ops}
	started, release, finishDone, callerDone := make(chan struct{}), make(chan struct{}), make(chan struct{}), make(chan struct{})
	finish := func() error { defer close(finishDone); close(started); <-release; return nil }
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	marker := &definitionLocalRun{}
	ok := func() error { return nil }
	callback := newDefinitionAfterReadout(context.Background(), ok, ok, owner, &state, marker, finish)
	var releaseOnce sync.Once
	var result error
	launched := false
	t.Cleanup(func() {
		cancel(); releaseOnce.Do(func() { close(release) })
		if launched { select { case <-callerDone: case <-time.After(5*time.Second): t.Errorf("callback did not return") } }
		select { case <-started: select { case <-finishDone: case <-time.After(5*time.Second): t.Errorf("continuation did not return") }; default: }
	})
	launched = true
	go func() { result = callback(ctx); close(callerDone) }()
	select { case <-started: case <-time.After(5*time.Second): t.Fatal("continuation not entered") }
	first := marker.closeMissing()
	select { case <-callerDone: case <-time.After(5*time.Second): t.Fatal("deadline did not return callback") }
	if !errors.Is(result, context.DeadlineExceeded) { t.Fatalf("lost deadline: %v", result) }
	releaseOnce.Do(func() { close(release) })
	select { case <-finishDone: case <-time.After(5*time.Second): t.Fatal("continuation did not return") }
	if marker.closeMissing() != first || first.Phase != localEnrichment || !reflect.DeepEqual(ops, []string{"drain"}) {
		t.Fatalf("missing=%+v ops=%v", first, ops)
	}
	if state.status != definitionRejected || state.combined.TraceDefinition != nil { t.Fatal("late continuation promoted or fetched combined state") }
	called := false
	if err := withDefinitionDrain(ctx, owner, state, func(context.Context) error { called = true; return nil }); !errors.Is(err, context.DeadlineExceeded) || called || !reflect.DeepEqual(ops, []string{"drain"}) { t.Fatalf("late retry admitted work: %v %v", called, err) }
}

func TestDefinitionAfterReadoutFetchesCombinedOnlyAfterContinuation(t *testing.T) {
	value := validObservationFixture(validBindingFixture())
	aux := tracer.DefinitionAuxiliaryObservation{}
	combined := symbolizer.DefinitionStopObservation{ReaderStopped: true, ConsumerStopped: true, BuffersDrained: true, AuxiliaryStopped: true, TraceDefinition: &value, Auxiliary: &aux}
	state := &definitionAccounting{expected: validBindingFixture()}
	var ops []string
	owner := &callbackTestOwner{observation: symbolizer.DefinitionStopObservation{TraceDefinition: &value}, combined: &combined, operations: &ops}
	finishEntered, finishDone := make(chan struct{}), make(chan struct{})
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	t.Cleanup(func() {
		cancel()
		select { case <-finishEntered: select { case <-finishDone: case <-time.After(5*time.Second): t.Errorf("finish did not return") }; default: }
	})
	owner.onStop = func() {
		if state.status != definitionUnrecorded || state.combined.TraceDefinition != nil || !reflect.DeepEqual(ops, []string{"exec", "drain", "finish", "stop"}) { t.Errorf("combined state before auxiliary stop: %v", ops) }
	}
	finish := func() error {
		close(finishEntered); defer close(finishDone)
		if state.status != definitionUnrecorded || state.combined.TraceDefinition != nil || !reflect.DeepEqual(ops, []string{"exec", "drain"}) { t.Errorf("combined state before finish: %v", ops) }
		ops = append(ops, "finish"); return nil
	}
	exec := func() error { ops = append(ops, "exec"); return nil }
	fatal := func() error { ops = append(ops, "fatal"); return nil }
	err := newDefinitionAfterReadout(context.Background(), fatal, exec, owner, &state, &definitionLocalRun{}, finish)(ctx)
	if err == nil || !strings.Contains(err.Error(), "combined auxiliary facts missing") { t.Fatalf("missing auxiliary accepted: %v", err) }
	if !reflect.DeepEqual(ops, []string{"exec", "drain", "finish", "stop", "fatal", "exec", "combined"}) { t.Fatalf("wrong order: %v", ops) }
	if state.status != definitionRejected || state.combined.Auxiliary == nil || state.combined.Auxiliary == &aux { t.Fatal("combined rejecting receipt not detached") }
	if _, err := state.Diagnostic(); err == nil { t.Fatal("nonfinal receipt became accepted") }
	value.Rows[0].Attempted++
	value.Binding.Session = "changed"
	if state.Final().Binding.Session != validBindingFixture().Session || state.Final().Rows[0].Attempted == value.Rows[0].Attempted { t.Fatal("captured trace aliases source") }
}

func TestDefinitionAfterReadoutVetoesCombinedFetch(t *testing.T) {
	for _, stage := range []string{"finish", "auxiliary", "fatal", "exec"} {
		t.Run(stage, func(t *testing.T) {
			value := validObservationFixture(validBindingFixture())
			state := &definitionAccounting{expected: validBindingFixture()}
			var ops []string
			owner := &callbackTestOwner{observation: symbolizer.DefinitionStopObservation{TraceDefinition: &value}, operations: &ops}
			failure := errors.New("continuation failure")
			if stage == "auxiliary" { failure = unix.EACCES; owner.stopErr = failure }
			finishEntered, finishDone := make(chan struct{}), make(chan struct{})
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			t.Cleanup(func() { cancel(); select { case <-finishEntered: select { case <-finishDone: case <-time.After(5*time.Second): t.Errorf("finish did not return") }; default: } })
			finish := func() error { close(finishEntered); defer close(finishDone); ops = append(ops, "finish"); if stage == "finish" { return failure }; return nil }
			calls := 0
			exec := func() error { calls++; ops = append(ops, "exec"); if stage == "exec" && calls == 2 { return failure }; return nil }
			fatal := func() error { ops = append(ops, "fatal"); if stage == "fatal" { return failure }; return nil }
			err := newDefinitionAfterReadout(context.Background(), fatal, exec, owner, &state, &definitionLocalRun{}, finish)(ctx)
			if !errors.Is(err, failure) || state.status != definitionRejected { t.Fatalf("%s cause/state lost: %v", stage, err) }
			for _, op := range ops { if op == "combined" { t.Fatalf("%s fetched combined after veto: %v", stage, ops) } }
		})
	}
}

func TestDefinitionDrainMissingContinuationAndCombinedError(t *testing.T) {
	value := validObservationFixture(validBindingFixture())
	var ops []string
	owner := &callbackTestOwner{observation: symbolizer.DefinitionStopObservation{TraceDefinition: &value}, operations: &ops}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	state := &definitionAccounting{expected: validBindingFixture()}
	if err := withDefinitionDrain(ctx, owner, state, nil); err == nil || !strings.Contains(err.Error(), "continuation missing") || !reflect.DeepEqual(ops, []string{"drain"}) { t.Fatalf("missing continuation: %v %v", ops, err) }
	continuationErr := errors.New("direct continuation failure")
	ops = nil
	state = &definitionAccounting{expected: validBindingFixture()}
	if err := withDefinitionDrain(ctx, owner, state, func(context.Context) error { return continuationErr }); !errors.Is(err, continuationErr) || !reflect.DeepEqual(ops, []string{"drain"}) { t.Fatalf("continuation error lost: %v %v", ops, err) }
	aux := tracer.DefinitionAuxiliaryObservation{}
	combined := symbolizer.DefinitionStopObservation{ReaderStopped: true, ConsumerStopped: true, BuffersDrained: true, AuxiliaryStopped: true, TraceDefinition: &value, Auxiliary: &aux}
	sentinel := errors.New("combined stop failure")
	ops = nil
	owner.combined, owner.combinedErr = &combined, sentinel
	state = &definitionAccounting{expected: validBindingFixture()}
	err := withDefinitionDrain(ctx, owner, state, func(context.Context) error { return nil })
	if !errors.Is(err, sentinel) || state.status != definitionRejected || !reflect.DeepEqual(ops, []string{"drain", "combined"}) { t.Fatalf("combined error lost: %v %v", ops, err) }
}

func TestDefinitionCombinedTraceBindingSubstitutionRejected(t *testing.T) {
	for _, field := range []string{"session", "target", "generation", "counter_map", "perf_map"} {
		t.Run(field, func(t *testing.T) {
			expected := validBindingFixture()
			trace := validObservationFixture(expected)
			changed := validObservationFixture(expected)
			switch field {
			case "session": changed.Binding.Session = "different"
			case "target": changed.Binding.HostTGID++
			case "generation": changed.Binding.Generation++
			case "counter_map": changed.Binding.CounterMapID++
			case "perf_map": changed.Binding.PerfMapID++
			}
			aux := tracer.DefinitionAuxiliaryObservation{}
			combined := symbolizer.DefinitionStopObservation{ReaderStopped: true, ConsumerStopped: true, BuffersDrained: true, AuxiliaryStopped: true, TraceDefinition: &changed, Auxiliary: &aux}
			var ops []string
			owner := &callbackTestOwner{observation: symbolizer.DefinitionStopObservation{TraceDefinition: &trace}, combined: &combined, operations: &ops}
			state := &definitionAccounting{expected: expected}
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()
			err := withDefinitionDrain(ctx, owner, state, func(context.Context) error { return nil })
			if err == nil || !strings.Contains(err.Error(), "trace definition binding mismatch") || state.status != definitionRejected { t.Fatalf("%s substitution accepted: %v", field, err) }
			if !reflect.DeepEqual(ops, []string{"drain", "combined"}) { t.Fatalf("wrong operations: %v", ops) }
		})
	}
}

func TestDefinitionAfterReadoutPanicClosesMarker(t *testing.T) {
	marker := &definitionLocalRun{}
	panicValue := errors.New("original panic")
	ok := func() error { return nil }
	callback := newDefinitionAfterReadout(context.Background(), ok, func() error { panic(panicValue) }, nil, nil, marker, ok)
	defer func() {
		if got := recover(); got != panicValue {
			t.Fatalf("panic changed: %v", got)
		}
		if missing := marker.closeMissing(); missing == nil || missing.Phase != localExecCheck {
			t.Fatalf("missing=%+v", missing)
		}
	}()
	_ = callback(context.Background())
}

func TestDefinitionLocalSuccessfulDrainAndStopMissingTransfer(t *testing.T) {
	// Rejection coverage: balanced local snapshots, AuxiliaryStopped, and final trace receipt
	// do NOT install cross-package transfer or admit all-source Definitions without an approved contract.
	marker := &definitionLocalRun{}
	ok := func() error { return nil }
	var operations []string
	value := validObservationFixture(validBindingFixture())
	owner := &callbackTestOwner{
		observation: symbolizer.DefinitionStopObservation{TraceDefinition: &value},
		operations:  &operations,
	}
	state := &definitionAccounting{expected: validBindingFixture()}
	callback := newDefinitionAfterReadout(context.Background(), ok, ok, owner, &state, marker, ok)
	err := callback(context.Background())
	if err == nil { t.Fatal("missing combined auxiliary accepted") }
	if !reflect.DeepEqual(operations, []string{"drain", "stop", "combined"}) {
		t.Fatalf("unexpected operations: %v", operations)
	}
	missing := marker.closeMissing()
	if missing == nil {
		t.Fatal("expected missing readout marker when transfer contract is not installed")
	}
	if missing.Phase != localFinalCheck {
		t.Fatalf("expected phase localFinalCheck, got %v", missing.Phase)
	}
	if missing.Reason != "local_readout_not_installed_safety_gate" {
		t.Fatalf("expected reason local_readout_not_installed_safety_gate, got %v", missing.Reason)
	}
}
