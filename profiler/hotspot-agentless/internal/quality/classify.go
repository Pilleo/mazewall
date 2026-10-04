package quality

import (
	"go.opentelemetry.io/ebpf-profiler/libpf"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
)

type Classification struct {
	ScopeLookup, Walk string
	RawErrors         []string
}

func Classify(frames []model.Frame) Classification {
	c := Classification{ScopeLookup: "unknown", Walk: "returned"}
	scope := ""
	unknown := false
	for _, f := range frames {
		if f.ErrorFrame || f.ScopeWalk == "partial" {
			c.Walk = "partial"
		}
		if f.QualityDiagnostic != "" {
			c.RawErrors = append(c.RawErrors, f.QualityDiagnostic)
		}
		if f.ProviderType != uint8(libpf.HotSpotFrame) {
			continue
		}
		switch f.ScopeLookup {
		case "interpreter", "exact_pc", "preceding_pc", "method_only", "stub":
		default:
			unknown = true
			continue
		}
		if scope == "" {
			scope = f.ScopeLookup
		} else if scope != f.ScopeLookup {
			scope = "mixed"
		}
	}
	if scope != "" && !unknown {
		c.ScopeLookup = scope
	}
	return c
}
