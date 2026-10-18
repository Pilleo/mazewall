package wire

import (
	"bytes"
	"encoding/json"
	"errors"
	"math"
	"os"
	"reflect"
	"strings"
	"syscall"
	"testing"
)

// This frozen native projection intentionally retains its historical provider pin.
func TestReadyRejectsForeignProcessBirth(t *testing.T) {
	data, err := os.ReadFile("testdata/legacy-serialized.jsonl")
	if err != nil {
		t.Fatal(err)
	}
	lines := bytes.Split(bytes.TrimSpace(data), []byte{'\n'})
	var ready Record
	if err := json.Unmarshal(lines[1], &ready); err != nil {
		t.Fatal(err)
	}
	x := Expected{Schema: 2, Profile: Strict25, Provider: ready.Lifecycle.Provider,
		Target: Target{PID: ready.Lifecycle.PID, StartTicks: ready.Lifecycle.ProcessStartTicks}}
	control := NewDecoder(x)
	for _, line := range lines[:2] {
		if err := control.Accept(line); err != nil {
			t.Fatal("historical lifecycle control rejected", err)
		}
	}
	if got, ok := control.Ready(); !ok || got.PID != x.Target.PID || got.ProcessStartTicks != x.Target.StartTicks {
		t.Fatal("historical control did not bind ready identity", got)
	}
	x.Target.StartTicks++
	d := NewDecoder(x)
	if err := d.Accept(lines[0]); err != nil {
		t.Fatal(err)
	}
	if err := d.Accept(lines[1]); !errors.Is(err, ErrIdentity) {
		t.Fatal("foreign birth must fail with ErrIdentity", err)
	}
}

func syntheticSession() (Expected, []Record) {
	p := Provider{Name: "otel-ebpf-profiler-via-gadget", GadgetRevision: strings.Repeat("1", 40), ProfilerRevision: strings.Repeat("2", 40), PatchSHA256: strings.Repeat("3", 64)}
	x := Expected{Schema: 3, Profile: Observed25, Provider: p, Target: Target{PID: 123, StartTicks: 456, CgroupID: 789, MountNS: 42, ImageID: "synthetic-image", ContainerID: "synthetic-container"}}
	l := Lifecycle{PID: 123, ClockDomain: "CLOCK_BOOTTIME", BootNS: 100, RuntimeProfile: string(Observed25), Provider: p}
	start := Record{SchemaVersion: 3, Kind: "session_started", SessionID: "synthetic-session", PolicyEligibility: new(bool), Lifecycle: &l}
	ready := start
	bound := l
	ready.Lifecycle = &bound
	ready.Kind = "collector_ready"
	bound.BootNS = 101
	bound.ProcessStartTicks = 456
	bound.CgroupID = 789
	bound.MountNS = 42
	bound.ValidationMode = "upstream_generation_checks"
	bound.JVMBuildID = strings.Repeat("4", 40)
	bound.JVMSHA256 = strings.Repeat("5", 64)
	e := Event{SessionID: start.SessionID, EventID: "synthetic-session:0:1", CPU: 0, Sequence: 1, PID: 123, TID: 124, ProcessStartTicks: 456, CgroupID: 789, CaptureBootNS: 102, ClockDomain: "CLOCK_BOOTTIME", Kind: "openat_handler_entry", CorrelationID: 17, DirFD: -100, Filename: "/synthetic", FilenameBytes: []byte("/synthetic"), ArgumentRead: 11}
	event := Record{SchemaVersion: 3, Kind: "event", SessionID: start.SessionID, EventID: e.EventID, PolicyEligibility: new(bool), Event: &e}
	ev := StackEvidence{Attribution: "event_correlated", Walk: "returned", ScopeLookup: "exact_pc", Provider: p, Frames: []Frame{{ProviderType: 4, FrameType: "hotspot", FunctionName: "Synthetic.leaf", RawPosition: 0, PositionDomain: "provider_hotspot_bci_or_fallback", BCI: new(uint32), BCIState: "observed_in_range", ScopeLookup: "exact_pc"}}}
	terminal := Record{SchemaVersion: 3, Kind: "enrichment", SessionID: start.SessionID, EventID: e.EventID, PolicyEligibility: new(bool), Evidence: &ev}
	s := Summary{Stats: Stats{Received: 1, Resolved: 1, MaxPending: 1, MaxPendingBytes: 10}, TerminalAccountingComplete: true, CollectionHealth: "partial",
		UnprovenCollectionSources:   []Lane{Definitions, Enrichment, Wire, Retention},
		SelectedAccounting:          &SelectedAccountingDiagnostic{SchemaVersion: 1, Scope: "owned_openat_handler_entry", SessionID: start.SessionID, PID: 123, Birth: 456, MapID: 7, PossibleCPUs: 1, Attempted: 1, Submitted: 1, Received: 1, Terminals: 1, Complete: true},
		EventFinalization:           &EventFinalizationObservation{Scope: "owned_openat_event_ring", Phase: "resources_retired", StopCause: "request", ProducersQuiesced: true, BufferDrained: true, ReceiverJoined: true, FinalCounterRead: true, ResourcesRetired: true, NativeReserveFailures: new(uint64)},
		DefinitionStop:              &DefinitionStopObservation{ReaderStopped: true, ConsumerStopped: true, BuffersDrained: true, AuxiliaryStopped: true, Semantics: "owned_definition_perf_drained_partial_loss_snapshot"},
		EventLossObservation:        &LossObservation{Observed: new(uint64), Semantics: "quiescent_native_reserve_failures_partial_snapshot"},
		DefinitionReaderObservation: &ReaderObservation{BufferBytesPerCPU: 524288, Semantics: "consumed_trace_perf_records_partial_snapshot"}}
	summary := Record{SchemaVersion: 3, Kind: "session_summary", SessionID: start.SessionID, PolicyEligibility: new(bool), Summary: &s}
	return x, []Record{start, ready, event, terminal, summary}
}

