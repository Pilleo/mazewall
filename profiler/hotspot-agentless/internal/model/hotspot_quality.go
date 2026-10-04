package model

import "go.opentelemetry.io/ebpf-profiler/libpf"

func qualityLabel(value uint8, labels []string) string {
	if int(value) >= len(labels) {
		return "unknown"
	}
	return labels[value]
}

func normalizeHotSpotQuality(f *Frame, q libpf.HotSpotQuality) {
	f.ScopeLookup = qualityLabel(uint8(q.ScopeLookup), []string{"unknown", "interpreter", "exact_pc", "preceding_pc", "method_only", "stub"})
	f.ScopeWalk = qualityLabel(uint8(q.ScopeWalk), []string{"unknown", "returned", "partial"})
	f.BCIState = qualityLabel(uint8(q.BCIState), []string{"unknown", "observed_in_range", "unavailable", "out_of_range"})
	f.QualityDiagnostic = q.Diagnostic.String()
	if f.ScopeLookup != "interpreter" && f.ScopeLookup != "exact_pc" && f.ScopeLookup != "preceding_pc" {
		if f.BCIState == "observed_in_range" || f.BCIState == "out_of_range" {
			f.BCIState = "unknown"
		}
		return
	}
	if q.BCIState == libpf.HotSpotBCIObservedInRange || q.BCIState == libpf.HotSpotBCIOutOfRange {
		bci := q.RawBCI
		f.RawBCI = &bci
	}
	if q.BCIState == libpf.HotSpotBCIObservedInRange {
		if uint64(q.RawBCI) != f.RawPosition || q.RawBCI >= 0xffff {
			f.BCIState = "unknown"
			return
		}
		bci := q.RawBCI
		f.BCI = &bci
	}
}
