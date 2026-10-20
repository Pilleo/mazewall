package model

import (
	"context"
	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
)

type Stats = wire.Stats
type Summary = wire.Summary
type SelectedAccountingDiagnostic = wire.SelectedAccountingDiagnostic
type EventFinalizationObservation = wire.EventFinalizationObservation
type DefinitionStopObservation = wire.DefinitionStopObservation
type LossObservation = wire.LossObservation
type ReaderObservation = wire.ReaderObservation
type Record = wire.Record
type Lifecycle = wire.Lifecycle
type RecoveryResult = wire.RecoveryResult

type Sink interface {
	Write(context.Context, Record) error
}
