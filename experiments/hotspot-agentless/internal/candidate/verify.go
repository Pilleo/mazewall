package candidate

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"unicode/utf8"
)

type Frame struct {
	FunctionName string `json:"function_name"`
}
type Evidence struct {
	Attribution string  `json:"attribution"`
	Frames      []Frame `json:"frames"`
}
type ThreadIdentity struct {
	Scope, Source   string
	LogicalID       uint64 `json:"logical_id"`
	ObjectLease     bool   `json:"object_lease"`
	MountCapture    uint64 `json:"mount_capture"`
	MountDecode     uint64 `json:"mount_decode"`
	ParentAvailable bool   `json:"parent_available"`
}
type Event struct {
	Filename   string
	PID        uint32          `json:"host_pid"`
	StartTicks uint64          `json:"process_start_ticks"`
	TID        uint32          `json:"host_tid"`
	Identity   *ThreadIdentity `json:"thread_identity"`
}
type Record struct {
	Kind      string
	SessionID *string `json:"session_id"`
	EventID   string  `json:"event_id"`
	Event     *Event
	Evidence  *Evidence
}
type RuntimeManifest struct {
	PID        uint32
	StartTicks uint64 `json:"start_ticks"`
	Tokens     map[string][]string
}
type VirtualOracle struct {
	LogicalID uint64 `json:"logical_id"`
	Route     []string
}
type RuntimeVerdict struct {
	Marked                 int    `json:"marked"`
	Useful                 int    `json:"useful"`
	ApplicationUnavailable int    `json:"application_unavailable"`
	LifetimeQualified      bool   `json:"lifetime_qualified"`
	Scope                  string `json:"scope"`
}
type VirtualVerdict struct {
	Marked              int  `json:"marked"`
	LogicalObserved     int  `json:"logical_observed"`
	CarrierTIDs         int  `json:"carrier_tids"`
	ParentAvailable     bool `json:"parent_available"`
	ProductionQualified bool `json:"production_qualified"`
}

func routeMatches(frames []Frame, expected []string) (bool, error) {
	if len(expected) == 0 {
		return false, fmt.Errorf("missing expected application route")
	}
	first := expected[0]
	owner := first
	if index := strings.LastIndex(first, "."); index >= 0 {
		owner = first[:index]
	}
	owner += "."
	var application []string
	for _, frame := range frames {
		if strings.Contains(frame.FunctionName, owner) {
			application = append(application, frame.FunctionName)
		}
	}
	if len(application) == 0 {
		return false, nil
	}
	if len(application) < len(expected) {
		return false, fmt.Errorf("wrong or reordered application route")
	}
	for index, name := range expected {
		if !strings.Contains(application[index], name) {
			return false, fmt.Errorf("wrong or reordered application route")
		}
	}
	return true, nil
}

func VerifyRuntime(manifest RuntimeManifest, rows []Record) (RuntimeVerdict, error) {
	result := RuntimeVerdict{Scope: "application_route_prefix"}
	events := map[string]Event{}
	terminals := map[string]Evidence{}
	sessions := map[string]bool{}
	for _, row := range rows {
		if row.SessionID != nil {
			sessions[*row.SessionID] = true
		}
		if row.Kind == "event" {
			if row.Event == nil {
				return result, fmt.Errorf("missing event")
			}
			if _, marked := manifest.Tokens[row.Event.Filename]; !marked {
				continue
			}
			if _, duplicate := events[row.EventID]; duplicate {
				return result, fmt.Errorf("duplicate event")
			}
			if row.Event.PID != manifest.PID || row.Event.StartTicks != manifest.StartTicks {
				return result, fmt.Errorf("process generation mismatch")
			}
			events[row.EventID] = *row.Event
		} else if row.Kind == "enrichment" {
			if _, duplicate := terminals[row.EventID]; duplicate {
				return result, fmt.Errorf("duplicate terminal")
			}
			if row.Evidence == nil {
				return result, fmt.Errorf("missing terminal evidence")
			}
			terminals[row.EventID] = *row.Evidence
		}
	}
	if len(sessions) > 1 {
		return result, fmt.Errorf("mixed collector sessions")
	}
	seen := map[string]bool{}
	for key, event := range events {
		if seen[event.Filename] {
			return result, fmt.Errorf("duplicate token")
		}
		seen[event.Filename] = true
		evidence, ok := terminals[key]
		if !ok {
			return result, fmt.Errorf("missing terminal")
		}
		if evidence.Attribution != "event_correlated" {
			if (evidence.Attribution != "unavailable" && evidence.Attribution != "skipped") || len(evidence.Frames) != 0 {
				return result, fmt.Errorf("invalid unavailable terminal")
			}
			result.ApplicationUnavailable++
			continue
		}
		matched, err := routeMatches(evidence.Frames, manifest.Tokens[event.Filename])
		if err != nil {
			return result, err
		}
		if matched {
			result.Useful++
		} else {
			result.ApplicationUnavailable++
		}
	}
	if len(seen) != len(manifest.Tokens) {
		return result, fmt.Errorf("missing marked token")
	}
	result.Marked = len(seen)
	return result, nil
}

