package candidate

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
)

func TestObservedRoutesDetectSwap(t *testing.T) {
	want := map[string]string{"a-1": "routeA", "b-1": "routeB"}
	if err := VerifyObservedRoutes([]ObservedRoute{{"a-1", "routeA"}, {"b-1", "routeB"}}, want); err != nil {
		t.Fatal("valid routes rejected", err)
	}
	if err := VerifyObservedRoutes([]ObservedRoute{{"a-1", "routeB"}, {"b-1", "routeA"}}, want); err == nil {
		t.Fatal("worker swap accepted")
	}
}

// This optional replay diagnoses a rejected native prefix; it cannot admit it.
func TestObservedPreservedNativePrefix(t *testing.T) {
	root := os.Getenv("MW_OBSERVED_PREFIX")
	if root == "" {
		t.Skip("explicit preserved native prefix not selected")
	}
	for _, name := range []string{"ordinary", "compiled-churn", "class-unload", "target-exit"} {
		t.Run(name, func(t *testing.T) {
			dir := filepath.Join(root, name)
			var input struct {
				Target wire.Target
				Oracle map[string]observedOracle
			}
			data, err := os.ReadFile(filepath.Join(dir, "identity-and-oracle.json"))
			if err != nil {
				t.Fatal(err)
			}
			if err := json.Unmarshal(data, &input); err != nil {
				t.Fatal(err)
			}
			var result struct{ Error string }
			data, err = os.ReadFile(filepath.Join(dir, "result.json"))
			if err != nil {
				t.Fatal(err)
			}
			if err := json.Unmarshal(data, &result); err != nil {
				t.Fatal(err)
			}
			if result.Error == "" {
				t.Fatal("expected the preserved rejected capture")
			}
			expected := wire.Expected{Target: input.Target, Profile: wire.Observed25, Schema: 3, Provider: wire.Provider{Name: "otel-ebpf-profiler-via-gadget", GadgetRevision: "7097db144ccd8dab15373f27ec98d2c3d22e4588", ProfilerRevision: "ef6cf29492c8a980f889052903fa2dd0c0765cc0", PatchSHA256: "473acb5ab31168cdba2ad390c105ca83a163e91d9443e53ce45cfecd6f30c58b"}}
			decoder := wire.NewDecoder(expected)
			file, err := os.Open(filepath.Join(dir, "capture.jsonl"))
			if err != nil {
				t.Fatal(err)
			}
			defer file.Close()
			scan := bufio.NewScanner(file)
			scan.Buffer(make([]byte, 4096), 1048577)
			events, terminals := map[string]wire.Event{}, map[string]wire.StackEvidence{}
			var order []string
			var first, last uint64
			frameQuality := map[string]int{}
			for scan.Scan() {
				if err := decoder.Accept(scan.Bytes()); err != nil {
					t.Fatal("actual SDK rejected prefix row", err)
				}
				var row wire.Record
				if err := json.Unmarshal(scan.Bytes(), &row); err != nil {
					t.Fatal(err)
				}
				if row.Event != nil {
					if _, ok := events[row.EventID]; ok {
						t.Fatal("duplicate raw occurrence")
					}
					events[row.EventID] = *row.Event
					order = append(order, row.EventID)
				}
				if row.Evidence != nil {
					if _, ok := terminals[row.EventID]; ok {
						t.Fatal("duplicate terminal")
					}
					terminals[row.EventID] = *row.Evidence
				}
			}
			if err := scan.Err(); err != nil {
				t.Fatal(err)
			}
			if _, ready := decoder.Ready(); !ready {
				t.Fatal("actual SDK never accepted readiness")
			}
			capture, finishErr := decoder.Finish(errors.New(result.Error))
			if finishErr == nil || len(capture.Pairs) != 0 {
				t.Fatal("rejected EOF became publishable")
			}
			pairs := make([]wire.Pair, 0, len(events))
			raw, terminal := 0, 0
			for _, id := range order {
				event := events[id]
				evidence, ok := terminals[id]
				if !ok {
					t.Fatal("missing prefix terminal", id)
				}
				pairs = append(pairs, wire.Pair{Event: event, Evidence: evidence})
				if strings.HasPrefix(event.Filename, "/fixture/gates/marked-") {
					raw++
					terminal++
					if first == 0 || event.CaptureBootNS < first {
						first = event.CaptureBootNS
					}
					if event.CaptureBootNS > last {
						last = event.CaptureBootNS
					}
					frameQuality["terminal:"+evidence.Attribution+":"+evidence.Reason]++
					for _, frame := range evidence.Frames {
						frameQuality["frame_type:"+frame.FrameType]++
						frameQuality["lookup:"+frame.ScopeLookup]++
						frameQuality["bci:"+frame.BCIState]++
						if frame.SourceLine == nil {
							frameQuality["source_line_absent"]++
						}
					}
				}
			}
			routes, useful, err := observedRoutes(pairs, input.Oracle)
			if err != nil {
				t.Fatal(err)
			}
			want := map[string]string{}
			for token, entry := range input.Oracle {
				want[token] = entry.Method
			}
			if err := VerifyObservedRoutes(routes, want); err != nil {
				t.Fatal(err)
			}
			gateErr := verifyObservedMarked(routes, want, raw, terminal)
			if name != "target-exit" && gateErr != nil {
				t.Fatal("rejected session's route-only diagnostic gate", gateErr)
			}
			if raw != 400 || terminal != 400 {
				t.Fatalf("marked prefix denominators %d/%d", raw, terminal)
			}
			a, b := -1, -1
			for i, pair := range pairs {
				if _, ok := input.Oracle[filepath.Base(pair.Event.Filename)]; !ok {
					continue
				}
				_, n, err := observedRoutes([]wire.Pair{pair}, input.Oracle)
				if err == nil && n == 1 {
					if input.Oracle[filepath.Base(pair.Event.Filename)].Method == "routeA" {
						a = i
					} else {
						b = i
					}
				}
			}
			if a < 0 || b < 0 {
				t.Fatal("no actual useful two-route controls")
			}
			pairs[a].Evidence, pairs[b].Evidence = pairs[b].Evidence, pairs[a].Evidence
			if _, _, err := observedRoutes(pairs, input.Oracle); err == nil {
				t.Fatal("actual captured worker-stack swap escaped verifier")
			}
			if err := VerifyObservedRoutes(routes[:len(routes)-1], want); err == nil {
				t.Fatal("actual missing token escaped")
			}
			duplicate := append(append([]ObservedRoute{}, routes...), routes[0])
			if err := VerifyObservedRoutes(duplicate, want); err == nil {
				t.Fatal("actual duplicate token escaped")
			}
			diagnostic := map[string]any{"scope": "rejected prefix diagnostic only", "raw_marked": raw, "terminal_marked": terminal, "useful": useful, "wrong_route": 0, "marked_span_ns": last - first, "frame_quality": frameQuality, "sdk_finish_error": finishErr.Error(), "full_drain_finality": "unavailable", "selected_conservation": "unavailable", "route_only_gate": gateErr == nil, "actual_stack_swap_detected": true, "missing_and_duplicate_detected": true}
			data, err = json.MarshalIndent(diagnostic, "", "  ")
			if err != nil {
				t.Fatal(err)
			}
			path := filepath.Join(os.Getenv("MW_OBSERVED_DIAGNOSTICS"), name+"-prefix.json")
			fileOut, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0644)
			if err != nil {
				t.Fatal(err)
			}
			_, writeErr := fileOut.Write(append(data, '\n'))
			if err := errors.Join(writeErr, fileOut.Close()); err != nil {
				t.Fatal(err)
			}
			t.Logf("rejected prefix: marked=%d/%d useful=%d/400 wrong=0; actual stack swap rejected; SDK finality rejected", raw, terminal, useful)
		})
	}
}

