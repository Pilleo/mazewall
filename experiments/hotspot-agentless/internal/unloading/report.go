package unloading

import (
	"bufio"
	"encoding/csv"
	"encoding/json"
	"fmt"
	"io"
	"regexp"
	"sort"
	"strconv"
	"strings"

	"io.mazewall/experiments/hotspot-agentless/internal/identity"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
)

var classPattern = regexp.MustCompile(`UnloadG(\d{5})`)
var rawPattern = regexp.MustCompile(`mw-raw-identity pid=(\d+) tid=(\d+) ref=(\d+) ktime=(\d+) method=(0x[0-9a-f]+) cookie=(0x[0-9a-f]+) subtype=(\d+) cache_hit=(true|false) names=(".*")`)

type WrongExample struct {
	EventID   string `json:"event_id"`
	Expected  int    `json:"expected_generation"`
	Actual    []int  `json:"actual_generations"`
	Reference uint64 `json:"reference"`
}
type RawIdentity struct {
	Reference     uint64 `json:"reference"`
	Method        string `json:"method"`
	Cookie        string `json:"cookie"`
	Subtype       int    `json:"subtype"`
	FrameCacheHit bool   `json:"frame_cache_hit"`
	Names         string `json:"names"`
}
type Report struct {
	CheckedSegments           int                `json:"epoch_checked_segments"`
	ReferenceEpochConflicts   int                `json:"reference_epoch_conflicts"`
	SameEpochReferenceReuses  int                `json:"same_epoch_reference_reuses"`
	WrongBranch               int                `json:"wrong_branch_events"`
	GenerationBranches        []GenerationBranch `json:"generation_branches,omitempty"`
	RejectionReasons          map[string]int     `json:"rejection_reasons,omitempty"`
	Expected                  int                `json:"expected_operations"`
	Observed                  int                `json:"observed_operations"`
	Unobserved                int                `json:"unobserved_operations"`
	CorrectSegments           int                `json:"correct_application_segments"`
	Unavailable               int                `json:"unavailable_application_segments"`
	WrongGeneration           int                `json:"wrong_generation_events"`
	UnloadedGenerations       int                `json:"confirmed_unloaded_generations"`
	CrossGenerationReferences int                `json:"references_used_across_generations"`
	RepeatedMethodCookieKeys  int                `json:"repeated_method_cookie_keys"`
	Examples                  []WrongExample     `json:"wrong_examples,omitempty"`
	Identities                []RawIdentity      `json:"raw_identities,omitempty"`
}

type GenerationBranch struct {
	Generation  int      `json:"generation"`
	Branch      string   `json:"branch"`
	Expected    int      `json:"expected"`
	Observed    int      `json:"observed"`
	Correct     int      `json:"correct"`
	Unavailable int      `json:"unavailable"`
	Epochs      []uint64 `json:"captured_epochs"`
}

func Analyze(manifest, records, unloadLog, diagnostics io.Reader, pid uint32) (Report, error) {
	return analyze(manifest, records, unloadLog, diagnostics, pid, false)
}

func AnalyzeEpoch(manifest, records, unloadLog, diagnostics io.Reader, pid uint32) (Report, error) {
	return analyze(manifest, records, unloadLog, diagnostics, pid, true)
}

