package capture

import (
	"github.com/inspektor-gadget/inspektor-gadget/pkg/datasource"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/symbolizer"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
)

func reportedDefinitionHealth(reader symbolizer.TraceReaderHealthProvider) *model.ReaderObservation {
	if reader == nil {
		return nil
	}
	counts, known := reader.TraceReaderHealth()
	if !known {
		return nil
	}
	return &model.ReaderObservation{Lost: counts.Lost, NoData: counts.NoData, ReadError: counts.ReadError, BufferBytesPerCPU: counts.BufferBytesPerCPU, Complete: false, Semantics: "consumed_trace_perf_records_partial_snapshot"}
}

func definitionStopModel(s symbolizer.DefinitionStopObservation) *model.DefinitionStopObservation {
	semantics := "owned_definition_workers_unjoined_partial_snapshot"
	if s.ReaderStopped && s.ConsumerStopped {
		semantics = "owned_definition_workers_joined_partial_snapshot"
		if s.BuffersDrained {
			semantics = "owned_definition_perf_drained_partial_loss_snapshot"
		}
	}
	return &model.DefinitionStopObservation{ReaderStopped: s.ReaderStopped, ConsumerStopped: s.ConsumerStopped, BuffersDrained: s.BuffersDrained && s.ReaderStopped && s.ConsumerStopped, Semantics: semantics}
}

func reportedEventHealth(reader datasource.ReportedLossReader) model.LossObservation {
	if reader == nil {
		return model.LossObservation{Semantics: "unavailable"}
	}
	observed := reader.ReportedLoss()
	return model.LossObservation{Observed: &observed, Complete: false, Semantics: "forwarded_reports_lower_bound"}
}

func applyCollectionHealth(summary *model.Summary, eventLoss model.LossObservation) {
	summary.EventLossObservation = &eventLoss
	if eventLoss.Observed != nil {
		summary.CollectionHealth = "partial"
	} else {
		summary.CollectionHealth = "unknown"
	}
	// Neither a non-destructive read nor a stopped datasource proves that the
	// upstream native loss reader drained every counter. Final loss stays nil.
}
