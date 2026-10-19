package wire

import (
	"bytes"
	"slices"
)

func clonePtr[T any](p *T) *T {
	if p == nil {
		return nil
	}
	v := *p
	return &v
}
func cloneExtensions(m extensions) extensions {
	if m == nil {
		return nil
	}
	result := make(extensions, len(m))
	for k, v := range m {
		result[k] = bytes.Clone(v)
	}
	return result
}
func cloneIdentity(p *HotSpotIdentity) *HotSpotIdentity {
	v := clonePtr(p)
	if v != nil {
		v.Epoch = clonePtr(v.Epoch)
		v.ReadError = clonePtr(v.ReadError)
		v.Extensions = cloneExtensions(v.Extensions)
	}
	return v
}
func cloneLifecycle(v Lifecycle) Lifecycle {
	v.ErrnoCauses = slices.Clone(v.ErrnoCauses)
	v.Extensions = cloneExtensions(v.Extensions)
	return v
}
func clonePair(v Pair) Pair {
	v.Event.HotSpotIdentity = cloneIdentity(v.Event.HotSpotIdentity)
	v.Event.FilenameBytes = bytes.Clone(v.Event.FilenameBytes)
	v.Event.Extensions = cloneExtensions(v.Event.Extensions)
	v.Evidence.HotSpotIdentity = cloneIdentity(v.Evidence.HotSpotIdentity)
	v.Evidence.Extensions = cloneExtensions(v.Evidence.Extensions)
	v.Evidence.RawErrors = slices.Clone(v.Evidence.RawErrors)
	v.Evidence.Frames = slices.Clone(v.Evidence.Frames)
	for i := range v.Evidence.Frames {
		f := &v.Evidence.Frames[i]
		f.SourceLine = clonePtr(f.SourceLine)
		f.SourceColumn = clonePtr(f.SourceColumn)
		f.NativeAddress = clonePtr(f.NativeAddress)
		f.BCI = clonePtr(f.BCI)
		f.RawBCI = clonePtr(f.RawBCI)
		f.Extensions = cloneExtensions(f.Extensions)
		f.Mapping = clonePtr(f.Mapping)
		if f.Mapping != nil {
			f.Mapping.Extensions = cloneExtensions(f.Mapping.Extensions)
		}
	}
	return v
}
func cloneSummary(v Summary) Summary {
	v.Extensions = cloneExtensions(v.Extensions)
	v.Stats.Extensions = cloneExtensions(v.Stats.Extensions)
	v.UnprovenCollectionSources = slices.Clone(v.UnprovenCollectionSources)
	v.Errors = slices.Clone(v.Errors)
	v.EventLoss = clonePtr(v.EventLoss)
	v.DefinitionLoss = clonePtr(v.DefinitionLoss)
	v.SelectedAccounting = clonePtr(v.SelectedAccounting)
	if v.SelectedAccounting != nil {
		v.SelectedAccounting.Extensions = cloneExtensions(v.SelectedAccounting.Extensions)
	}
	v.EventFinalization = clonePtr(v.EventFinalization)
	if v.EventFinalization != nil {
		v.EventFinalization.Extensions = cloneExtensions(v.EventFinalization.Extensions)
		v.EventFinalization.NativeReserveFailures = clonePtr(v.EventFinalization.NativeReserveFailures)
	}
	v.DefinitionStop = clonePtr(v.DefinitionStop)
	if v.DefinitionStop != nil {
		v.DefinitionStop.Extensions = cloneExtensions(v.DefinitionStop.Extensions)
	}
	v.EventLossObservation = clonePtr(v.EventLossObservation)
	if v.EventLossObservation != nil {
		v.EventLossObservation.Extensions = cloneExtensions(v.EventLossObservation.Extensions)
		v.EventLossObservation.Observed = clonePtr(v.EventLossObservation.Observed)
	}
	v.DefinitionReaderObservation = clonePtr(v.DefinitionReaderObservation)
	if v.DefinitionReaderObservation != nil {
		v.DefinitionReaderObservation.Extensions = cloneExtensions(v.DefinitionReaderObservation.Extensions)
	}
	return v
}