func acceptRecords(d *Decoder, rows []Record) error {
	for _, r := range rows {
		b, err := json.Marshal(r)
		if err != nil {
			return err
		}
		if err = d.Accept(b); err != nil {
			return err
		}
	}
	return nil
}

func TestDecoderCompleteSyntheticSession(t *testing.T) {
	for _, zero := range []bool{false, true} {
		t.Run(map[bool]string{false: "retained", true: "zero-retention"}[zero], func(t *testing.T) {
			x, rows := syntheticSession()
			d := NewDecoder(x)
			if zero {
				d.maxPairs = 0
			}
			if err := acceptRecords(d, rows); err != nil {
				t.Fatal(err)
			}
			c, err := d.Finish(nil)
			if err != nil {
				t.Fatal(err)
			}
			if c.SessionID != "synthetic-session" || c.Summary.Stats.Received != 1 || c.Target != x.Target || len(c.RawSHA256) != 64 {
				t.Fatalf("incomplete capture: %+v", c)
			}
			if zero {
				if len(c.Pairs) != 0 || c.Discarded != 1 {
					t.Fatal("discarded pair not reconciled", c)
				}
			} else if len(c.Pairs) != 1 || c.Pairs[0].Evidence.Frames[0].FunctionName != "Synthetic.leaf" {
				t.Fatal("pair lost")
			}
		})
	}
}

