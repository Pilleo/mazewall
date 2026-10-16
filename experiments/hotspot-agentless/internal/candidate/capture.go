package candidate

import (
	"bytes"
	"encoding/json"
	"fmt"
	"math"
	"strings"
)

const maxCaptureRows = 65536
const maxCaptureLine = 1048576
const maxCaptureFrames = 4096

type captureIndex struct {
	events    map[string]Event
	terminals map[string]Evidence
}

// Validate the complete stream before oracle selection, including carrier-only rows.
func validateCapture(rows []Record, target TargetIdentity) (captureIndex, error) {
	index := captureIndex{events: map[string]Event{}, terminals: map[string]Evidence{}}
	if target.PID == 0 || target.PID > math.MaxInt32 || target.StartTicks == 0 || len(rows) == 0 || len(rows) > maxCaptureRows {
		return index, fmt.Errorf("invalid capture target/row count")
	}
	var session string
	var summary *captureSummary
	attached := false
	observed := [4]uint64{}
	for _, row := range rows {
		if summary != nil {
			return index, fmt.Errorf("record after summary")
		}
		if row.SchemaVersion != 1 || row.SessionID == nil || len(*row.SessionID) == 0 || len(*row.SessionID) > 128 {
			return index, fmt.Errorf("unsupported capture schema/session")
		}
		if session == "" {
			session = *row.SessionID
		}
		if session != *row.SessionID {
			return index, fmt.Errorf("mixed collector sessions")
		}
		switch row.Kind {
		case "collector_attached":
			if attached {
				return index, fmt.Errorf("duplicate attachment")
			}
			attached = true
		case "event", "enrichment":
			if len(row.EventID) > 256 || !strings.HasPrefix(row.EventID, session+":") || len(row.EventID) == len(session)+1 {
				return index, fmt.Errorf("foreign occurrence")
			}
			if row.Kind == "event" {
				event := row.Event
				if event == nil || event.SessionID != session || event.EventID != row.EventID {
					return index, fmt.Errorf("nested occurrence mismatch")
				}
				if event.PID != target.PID || event.StartTicks != target.StartTicks || event.TID == 0 || event.TID > math.MaxInt32 {
					return index, fmt.Errorf("target/process generation mismatch")
				}
				if event.ClockDomain != "CLOCK_BOOTTIME" || event.EventKind != "openat_handler_entry" || event.CaptureBootNS == nil {
					return index, fmt.Errorf("unsupported event/clock")
				}
				if event.Filename == "" {
					return index, fmt.Errorf("missing event path")
				}
				if _, found := index.events[row.EventID]; found {
					return index, fmt.Errorf("duplicate event")
				}
				index.events[row.EventID] = *event
				observed[0]++
			} else {
				evidence := row.Evidence
				if evidence == nil || evidence.Frames == nil || len(evidence.Frames) > maxCaptureFrames {
					return index, fmt.Errorf("missing or excessive terminal frames")
				}
				if _, found := index.terminals[row.EventID]; found {
					return index, fmt.Errorf("duplicate terminal")
				}
				switch evidence.Attribution {
				case "event_correlated":
					observed[1]++
				case "unavailable":
					if len(evidence.Frames) != 0 {
						return index, fmt.Errorf("unavailable frames not empty")
					}
					observed[2]++
				case "skipped":
					if len(evidence.Frames) != 0 {
						return index, fmt.Errorf("skipped frames not empty")
					}
					observed[3]++
				default:
					return index, fmt.Errorf("unsupported attribution")
				}
				index.terminals[row.EventID] = *evidence
			}
		case "session_summary":
			if row.Summary == nil {
				return index, fmt.Errorf("missing summary")
			}
			summary = row.Summary
		case "recovery":
			raw := bytes.TrimSpace(row.Recovery)
			if len(raw) == 0 || raw[0] != '{' || !json.Valid(raw) {
				return index, fmt.Errorf("invalid recovery")
			}
		default:
			return index, fmt.Errorf("unsupported capture kind %q", row.Kind)
		}
	}
	if !attached || summary == nil || summary.Enrichment == nil || summary.TerminalAccountingComplete == nil || !*summary.TerminalAccountingComplete || len(index.events) != len(index.terminals) {
		return index, fmt.Errorf("incomplete capture")
	}
	for id := range index.events {
		if _, ok := index.terminals[id]; !ok {
			return index, fmt.Errorf("unpaired occurrence")
		}
	}
	c := summary.Enrichment
	for i, value := range []*uint64{c.Received, c.Resolved, c.Unavailable, c.Skipped} {
		if value == nil || *value != observed[i] {
			return index, fmt.Errorf("terminal counter mismatch")
		}
	}
	return index, nil
}