func VerifyVirtual(oracle map[string]VirtualOracle, rows []Record) (VirtualVerdict, error) {
	var result VirtualVerdict
	terminals := map[string]Evidence{}
	for _, row := range rows {
		if row.Kind == "enrichment" {
			if _, duplicate := terminals[row.EventID]; duplicate {
				return result, fmt.Errorf("duplicate terminal")
			}
			if row.Evidence == nil {
				return result, fmt.Errorf("missing terminal evidence")
			}
			terminals[row.EventID] = *row.Evidence
		}
	}
	seen := map[string]bool{}
	carriers := map[uint32]bool{}
	for _, row := range rows {
		if row.Kind != "event" {
			continue
		}
		if row.Event == nil {
			return result, fmt.Errorf("missing event")
		}
		event := row.Event
		token := filepath.Base(event.Filename)
		expected, marked := oracle[token]
		if !marked {
			continue
		}
		if seen[token] {
			return result, fmt.Errorf("duplicate logical operation")
		}
		seen[token] = true
		carriers[event.TID] = true
		identity := event.Identity
		if identity != nil && identity.Source == "oracle" {
			return result, fmt.Errorf("oracle substituted for capture")
		}
		if identity == nil || identity.Scope != "mounted_virtual" {
			continue
		}
		if identity.LogicalID != expected.LogicalID {
			return result, fmt.Errorf("wrong logical thread")
		}
		if !identity.ObjectLease || identity.MountCapture == 0 || identity.MountCapture != identity.MountDecode {
			return result, fmt.Errorf("unproved mount/object identity")
		}
		if identity.ParentAvailable {
			return result, fmt.Errorf("parent source unqualified")
		}
		evidence, ok := terminals[row.EventID]
		if !ok {
			return result, fmt.Errorf("missing identity terminal")
		}
		if evidence.Attribution != "event_correlated" {
			if len(evidence.Frames) != 0 {
				return result, fmt.Errorf("unavailable frames not empty")
			}
			continue
		}
		matched, err := routeMatches(evidence.Frames, expected.Route)
		if err != nil || !matched {
			return result, fmt.Errorf("wrong or mixed logical route")
		}
		result.LogicalObserved++
	}
	if len(seen) != len(oracle) {
		return result, fmt.Errorf("missing virtual operations")
	}
	result.Marked = len(seen)
	result.CarrierTIDs = len(carriers)
	return result, nil
}

func Attached(path string) (bool, error) {
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	if !utf8.Valid(data) {
		return false, fmt.Errorf("invalid capture UTF8")
	}
	end := strings.LastIndexByte(string(data), '\n')
	if end < 0 {
		return false, nil
	}
	for _, line := range strings.Split(string(data[:end]), "\n") {
		var row struct{ Kind string }
		if err := json.Unmarshal([]byte(line), &row); err != nil {
			return false, err
		}
		if row.Kind == "collector_attached" {
			return true, nil
		}
	}
	return false, nil
}