func TestDecoderRejectsOneFieldContradictions(t *testing.T) {
	for _, tc := range []struct {
		name   string
		mutate func([]Record) []Record
		want   error
	}{
		{"schema", func(r []Record) []Record { r[2].SchemaVersion = 2; return r }, ErrIdentity},
		{"profile", func(r []Record) []Record { r[1].Lifecycle.RuntimeProfile = string(Strict25); return r }, ErrIdentity},
		{"session", func(r []Record) []Record { r[2].SessionID = "other"; return r }, ErrIdentity},
		{"provider", func(r []Record) []Record { r[1].Lifecycle.Provider.PatchSHA256 = strings.Repeat("6", 64); return r }, ErrIdentity},
		{"pid", func(r []Record) []Record { r[2].Event.PID++; return r }, ErrIdentity},
		{"birth", func(r []Record) []Record { r[2].Event.ProcessStartTicks++; return r }, ErrIdentity},
		{"cgroup", func(r []Record) []Record { r[2].Event.CgroupID++; return r }, ErrIdentity},
		{"mount", func(r []Record) []Record { r[1].Lifecycle.MountNS++; return r }, ErrIdentity},
		{"clock", func(r []Record) []Record { r[2].Event.ClockDomain = "CLOCK_MONOTONIC"; return r }, ErrIdentity},
		{"policy-absent", func(r []Record) []Record { r[2].PolicyEligibility = nil; return r }, ErrIdentity},
		{"policy-true", func(r []Record) []Record {
			r[2].PolicyEligibility = new(bool)
			*r[2].PolicyEligibility = true
			return r
		}, ErrIdentity},
		{"event-id", func(r []Record) []Record { r[2].Event.EventID = "synthetic-session:0:2"; return r }, ErrPairing},
		{"sequence-zero", func(r []Record) []Record { r[2].Event.Sequence = 0; return r }, ErrPairing},
		{"zero-reference-resolved", func(r []Record) []Record { r[2].Event.CorrelationID = 0; return r }, ErrIdentity},
		{"filename", func(r []Record) []Record { r[2].Event.Filename = "wrong"; return r }, ErrIdentity},
		{"duplicate-event", func(r []Record) []Record { return append(r[:3], append([]Record{r[2]}, r[3:]...)...) }, ErrPairing},
		{"duplicate-terminal", func(r []Record) []Record { return append(r[:4], append([]Record{r[3]}, r[4:]...)...) }, ErrPairing},
		{"duplicate-summary", func(r []Record) []Record { return append(r, r[4]) }, ErrOrder},
		{"missing-terminal", func(r []Record) []Record { return append(r[:3], r[4]) }, ErrAccounting},
		{"terminal-first", func(r []Record) []Record { r[2], r[3] = r[3], r[2]; return r }, ErrPairing},
		{"late-event", func(r []Record) []Record { return append(r, r[2]) }, ErrOrder},
		{"overflow", func(r []Record) []Record {
			r[4].Summary.Stats.Resolved = math.MaxUint64
			r[4].Summary.Stats.Unavailable = 1
			return r
		}, ErrAccounting},
		{"selected-binding", func(r []Record) []Record { r[4].Summary.SelectedAccounting.Birth++; return r }, ErrAccounting},
		{"selected-map-zero", func(r []Record) []Record { r[4].Summary.SelectedAccounting.MapID = 0; return r }, ErrAccounting},
		{"selected-cpus-zero", func(r []Record) []Record { r[4].Summary.SelectedAccounting.PossibleCPUs = 0; return r }, ErrAccounting},
		{"aggregate-overflow", func(r []Record) []Record {
			s := r[4].Summary.SelectedAccounting
			s.Attempted = math.MaxUint64
			s.Submitted = math.MaxUint64
			s.ReserveFailed = 1
			return r
		}, ErrAccounting},
		{"native-error", func(r []Record) []Record { r[4].Summary.EventFinalization.Error = "native EACCES"; return r }, ErrFinality},
		{"loss-observation-contradiction", func(r []Record) []Record { *r[4].Summary.EventLossObservation.Observed = 1; return r }, ErrAccounting},
		{"loss-observation-scope", func(r []Record) []Record {
			r[4].Summary.EventLossObservation.Semantics = "forwarded_reports_lower_bound"
			return r
		}, ErrAccounting},
		{"retired", func(r []Record) []Record { r[4].Summary.EventFinalization.ResourcesRetired = false; return r }, ErrFinality},
		{"definition-reader", func(r []Record) []Record { r[4].Summary.DefinitionStop.ReaderStopped = false; return r }, ErrFinality},
		{"definition-consumer", func(r []Record) []Record { r[4].Summary.DefinitionStop.ConsumerStopped = false; return r }, ErrFinality},
		{"definition-drain", func(r []Record) []Record { r[4].Summary.DefinitionStop.BuffersDrained = false; return r }, ErrFinality},
		{"auxiliary", func(r []Record) []Record { r[4].Summary.DefinitionStop.AuxiliaryStopped = false; return r }, ErrFinality},
		{"global-claim", func(r []Record) []Record { r[4].Summary.CollectionHealth = "complete"; return r }, ErrAccounting},
		{"loss-claim", func(r []Record) []Record { r[4].Summary.EventFinalization.LossComplete = true; return r }, ErrAccounting},
		{"erased-unknown-lanes", func(r []Record) []Record { r[4].Summary.UnprovenCollectionSources = nil; return r }, ErrAccounting},
	} {
		t.Run(tc.name, func(t *testing.T) {
			x, rows := syntheticSession()
			d := NewDecoder(x)
			err := acceptRecords(d, tc.mutate(rows))
			if err == nil {
				_, err = d.Finish(nil)
			}
			if !errors.Is(err, tc.want) {
				t.Fatalf("want %v, got %v", tc.want, err)
			}
			if c, e := d.Finish(nil); e == nil || !reflect.DeepEqual(c, Capture{}) {
				t.Fatal("failed session published", c, e)
			}
		})
	}
}

func TestDecoderJSONBoundaries(t *testing.T) {
	x, rows := syntheticSession()
	b, _ := json.Marshal(rows[0])
	for _, tc := range []struct {
		name string
		b    []byte
		want error
	}{
		{"duplicate", []byte(`{"kind":"a","kind":"b"}`), ErrJSON},
		{"escaped-duplicate", []byte(`{"kind":"a","\u006bind":"b"}`), ErrJSON},
		{"nested-duplicate", []byte(`{"x":{"a":1,"a":2}}`), ErrJSON},
		{"trailing", append(append([]byte{}, b...), []byte(` {}`)...), ErrJSON},
		{"utf8", append([]byte(`{"x":"`), 0xff, '"', '}'), ErrJSON},
		{"nesting65", []byte(`{"x":` + strings.Repeat("[", 64) + "0" + strings.Repeat("]", 64) + "}"), ErrLimit},
		{"line-max-plus-one", bytes.Repeat([]byte{' '}, 1048577), ErrLimit},
		{"uint-overflow", bytes.Replace(b, []byte(`"boot_ns":100`), []byte(`"boot_ns":18446744073709551616`), 1), ErrJSON},
		{"case-fold", bytes.Replace(b, []byte(`"kind"`), []byte(`"Kind"`), 1), ErrJSON},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if err := NewDecoder(x).Accept(tc.b); !errors.Is(err, tc.want) {
				t.Fatalf("want %v got %v", tc.want, err)
			}
		})
	}
}

