// Copyright 2026 The mazewall Authors
// SPDX-License-Identifier: Apache-2.0

package capture

import (
	"context"
	"errors"
	"sync/atomic"

	"github.com/inspektor-gadget/inspektor-gadget/pkg/symbolizer"
)

type definitionReadoutPhase uint32

const (
	localNotEntered definitionReadoutPhase = iota
	localOwnedCheck
	localExecCheck
	localDrainOrRecord
	localEnrichment
	localAuxStop
	localFinalCheck
	localCompleted
)

type definitionLocalMissing struct {
	Phase  definitionReadoutPhase `json:"phase"`
	Reason string                 `json:"reason"`
	OwnerDiagnostic *definitionOwnerDiagnostic `json:"owner_diagnostic,omitempty"`
}

type definitionLocalRun struct {
	phase  atomic.Uint32
	closed atomic.Pointer[definitionLocalMissing]
	ownerDiagnostic atomic.Pointer[definitionOwnerDiagnostic]
}

func (r *definitionLocalRun) enter(p definitionReadoutPhase) {
	if r != nil {
		r.phase.Store(uint32(p))
	}
}

func (r *definitionLocalRun) closeMissing() *definitionLocalMissing {
	if r == nil {
		return nil
	}
	missing := &definitionLocalMissing{Phase: definitionReadoutPhase(r.phase.Load()), Reason: "local_readout_not_installed_safety_gate"}
	if diagnostic := r.ownerDiagnostic.Load(); diagnostic != nil {
		copy := *diagnostic
		copy.Raw = append([]byte(nil), diagnostic.Raw...)
		missing.OwnerDiagnostic = &copy
	}
	r.closed.CompareAndSwap(nil, missing)
	return r.closed.Load()
}

type definitionDrainStopper interface {
	DrainDefinitions(context.Context) (symbolizer.DefinitionStopObservation, error)
	StopDefinitions(context.Context) (symbolizer.DefinitionStopObservation, error)
	StopAuxiliary(context.Context) error
}

func newDefinitionAfterReadout(ownedCtx context.Context, fatalErr, checkExec func() error, owner definitionDrainStopper, state **definitionAccounting, local *definitionLocalRun, finish func() error) func(context.Context) error {
	return func(stopCtx context.Context) error {
		defer local.closeMissing()
		var current *definitionAccounting
		if state != nil { current = *state }
		local.enter(localOwnedCheck)
		if err := ownedCtx.Err(); err != nil {
			err = errors.Join(err, fatalErr())
			if current != nil { return current.reject(err) }
			return err
		}
		local.enter(localExecCheck)
		if err := checkExec(); err != nil {
			if current != nil { return current.reject(err) }
			return err
		}
		local.enter(localDrainOrRecord)
		observeDiagnostics := current != nil && current.status == definitionUnrecorded
		nativeErr := withDefinitionDrain(stopCtx, owner, current, func(stopCtx context.Context) error {
			local.enter(localEnrichment)
			if finish == nil { return errors.New("definition enrichment continuation missing") }
			finished := make(chan error, 1)
			go func() { finished <- finish() }()
			select {
			case err := <-finished:
				if err != nil {
					return err
				}
			case <-stopCtx.Done():
				return stopCtx.Err()
			}
			local.enter(localAuxStop)
			if err := owner.StopAuxiliary(stopCtx); err != nil {
				return err
			}
			local.enter(localFinalCheck)
			err := errors.Join(ownedCtx.Err(), fatalErr(), checkExec())
			return err
		})
		if observeDiagnostics { current.retainOwnerDiagnostics(stopCtx, owner, local) }
		if nativeErr == nil { local.enter(localCompleted) }
		return nativeErr
	}
}
