package benchmark

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"regexp"
	"sort"
	"strconv"

	"io.mazewall/experiments/hotspot-agentless/internal/candidate"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
)

type ReadinessObservation struct {
	Ready     bool     `json:"ready"`
	Observed  int      `json:"observed_preparation_operations"`
	Routes    []int    `json:"epoch_checked_routes"`
	Epochs    []uint64 `json:"checked_epochs"`
	Semantics string   `json:"semantics"`
}

func ObserveReadiness(m RunManifest, records io.Reader) (ReadinessObservation, error) {
	events := map[string]model.Event{}
	terminals := map[string]model.StackEvidence{}
	s := bufio.NewScanner(io.LimitReader(records, 256<<20))
	s.Buffer(make([]byte, 4096), 4<<20)
	for s.Scan() {
		var row model.Record
		if err := json.Unmarshal(s.Bytes(), &row); err != nil {
			return ReadinessObservation{}, err
		}
		switch row.Kind {
		case "event":
			if row.Event == nil || row.EventID != row.Event.EventID || row.Event.PID != m.PID {
				return ReadinessObservation{}, fmt.Errorf("readiness event identity mismatch")
			}
			if _, found := events[row.EventID]; found {
				return ReadinessObservation{}, fmt.Errorf("duplicate readiness event")
			}
			events[row.EventID] = model.Event(*row.Event)
		case "enrichment":
			if row.Evidence == nil {
				return ReadinessObservation{}, fmt.Errorf("missing readiness evidence")
			}
			if _, found := terminals[row.EventID]; found {
				return ReadinessObservation{}, fmt.Errorf("duplicate readiness terminal")
			}
			terminals[row.EventID] = *row.Evidence
		}
	}
	if err := s.Err(); err != nil {
		return ReadinessObservation{}, err
	}
	return readinessFromRecords(m, events, terminals)
}

var preparationToken = regexp.MustCompile(`^/fixture/mw-ready-([a-f0-9]{16})-(\d{6})-r([0-7])$`)

func readinessFromRecords(m RunManifest, events map[string]model.Event, terminals map[string]model.StackEvidence) (ReadinessObservation, error) {
	r := ReadinessObservation{Semantics: "post_attachment_preparation_routes_observed; not_future_coverage_or_loss_proof"}
	if m.Mode != "full" || m.PostAttachWarmup < m.Diversity || (m.Diversity != 1 && m.Diversity != 8) {
		return r, fmt.Errorf("invalid full-mode readiness configuration")
	}
	seen, routes, epochs := map[int]bool{}, map[int]bool{}, map[uint64]bool{}
	for id, e := range events {
		match := preparationToken.FindStringSubmatch(e.Filename)
		if match == nil {
			continue
		}
		seq, err := strconv.Atoi(match[2])
		route, er2 := strconv.Atoi(match[3])
		if err != nil || er2 != nil || match[1] != m.Run || seq >= m.PostAttachWarmup || route != seq%m.Diversity || seen[seq] || e.PID != m.PID {
			return r, fmt.Errorf("preparation token identity mismatch")
		}
		seen[seq] = true
		r.Observed++
		v, found := terminals[id]
		if !found {
			continue
		}
		correct, wrong := applicationSegment(v, route)
		if wrong {
			return r, fmt.Errorf("wrong preparation application attribution")
		}
		identity := v.HotSpotIdentity
		if correct && identity != nil && identity.State == "safepoint_epoch_checked" && identity.Epoch != nil && *identity.Epoch%2 == 0 {
			routes[route] = true
			epochs[*identity.Epoch] = true
		}
	}
	for route := range routes {
		r.Routes = append(r.Routes, route)
	}
	for epoch := range epochs {
		r.Epochs = append(r.Epochs, epoch)
	}
	sort.Ints(r.Routes)
	sort.Slice(r.Epochs, func(i, j int) bool { return r.Epochs[i] < r.Epochs[j] })
	r.Ready = len(routes) == m.Diversity
	return r, nil
}

func applicationSegment(v model.StackEvidence, route int) (correct, wrong bool) {
	if v.Attribution != "event_correlated" {
		return false, false
	}
	leaf, root := -1, -1
	for j, f := range v.Frames {
		owner, method, ok := candidate.ParseJavaMethod(f.FunctionName)
		if !ok || owner != "BenchmarkFixture" {
			continue
		}
		if method == "leaf" {
			leaf = j
		}
		if method == fmt.Sprintf("route%d", route) {
			root = j
		}
		for other := 0; other < 8; other++ {
			if other != route && method == fmt.Sprintf("route%d", other) {
				wrong = true
			}
		}
	}
	return leaf >= 0 && root > leaf && !wrong, wrong
}
