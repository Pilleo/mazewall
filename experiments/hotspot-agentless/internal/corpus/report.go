package corpus

import (
	"bufio"
	"encoding/csv"
	"encoding/json"
	"fmt"
	"go.opentelemetry.io/ebpf-profiler/libpf"
	"io"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
	"regexp"
	"strconv"
	"strings"
)

type operation struct {
	run, branch, filename string
	sequence              int
}

var runPattern = regexp.MustCompile(`^[a-z0-9]{1,32}$`)

type Report struct {
	SchemaVersion       int                    `json:"schema_version"`
	Run                 string                 `json:"run"`
	Expected            int                    `json:"expected_operations"`
	Observed            int                    `json:"observed_operations"`
	Unobserved          int                    `json:"unobserved_operations"`
	ResolvedSegments    int                    `json:"resolved_application_segments"`
	UnavailableSegments int                    `json:"unavailable_application_segments"`
	WrongAttribution    int                    `json:"wrong_attribution_or_order"`
	CompiledSegments    map[string]int         `json:"compiled_segments_by_branch"`
	Compiler            map[string]InlineProof `json:"compiler_inline_proof"`
	Summary             *model.Summary         `json:"collection_summary"`
	Failures            []string               `json:"failures,omitempty"`
}

func readManifest(r io.Reader, perBranch int) (map[string]operation, string, error) {
	if perBranch <= 0 || perBranch > 10000 {
		return nil, "", fmt.Errorf("invalid corpus size")
	}
	c := csv.NewReader(io.LimitReader(r, 4*1024*1024))
	header, err := c.Read()
	if err != nil || strings.Join(header, ",") != "run,worker,sequence,branch,filename" {
		return nil, "", fmt.Errorf("invalid operation manifest header")
	}
	ops := map[string]operation{}
	keys := map[string]bool{}
	run := ""
	counts := map[string]int{}
	for {
		row, err := c.Read()
		if err == io.EOF {
			break
		}
		if err != nil {
			return nil, "", err
		}
		if len(row) != 5 {
			return nil, "", fmt.Errorf("invalid manifest row")
		}
		seq, err := strconv.Atoi(row[2])
		b := row[3]
		if err != nil || seq < 0 || seq >= perBranch || row[1] != "0" || (b != "A" && b != "B") || !runPattern.MatchString(row[0]) {
			return nil, "", fmt.Errorf("invalid operation identity")
		}
		if run == "" {
			run = row[0]
		}
		if row[0] != run {
			return nil, "", fmt.Errorf("mixed manifest runs")
		}
		filename := "/fixture/mw-inline-corpus-" + run + "-w0-s" + strconv.Itoa(seq) + "-" + b
		key := strconv.Itoa(seq) + "/" + b
		if keys[key] || row[4] != filename {
			return nil, "", fmt.Errorf("duplicate or noncanonical operation token")
		}
		keys[key] = true
		ops[filename] = operation{run: run, branch: b, filename: filename, sequence: seq}
		counts[b]++
	}
	if counts["A"] != perBranch || counts["B"] != perBranch {
		return nil, "", fmt.Errorf("manifest must contain %d operations per branch", perBranch)
	}
	return ops, run, nil
}

