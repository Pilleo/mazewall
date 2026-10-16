package candidate

import (
	"bufio"
	"bytes"
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
	LogicalID       *uint64 `json:"logical_id"`
	ObjectLease     *bool   `json:"object_lease"`
	MountCapture    *uint64 `json:"mount_capture"`
	MountDecode     *uint64 `json:"mount_decode"`
	ParentAvailable *bool   `json:"parent_available"`
}
type Event struct {
	Filename      string
	SessionID     string          `json:"session_id"`
	EventID       string          `json:"event_id"`
	ClockDomain   string          `json:"clock_domain"`
	EventKind     string          `json:"event_kind"`
	CaptureBootNS *uint64         `json:"capture_boot_ns"`
	PID           uint32          `json:"host_pid"`
	StartTicks    uint64          `json:"process_start_ticks"`
	TID           uint32          `json:"host_tid"`
	Identity      *ThreadIdentity `json:"thread_identity"`
}
type Record struct {
	SchemaVersion int `json:"schema_version"`
	Kind          string
	SessionID     *string `json:"session_id"`
	EventID       string  `json:"event_id"`
	Event         *Event
	Evidence      *Evidence
	Summary       *captureSummary
	Recovery      json.RawMessage
}
type captureSummary struct {
	Enrichment                 *terminalCounters
	TerminalAccountingComplete *bool `json:"terminal_accounting_complete"`
}
type terminalCounters struct{ Received, Resolved, Unavailable, Skipped *uint64 }
type TargetIdentity struct {
	PID         uint32 `json:"pid"`
	StartTicks  uint64 `json:"start_ticks"`
	FixtureRoot string `json:"fixture_root"`
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
	index, err := validateCapture(rows, TargetIdentity{PID: manifest.PID, StartTicks: manifest.StartTicks})
	if err != nil {
		return result, err
	}
	if len(manifest.Tokens) == 0 {
		return result, fmt.Errorf("empty token manifest")
	}
	seen := map[string]bool{}
	for key, event := range index.events {
		if _, marked := manifest.Tokens[event.Filename]; !marked {
			continue
		}
		if seen[event.Filename] {
			return result, fmt.Errorf("duplicate token")
		}
		seen[event.Filename] = true
		evidence, ok := index.terminals[key]
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

func VerifyVirtual(oracle map[string]VirtualOracle, rows []Record, target TargetIdentity) (VirtualVerdict, error) {
	var result VirtualVerdict
	index, err := validateCapture(rows, target)
	if err != nil {
		return result, err
	}
	if !filepath.IsAbs(target.FixtureRoot) || len(oracle) == 0 {
		return result, fmt.Errorf("missing independent virtual fixture context")
	}
	for token, value := range oracle {
		if token == "" || token == "." || token == ".." || filepath.Base(token) != token || value.LogicalID == 0 {
			return result, fmt.Errorf("invalid virtual oracle token/identity")
		}
	}
	seen := map[string]bool{}
	carriers := map[uint32]bool{}
	for id, event := range index.events {
		token := filepath.Base(event.Filename)
		expected, marked := oracle[token]
		if !marked {
			continue
		}
		if event.Filename != filepath.Join(target.FixtureRoot, token) {
			return result, fmt.Errorf("virtual token full-path mismatch")
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
		if identity == nil {
			continue
		}
		switch identity.Scope {
		case "carrier_only", "logical_unavailable":
			continue
		case "mounted_virtual":
		default:
			return result, fmt.Errorf("unsupported logical scope")
		}
		if identity.Source != "candidate_native" {
			return result, fmt.Errorf("unqualified identity source")
		}
		if identity.LogicalID == nil || *identity.LogicalID != expected.LogicalID {
			return result, fmt.Errorf("wrong logical thread")
		}
		if identity.ObjectLease == nil || !*identity.ObjectLease || identity.MountCapture == nil || identity.MountDecode == nil || *identity.MountCapture == 0 || *identity.MountCapture != *identity.MountDecode {
			return result, fmt.Errorf("unproved mount/object identity")
		}
		if identity.ParentAvailable == nil || *identity.ParentAvailable {
			return result, fmt.Errorf("parent source unqualified")
		}
		evidence, ok := index.terminals[id]
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
	file, err := os.Open(path)
	if os.IsNotExist(err) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	defer file.Close()
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 4096), maxCaptureLine+2)
	scanner.Split(func(data []byte, atEOF bool) (int, []byte, error) {
		// The native writer may still be producing the final row.
		if atEOF && len(data) <= maxCaptureLine && bytes.IndexByte(data, '\n') < 0 {
			return 0, nil, nil
		}
		return captureLine(data, atEOF)
	})
	count := 0
	for scanner.Scan() {
		count++
		if count > maxCaptureRows || !utf8.Valid(scanner.Bytes()) {
			return false, fmt.Errorf("invalid readiness capture")
		}
		var row Record
		if err := json.Unmarshal(scanner.Bytes(), &row); err != nil {
			return false, err
		}
		if row.SchemaVersion != 1 || row.SessionID == nil || len(*row.SessionID) == 0 || len(*row.SessionID) > 128 {
			return false, fmt.Errorf("unsupported readiness schema/session")
		}
		if row.Kind == "collector_attached" {
			return true, nil
		}
	}
	return false, scanner.Err()
}
