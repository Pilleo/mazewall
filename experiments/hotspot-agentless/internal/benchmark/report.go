package benchmark

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
	"math"
	"regexp"
	"sort"
	"strconv"
)

type ResourceObservation struct {
	CPUSeconds float64 `json:"cpu_seconds"`
	PeakRSSKiB uint64  `json:"peak_rss_kib"`
	Samples    int     `json:"samples"`
	WindowNS   uint64  `json:"window_ns"`
	Semantics  string  `json:"semantics"`
}
type RunManifest struct {
	PostAttachWarmup int                  `json:"post_attach_warmup"`
	Mode             string               `json:"mode"`
	Run              string               `json:"run"`
	PID              uint32               `json:"host_pid"`
	Operations       int                  `json:"operations"`
	Warmup           int                  `json:"warmup"`
	Diversity        int                  `json:"diversity"`
	PauseNS          uint64               `json:"pause_ns"`
	JVMImage         string               `json:"jvm_image"`
	PatchSHA256      string               `json:"patch_sha256"`
	GadgetImage      string               `json:"gadget_image"`
	Kernel           string               `json:"kernel"`
	ReadinessNS      uint64               `json:"readiness_ns"`
	Collector        *ResourceObservation `json:"collector_resources,omitempty"`
}
type TargetResult struct {
	PostAttachWarmup int    `json:"post_attach_warmup"`
	Run              string `json:"run"`
	PID              uint32 `json:"pid"`
	UID              uint32 `json:"uid"`
	Operations       int    `json:"operations"`
	Successful       int    `json:"successful_operations"`
	Warmup           int    `json:"warmup"`
	Diversity        int    `json:"diversity"`
	PauseNS          uint64 `json:"pause_ns"`
	ElapsedNS        uint64 `json:"elapsed_ns"`
	P50NS            uint64 `json:"p50_ns"`
	P95NS            uint64 `json:"p95_ns"`
	P99NS            uint64 `json:"p99_ns"`
	MaxNS            uint64 `json:"max_ns"`
}
type RunReport struct {
	Readiness              *ReadinessObservation `json:"preparation_readiness,omitempty"`
	Manifest               RunManifest           `json:"manifest"`
	Target                 TargetResult          `json:"target"`
	Throughput             float64               `json:"operations_per_second"`
	Observed               int                   `json:"observed_marked_operations"`
	Unobserved             int                   `json:"unobserved_marked_operations"`
	CorrectSegments        int                   `json:"correct_application_segments"`
	Unavailable            int                   `json:"unavailable_marked_operations"`
	Skipped                int                   `json:"skipped_marked_operations"`
	WrongSegments          int                   `json:"wrong_application_segments"`
	MissingSequences       []int                 `json:"missing_sequences,omitempty"`
	SequenceGapLowerBound  uint64                `json:"sequence_gap_lower_bound"`
	CollectionLossComplete bool                  `json:"collection_loss_complete"`
	Summary                *model.Summary        `json:"collector_summary,omitempty"`
}

var token = regexp.MustCompile(`^/fixture/mw-bench-([a-f0-9]{16})-(\d{6})-r([0-7])$`)