// Analyze pairs exact filename tokens, not clocks, stack IDs or same-TID guesses.
func Analyze(manifest, records, compiler io.Reader, pid uint32, perBranch int) (Report, error) {
	r := Report{SchemaVersion: 1, CompiledSegments: map[string]int{"A": 0, "B": 0}}
	fail := func(message string) {
		if len(r.Failures) < 20 {
			r.Failures = append(r.Failures, message)
		}
	}
	ops, run, err := readManifest(manifest, perBranch)
	if err != nil {
		return r, err
	}
	r.Run = run
	r.Expected = len(ops)
	r.Compiler, err = ReadCompiler(compiler, pid)
	if err != nil {
		return r, err
	}
	if pid == 0 {
		return r, fmt.Errorf("expected ordinary-user host PID required")
	}
	events := map[string]model.Event{}
	terminals := map[string]model.StackEvidence{}
	session := ""
	generation := uint64(0)
	s := bufio.NewScanner(io.LimitReader(records, 256*1024*1024))
	s.Buffer(make([]byte, 4096), 4*1024*1024)
	for s.Scan() {
		var row model.Record
		if err := json.Unmarshal(s.Bytes(), &row); err != nil {
			return r, err
		}
		if row.SchemaVersion != 1 || row.SessionID == "" {
			return r, fmt.Errorf("missing capture schema/session")
		}
		if session == "" {
			session = row.SessionID
		}
		if row.SessionID != session {
			return r, fmt.Errorf("mixed collector sessions")
		}
		switch row.Kind {
		case "event":
			if row.Event == nil {
				return r, fmt.Errorf("missing event")
			}
			e := *row.Event
			if _, exists := events[row.EventID]; exists {
				return r, fmt.Errorf("duplicate event occurrence")
			}
			if e.EventID != row.EventID || e.SessionID != session || e.PID != pid || e.TID == 0 || e.ProcessStartTicks == 0 || e.ClockDomain != "CLOCK_BOOTTIME" {
				return r, fmt.Errorf("wrong capture identity")
			}
			if generation == 0 {
				generation = e.ProcessStartTicks
			}
			if e.ProcessStartTicks != generation {
				return r, fmt.Errorf("mixed process incarnations")
			}
			events[row.EventID] = e
		case "enrichment":
			if row.Evidence == nil {
				return r, fmt.Errorf("missing terminal")
			}
			if _, ok := events[row.EventID]; !ok {
				return r, fmt.Errorf("terminal without event")
			}
			if _, exists := terminals[row.EventID]; exists {
				return r, fmt.Errorf("duplicate terminal")
			}
			terminals[row.EventID] = *row.Evidence
		case "session_summary":
			if r.Summary != nil {
				return r, fmt.Errorf("duplicate summary")
			}
			r.Summary = row.Summary
		case "collector_attached", "recovery":
		default:
			return r, fmt.Errorf("unknown capture record")
		}
		if len(events) > 100000 {
			return r, fmt.Errorf("corpus capture exceeds bound")
		}
	}
	if err := s.Err(); err != nil {
		return r, err
	}
	if r.Summary == nil || !r.Summary.TerminalAccountingComplete || len(events) != len(terminals) || r.Summary.Stats.Received != uint64(len(events)) || r.Summary.Stats.Resolved+r.Summary.Stats.Unavailable+r.Summary.Stats.Skipped != uint64(len(events)) {
		return r, fmt.Errorf("incomplete delivered-event accounting")
	}
	h := r.Summary
	if h.CollectionHealth != "partial" || len(h.Errors) != 0 || h.EventLoss != nil || h.DefinitionLoss != nil || h.EventLossObservation == nil || h.EventLossObservation.Observed == nil || h.EventLossObservation.Complete || h.DefinitionReaderObservation == nil || h.DefinitionReaderObservation.Complete {
		return r, fmt.Errorf("unsupported whole-session loss claim")
	}
	seen := map[string]bool{}
	workerTID := uint32(0)
	for id, e := range events {
		op, marked := ops[e.Filename]
		if !marked {
			if strings.Contains(e.Filename, "mw-inline-corpus-") {
				fail("unexpected operation token")
			}
			continue
		}
		if seen[e.Filename] {
			fail("duplicate observation for operation token")
			continue
		}
		seen[e.Filename] = true
		r.Observed++
		if workerTID == 0 {
			workerTID = e.TID
		}
		if workerTID != e.TID {
			fail("single-worker corpus observed multiple worker TIDs")
		}
		if e.RegisterRead != 0 || e.ArgumentRead <= 0 || e.PathTruncated || e.DirFD != -100 || e.Flags != 0 || string(e.FilenameBytes) != e.Filename {
			fail("unreadable or invalid operation arguments")
		}
		v := terminals[id]
		if v.Walk == "complete" {
			fail("unsupported complete stack claim")
		}
		if v.Attribution != "event_correlated" {
			if len(v.Frames) != 0 {
				fail("unavailable result substituted frames")
			}
			r.UnavailableSegments++
			continue
		}
		if e.CorrelationID == 0 || v.Provider.PatchSHA256 == "" {
			fail("attribution missing reference/provenance")
		}
		leaf, root := -1, -1
		compiled := true
		bad := false
		for i, f := range v.Frames {
			if f.BCI != nil && f.BCIState != "observed_in_range" {
				fail("BCI without provider evidence")
			}
			if strings.Contains(f.FunctionName, "InlineCorpusFixture.path") {
				if f.ProviderType != uint8(libpf.HotSpotFrame) || f.ErrorFrame {
					bad = true
				}
				if !strings.Contains(f.FunctionName, "InlineCorpusFixture.path"+op.branch+"(") || root != -1 {
					bad = true
				}
				root = i
				compiled = compiled && (f.ScopeLookup == "exact_pc" || f.ScopeLookup == "preceding_pc")
			}
			if strings.Contains(f.FunctionName, "InlineCorpusFixture.sharedLeaf(") {
				if f.ProviderType != uint8(libpf.HotSpotFrame) || f.ErrorFrame {
					bad = true
				}
				if leaf != -1 {
					bad = true
				}
				leaf = i
				compiled = compiled && (f.ScopeLookup == "exact_pc" || f.ScopeLookup == "preceding_pc")
			}
		}
		if bad || (leaf != -1 && root != -1 && leaf >= root) {
			r.WrongAttribution++
			fail("wrong branch or application-frame order")
			continue
		}
		if leaf == -1 || root == -1 {
			r.UnavailableSegments++
			continue
		}
		r.ResolvedSegments++
		if compiled {
			r.CompiledSegments[op.branch]++
		}
	}
	r.Unobserved = r.Expected - r.Observed
	if r.Unobserved != 0 {
		fail("unobserved intended operations; loss unexplained")
	}
	for _, b := range []string{"A", "B"} {
		if r.CompiledSegments[b] == 0 {
			fail("no reconstructed compiled application segment for branch " + b)
		}
	}
	if len(r.Failures) > 0 {
		return r, fmt.Errorf("inline corpus failed: %s", strings.Join(r.Failures, "; "))
	}
	return r, nil
}