func TestDecoderExactIntegersUnknownBytesAndOwnedOutputs(t *testing.T) {
	x, rows := syntheticSession()
	e := rows[2].Event
	e.CaptureBootNS = 9007199254740993
	e.CorrelationID = math.MaxInt64
	e.FilenameBytes = []byte{'a', 0xff, 0xfe}
	e.Filename = "a��"
	e.ArgumentRead = 4
	rows[3].Evidence.Frames[0].Extensions = extensions{"future": json.RawMessage(`{ "n":9007199254740993 }`)}
	rows[3].Evidence.Frames[0].SourceLine = new(uint32)
	rows[3].Evidence.Frames[0].Mapping = &Mapping{Start: 9, End: 10}
	rows[2].Extensions = extensions{"outer": json.RawMessage(`{ "n":18446744073709551615 }`)}
	d := NewDecoder(x)
	for _, r := range rows {
		b, _ := json.Marshal(r)
		if err := d.Accept(b); err != nil {
			t.Fatal(err)
		}
		clear(b)
	}
	c, err := d.Finish(nil)
	if err != nil {
		t.Fatal(err)
	}
	if c.Pairs[0].Event.CaptureBootNS != 9007199254740993 || c.Pairs[0].Event.CorrelationID != math.MaxInt64 || !bytes.Equal(c.Pairs[0].Event.FilenameBytes, []byte{'a', 0xff, 0xfe}) {
		t.Fatal("integer/raw bytes changed")
	}
	f := &c.Pairs[0].Evidence.Frames[0]
	if string(f.Extensions["future"]) != `{"n":9007199254740993}` {
		t.Fatalf("unknown integer lost: %s", f.Extensions["future"])
	}
	*f.SourceLine = 99
	f.Mapping.Start = 99
	f.Extensions["future"][0] = '!'
	c.Pairs[0].Event.FilenameBytes[0] = '!'
	*c.Summary.EventLossObservation.Observed = 99
	c.Summary.UnprovenCollectionSources[0] = "changed"
	c.RawJSONL[0] = '!'
	c2, err := d.Finish(nil)
	if err != nil {
		t.Fatal(err)
	}
	if c2.Pairs[0].Evidence.Frames[0].Mapping.Start != 9 || *c2.Pairs[0].Evidence.Frames[0].SourceLine != 0 || c2.Pairs[0].Event.FilenameBytes[0] != 'a' || *c2.Summary.EventLossObservation.Observed != 0 || c2.Summary.UnprovenCollectionSources[0] != Definitions || c2.RawJSONL[0] != '{' {
		t.Fatal("output aliases decoder")
	}
	r, ok := d.Ready()
	if !ok {
		t.Fatal("ready lost")
	}
	r.Extensions = extensions{"bad": json.RawMessage(`0`)}
	r2, _ := d.Ready()
	if r2.Extensions != nil {
		t.Fatal("ready output aliases state")
	}
}

func TestDecoderWaitAndCollectorFailures(t *testing.T) {
	x, rows := syntheticSession()
	d := NewDecoder(x)
	if err := acceptRecords(d, rows); err != nil {
		t.Fatal(err)
	}
	c, err := d.Finish(syscall.EACCES)
	if !errors.Is(err, ErrProcess) || !errors.Is(err, syscall.EACCES) || len(c.Pairs) != 0 {
		t.Fatal("Wait failure hidden", c, err)
	}
	d = NewDecoder(x)
	failure := rows[0]
	failure.Kind = "collector_error"
	failure.Lifecycle.Message = "original permission failure"
	failure.Lifecycle.Errno = int(syscall.EPERM)
	b, _ := json.Marshal(failure)
	err = d.Accept(b)
	if !errors.Is(err, ErrCollector) || !errors.Is(err, syscall.EPERM) || !strings.Contains(err.Error(), "original permission failure") {
		t.Fatal("collector cause lost", err)
	}
	d = NewDecoder(x)
	if err := acceptRecords(d, rows[:4]); err != nil {
		t.Fatal(err)
	}
	if _, err := d.Finish(nil); !errors.Is(err, ErrOrder) {
		t.Fatal("missing summary accepted", err)
	}
}