func TestObservedCapturedWorkerSwap(t *testing.T) {
	oracle := map[string]observedOracle{"a": {Method: "routeA", TID: 101}, "b": {Method: "routeB", TID: 102}}
	pairs := []wire.Pair{
		{Event: wire.Event{Filename: "/fixture/gates/a", TID: 101}, Evidence: wire.StackEvidence{Attribution: "event_correlated", Frames: []wire.Frame{{FunctionName: "void ObservedWorkload.leaf(java.nio.file.Path)"}, {FunctionName: "void ObservedWorkload.routeA(java.nio.file.Path)"}}}},
		{Event: wire.Event{Filename: "/fixture/gates/b", TID: 102}, Evidence: wire.StackEvidence{Attribution: "event_correlated", Frames: []wire.Frame{{FunctionName: "void ObservedWorkload.leaf(java.nio.file.Path)"}, {FunctionName: "void ObservedWorkload.routeB(java.nio.file.Path)"}}}},
	}
	if _, _, err := observedRoutes(pairs, oracle); err != nil {
		t.Fatal("valid independent oracle rejected", err)
	}
	pairs[0].Evidence, pairs[1].Evidence = pairs[1].Evidence, pairs[0].Evidence
	if _, _, err := observedRoutes(pairs, oracle); err == nil {
		t.Fatal("swapped worker stacks accepted with unchanged exact TIDs/tokens")
	}
	pairs[0].Evidence, pairs[1].Evidence = pairs[1].Evidence, pairs[0].Evidence
	pairs[0].Event.TID = 102
	if _, _, err := observedRoutes(pairs, oracle); err == nil {
		t.Fatal("wrong exact worker TID accepted")
	}
}

func TestObservedRoutesRejectMissingDuplicateAndUnknown(t *testing.T) {
	want := map[string]string{"a": "routeA", "b": "routeB"}
	for name, routes := range map[string][]ObservedRoute{
		"missing":      {{"a", "routeA"}},
		"duplicate":    {{"a", "routeA"}, {"a", "routeA"}},
		"foreign":      {{"a", "routeA"}, {"c", "routeB"}},
		"empty-oracle": {},
	} {
		t.Run(name, func(t *testing.T) {
			if err := VerifyObservedRoutes(routes, want); err == nil {
				t.Fatal("invalid token set accepted")
			}
		})
	}
}

func TestObservedMarkedGate(t *testing.T) {
	want := map[string]string{}
	routes := make([]ObservedRoute, 400)
	for i := range routes {
		token := fmt.Sprintf("marked-%03d", i)
		method := []string{"routeA", "routeB"}[i%2]
		want[token] = method
		routes[i] = ObservedRoute{token, method}
	}
	if err := verifyObservedMarked(routes, want, 400, 400); err != nil {
		t.Fatal("complete positive rejected", err)
	}
	for i := 360; i < 400; i++ {
		routes[i].Method = ""
	}
	if err := verifyObservedMarked(routes, want, 400, 400); err != nil {
		t.Fatal("360 useful positive rejected", err)
	}
	for _, counts := range [][2]int{{399, 400}, {400, 399}, {401, 400}} {
		if err := verifyObservedMarked(routes, want, counts[0], counts[1]); err == nil {
			t.Fatal("bad raw/terminal denominator accepted", counts)
		}
	}
	routes[359].Method = ""
	if err := verifyObservedMarked(routes, want, 400, 400); err == nil {
		t.Fatal("359 useful accepted")
	}
}