func analyze(manifest, records, unloadLog, diagnostics io.Reader, pid uint32, strict bool) (Report, error) {
	r := Report{}
	coverage := map[string]*GenerationBranch{}
	epochs := map[string]map[uint64]bool{}
	refEpochs := map[uint64]map[uint64]bool{}
	if strict {
		r.RejectionReasons = map[string]int{}
	}
	c := csv.NewReader(io.LimitReader(manifest, 4*1024*1024))
	header, err := c.Read()
	if err != nil || strings.Join(header, ",") != "generation,sequence,branch,filename" {
		return r, fmt.Errorf("invalid unloading manifest")
	}
	ops := map[string]int{}
	branches := map[string]string{}
	generations := map[int]bool{}
	for {
		row, err := c.Read()
		if err == io.EOF {
			break
		}
		if err != nil {
			return r, err
		}
		if len(row) != 4 {
			return r, fmt.Errorf("invalid manifest row")
		}
		g, err := strconv.Atoi(row[0])
		if err != nil || g < 0 || g > 10000 {
			return r, fmt.Errorf("invalid generation")
		}
		seq, err := strconv.Atoi(row[1])
		if err != nil || seq < 0 || seq > 10000 || (row[2] != "A" && row[2] != "B") {
			return r, fmt.Errorf("invalid sequence/branch")
		}
		expected := fmt.Sprintf("/fixture/mw-unload-g%05d-s%03d-%s", g, seq, row[2])
		if row[3] != expected {
			return r, fmt.Errorf("wrong token")
		}
		if _, exists := ops[expected]; exists {
			return r, fmt.Errorf("duplicate token")
		}
		ops[expected] = g
		branches[expected] = row[2]
		generations[g] = true
		key := fmt.Sprintf("%d/%s", g, row[2])
		if coverage[key] == nil {
			coverage[key] = &GenerationBranch{Generation: g, Branch: row[2]}
			epochs[key] = map[uint64]bool{}
		}
		coverage[key].Expected++
		if len(ops) > 100000 {
			return r, fmt.Errorf("manifest exceeds bound")
		}
	}
	r.Expected = len(ops)
	if r.Expected == 0 || pid == 0 {
		return r, fmt.Errorf("empty manifest or PID")
	}
	unloaded := map[int]bool{}
	s := bufio.NewScanner(io.LimitReader(unloadLog, 16*1024*1024))
	for s.Scan() {
		if !strings.Contains(s.Text(), "unloading class") {
			continue
		}
		for _, m := range classPattern.FindAllStringSubmatch(s.Text(), -1) {
			g, _ := strconv.Atoi(m[1])
			if generations[g] {
				unloaded[g] = true
			}
		}
	}
	if err := s.Err(); err != nil {
		return r, err
	}
	r.UnloadedGenerations = len(unloaded)
	if r.UnloadedGenerations == 0 {
		return r, fmt.Errorf("no JVM-confirmed unloading")
	}
	events := map[string]model.Event{}
	terminals := map[string]model.StackEvidence{}
	summary := false
	s = bufio.NewScanner(io.LimitReader(records, 256*1024*1024))
	s.Buffer(make([]byte, 4096), 4*1024*1024)
	for s.Scan() {
		var row model.Record
		if err := json.Unmarshal(s.Bytes(), &row); err != nil {
			return r, err
		}
		switch row.Kind {
		case "event":
			if row.Event == nil || row.Event.PID != pid {
				return r, fmt.Errorf("wrong/missing event PID")
			}
			if _, ok := events[row.EventID]; ok {
				return r, fmt.Errorf("duplicate event")
			}
			events[row.EventID] = model.Event(*row.Event)
		case "enrichment":
			if row.Evidence == nil {
				return r, fmt.Errorf("missing evidence")
			}
			if _, ok := terminals[row.EventID]; ok {
				return r, fmt.Errorf("duplicate terminal")
			}
			terminals[row.EventID] = *row.Evidence
		case "session_summary":
			summary = row.Summary != nil && row.Summary.TerminalAccountingComplete
		}
	}
	if err := s.Err(); err != nil {
		return r, err
	}
	if !summary || len(events) != len(terminals) {
		return r, fmt.Errorf("incomplete event terminal accounting")
	}
	seen := map[string]bool{}
	refs := map[uint64]map[int]bool{}
	identityMismatch := false
	for id, e := range events {
		g, ok := ops[e.Filename]
		if !ok {
			continue
		}
		if seen[e.Filename] {
			return r, fmt.Errorf("duplicate operation")
		}
		seen[e.Filename] = true
		r.Observed++
		key := fmt.Sprintf("%d/%s", g, branches[e.Filename])
		bucket := coverage[key]
		bucket.Observed++
		if e.HotSpotIdentity != nil && e.HotSpotIdentity.State == "stable" && e.HotSpotIdentity.Epoch != nil {
			epoch := *e.HotSpotIdentity.Epoch
			epochs[key][epoch] = true
			if e.CorrelationID != 0 {
				if refEpochs[e.CorrelationID] == nil {
					refEpochs[e.CorrelationID] = map[uint64]bool{}
				} else if refEpochs[e.CorrelationID][epoch] {
					r.SameEpochReferenceReuses++
				}
				refEpochs[e.CorrelationID][epoch] = true
			}
		}
		if e.CorrelationID != 0 {
			if refs[e.CorrelationID] == nil {
				refs[e.CorrelationID] = map[int]bool{}
			}
			refs[e.CorrelationID][g] = true
		}
		v := terminals[id]
		if v.Attribution != "event_correlated" {
			r.Unavailable++
			bucket.Unavailable++
			if strict {
				r.RejectionReasons[v.Reason]++
			}
			continue
		}
		actual := map[int]bool{}
		leaf, root := -1, -1
		wrongBranch := false
		for i, f := range v.Frames {
			matches := classPattern.FindStringSubmatch(f.FunctionName)
			if len(matches) == 0 {
				continue
			}
			a, _ := strconv.Atoi(matches[1])
			actual[a] = true
			if strings.Contains(f.FunctionName, ".sharedLeaf(") {
				leaf = i
			}
			if strings.Contains(f.FunctionName, ".path"+branches[e.Filename]+"(") {
				root = i
			}
			other := "B"
			if branches[e.Filename] == "B" {
				other = "A"
			}
			if strings.Contains(f.FunctionName, ".path"+other+"(") {
				wrongBranch = true
			}
		}
		bad := false
		for a := range actual {
			if a != g {
				bad = true
			}
		}
		if bad {
			r.WrongGeneration++
			if len(r.Examples) < 20 {
				x := WrongExample{EventID: id, Expected: g, Reference: e.CorrelationID}
				for a := range actual {
					x.Actual = append(x.Actual, a)
				}
				r.Examples = append(r.Examples, x)
			}
		} else if wrongBranch {
			r.WrongBranch++
		} else if leaf >= 0 && root > leaf {
			r.CorrectSegments++
			bucket.Correct++
			if strict {
				captured, checked := e.HotSpotIdentity, v.HotSpotIdentity
				if captured == nil || captured.State != "stable" || captured.Epoch == nil || checked == nil || checked.State != "safepoint_epoch_checked" || checked.Epoch == nil || *captured.Epoch != *checked.Epoch || checked.Profile != identity.ProfileName || e.TID == 0 || e.CorrelationID == 0 {
					identityMismatch = true
				} else {
					r.CheckedSegments++
				}
			}
		} else {
			r.Unavailable++
			bucket.Unavailable++
		}
	}
	for _, set := range refEpochs {
		if len(set) > 1 {
			r.ReferenceEpochConflicts++
		}
	}
	for key, bucket := range coverage {
		for epoch := range epochs[key] {
			bucket.Epochs = append(bucket.Epochs, epoch)
		}
		sort.Slice(bucket.Epochs, func(i, j int) bool { return bucket.Epochs[i] < bucket.Epochs[j] })
		if strict {
			r.GenerationBranches = append(r.GenerationBranches, *bucket)
		}
	}
	sort.Slice(r.GenerationBranches, func(i, j int) bool {
		a, b := r.GenerationBranches[i], r.GenerationBranches[j]
		if a.Generation != b.Generation {
			return a.Generation < b.Generation
		}
		return a.Branch < b.Branch
	})
	for _, g := range refs {
		if len(g) > 1 {
			r.CrossGenerationReferences++
		}
	}
	s = bufio.NewScanner(io.LimitReader(diagnostics, 64*1024*1024))
	s.Buffer(make([]byte, 4096), 1024*1024)
	keys := map[string]int{}
	for s.Scan() {
		m := rawPattern.FindStringSubmatch(s.Text())
		if len(m) == 0 {
			continue
		}
		p, _ := strconv.ParseUint(m[1], 10, 32)
		if uint32(p) != pid {
			continue
		}
		ref, _ := strconv.ParseUint(m[3], 10, 64)
		names, _ := strconv.Unquote(m[9])
		if !strings.Contains(names, "UnloadG") {
			continue
		}
		subtype, _ := strconv.Atoi(m[7])
		r.Identities = append(r.Identities, RawIdentity{Reference: ref, Method: m[5], Cookie: m[6], Subtype: subtype, FrameCacheHit: m[8] == "true", Names: names})
		keys[m[5]+"/"+m[6]]++
	}
	if err := s.Err(); err != nil {
		return r, err
	}
	for _, count := range keys {
		if count > 1 {
			r.RepeatedMethodCookieKeys++
		}
	}
	r.Unobserved = r.Expected - r.Observed
	if r.Unobserved != 0 || r.WrongGeneration != 0 || r.WrongBranch != 0 {
		return r, fmt.Errorf("unloading investigation: unobserved=%d wrong_generation=%d wrong_branch=%d", r.Unobserved, r.WrongGeneration, r.WrongBranch)
	}
	if strict {
		if identityMismatch || r.CheckedSegments != r.CorrectSegments || r.ReferenceEpochConflicts != 0 || r.CrossGenerationReferences != 0 || r.UnloadedGenerations != len(generations) {
			return r, fmt.Errorf("strict unloading identity/reference/lifetime acceptance failed")
		}
		for g := range generations {
			for _, branch := range []string{"A", "B"} {
				b := coverage[fmt.Sprintf("%d/%s", g, branch)]
				if b == nil || b.Observed != b.Expected || g > 0 && b.Correct == 0 {
					return r, fmt.Errorf("missing useful generation %d branch %s attribution", g, branch)
				}
			}
		}
	}
	return r, nil
}