func Analyze(m RunManifest, target, records io.Reader) (RunReport, error) {
	r := RunReport{Manifest: m}
	if (m.Mode != "baseline" && m.Mode != "events" && m.Mode != "full") || m.PID == 0 || m.Operations < 1 || m.Operations > 100000 || m.Warmup < 0 || m.Warmup > 100000 || m.PostAttachWarmup < 0 || m.PostAttachWarmup > 100000 || (m.PostAttachWarmup > 0 && m.PostAttachWarmup < m.Diversity) || (m.Diversity != 1 && m.Diversity != 8) || len(m.Run) != 16 || len(m.PatchSHA256) != 64 || m.JVMImage == "" || m.Kernel == "" {
		return r, fmt.Errorf("invalid benchmark manifest")
	}
	if e := json.NewDecoder(io.LimitReader(target, 4<<20)).Decode(&r.Target); e != nil {
		return r, e
	}
	t := r.Target
	if t.Run != m.Run || t.PID != m.PID || t.Operations != m.Operations || t.Successful != m.Operations || t.Warmup != m.Warmup || t.PostAttachWarmup != m.PostAttachWarmup || t.Diversity != m.Diversity || t.PauseNS != m.PauseNS || t.ElapsedNS == 0 || t.P50NS == 0 || t.P50NS > t.P95NS || t.P95NS > t.P99NS || t.P99NS > t.MaxNS {
		return r, fmt.Errorf("invalid target counts, identity, denominator or latency ordering")
	}
	r.Throughput = float64(m.Operations) * 1e9 / float64(t.ElapsedNS)
	if m.Collector != nil && (m.Collector.CPUSeconds < 0 || math.IsNaN(m.Collector.CPUSeconds) || math.IsInf(m.Collector.CPUSeconds, 0) || m.Collector.Samples < 1 || m.Collector.WindowNS == 0 || m.Collector.Semantics != "collector_process_attach_to_target_done") {
		return r, fmt.Errorf("invalid collector resource observation")
	}
	s := bufio.NewScanner(io.LimitReader(records, 256<<20))
	s.Buffer(make([]byte, 4096), 4<<20)
	events := map[string]model.Event{}
	terminals := map[string]model.StackEvidence{}
	perCPU := map[uint32][]uint64{}
	for s.Scan() {
		var row model.Record
		if e := json.Unmarshal(s.Bytes(), &row); e != nil {
			return r, e
		}
		switch row.Kind {
		case "event":
			if row.Event == nil || row.Event.PID != m.PID || row.Event.EventID != row.EventID {
				return r, fmt.Errorf("benchmark event identity mismatch")
			}
			if _, ok := events[row.EventID]; ok {
				return r, fmt.Errorf("duplicate event")
			}
			events[row.EventID] = *row.Event
			perCPU[row.Event.CPU] = append(perCPU[row.Event.CPU], row.Event.Sequence)
		case "enrichment":
			if row.Evidence == nil {
				return r, fmt.Errorf("missing terminal evidence")
			}
			if _, ok := terminals[row.EventID]; ok {
				return r, fmt.Errorf("duplicate terminal")
			}
			terminals[row.EventID] = *row.Evidence
		case "session_summary":
			if r.Summary != nil || row.Summary == nil {
				return r, fmt.Errorf("missing/duplicate summary")
			}
			r.Summary = row.Summary
		}
	}
	if e := s.Err(); e != nil {
		return r, e
	}
	if m.Mode == "baseline" {
		if len(events) > 0 || r.Summary != nil || m.Collector != nil {
			return r, fmt.Errorf("baseline contains collector evidence")
		}
		return r, nil
	}
	if r.Summary == nil || !r.Summary.TerminalAccountingComplete || len(r.Summary.Errors) > 0 || len(events) != len(terminals) || r.Summary.Stats.Received != uint64(len(events)) || r.Summary.EventLoss != nil || r.Summary.DefinitionLoss != nil {
		return r, fmt.Errorf("incomplete or falsely complete collector accounting")
	}
	if r.Summary.EventLossObservation != nil && r.Summary.EventLossObservation.Complete || r.Summary.DefinitionReaderObservation != nil && r.Summary.DefinitionReaderObservation.Complete {
		return r, fmt.Errorf("partial native loss observation upgraded to final proof")
	}
	if m.Mode == "events" && (r.Summary.DefinitionReaderObservation != nil || r.Summary.DefinitionStop != nil) {
		return r, fmt.Errorf("events-only mode contains definition-profiler evidence")
	}
	var delivered model.Stats
	for _, v := range terminals {
		switch v.Attribution {
		case "skipped":
			delivered.Skipped++
			if m.Mode == "events" && v.Reason != "unwind_disabled" {
				return r, fmt.Errorf("events-only terminal has wrong reason")
			}
		case "unavailable":
			delivered.Unavailable++
		case "event_correlated":
			delivered.Resolved++
		default:
			return r, fmt.Errorf("unknown terminal attribution")
		}
	}
	stats := r.Summary.Stats
	if delivered.Skipped != stats.Skipped || delivered.Unavailable != stats.Unavailable || delivered.Resolved != stats.Resolved {
		return r, fmt.Errorf("summary counters disagree with delivered terminals")
	}
	if m.Mode == "events" && delivered.Skipped != uint64(len(terminals)) {
		return r, fmt.Errorf("events-only mode contains enrichment outcomes")
	}
	if m.Mode == "full" && m.PostAttachWarmup > 0 {
		readiness, err := readinessFromRecords(m, events, terminals)
		if err != nil {
			return r, err
		}
		r.Readiness = &readiness
		if !readiness.Ready {
			return r, fmt.Errorf("post-attachment readiness lacks epoch-checked routes")
		}
	}
	seen := map[int]bool{}
	for id, e := range events {
		v, ok := terminals[id]
		if !ok {
			return r, fmt.Errorf("event without terminal")
		}
		match := token.FindStringSubmatch(e.Filename)
		if match == nil {
			continue
		}
		seq, err := strconv.Atoi(match[2])
		route, er2 := strconv.Atoi(match[3])
		if err != nil || er2 != nil || match[1] != m.Run || seq >= m.Operations || route != seq%m.Diversity || seen[seq] {
			return r, fmt.Errorf("marked benchmark token identity mismatch")
		}
		seen[seq] = true
		r.Observed++
		switch v.Attribution {
		case "skipped":
			r.Skipped++
			if m.Mode == "events" && v.Reason != "unwind_disabled" {
				return r, fmt.Errorf("events-only terminal has wrong reason")
			}
		case "unavailable":
			r.Unavailable++
		case "event_correlated":
			if m.Mode != "full" {
				return r, fmt.Errorf("events-only mode produced Java enrichment")
			}
			correct, wrong := applicationSegment(v, route)
			if wrong {
				r.WrongSegments++
			} else if correct {
				r.CorrectSegments++
			} else {
				r.Unavailable++
			}
		default:
			return r, fmt.Errorf("unknown terminal attribution")
		}
	}
	for _, seqs := range perCPU {
		sort.Slice(seqs, func(i, j int) bool { return seqs[i] < seqs[j] })
		var prev uint64
		for _, seq := range seqs {
			if seq == 0 || seq <= prev {
				return r, fmt.Errorf("invalid/duplicate occurrence sequence")
			}
			r.SequenceGapLowerBound += seq - prev - 1
			prev = seq
		}
	}
	for i := 0; i < m.Operations; i++ {
		if !seen[i] {
			r.MissingSequences = append(r.MissingSequences, i)
		}
	}
	r.Unobserved = len(r.MissingSequences)
	if r.WrongSegments > 0 {
		return r, fmt.Errorf("wrong benchmark application attribution")
	}
	return r, nil
}
