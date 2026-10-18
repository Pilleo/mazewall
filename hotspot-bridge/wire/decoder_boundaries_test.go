package wire

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"testing"
)

func TestDecoderActualFilenameReadContract(t *testing.T) {
	x, rows := syntheticSession()
	rows[2].Event.ArgumentRead = int32(len(rows[2].Event.FilenameBytes) + 1)
	d := NewDecoder(x)
	if err := acceptRecords(d, rows); err != nil {
		t.Fatal("positive bpf_probe_read_user_str result rejected", err)
	}
	for _, tc := range []struct {
		name   string
		change func(*Event)
	}{
		{"read-count", func(e *Event) { e.ArgumentRead++ }},
		{"read-error-with-bytes", func(e *Event) { e.ArgumentRead = -14 }},
		{"truncation", func(e *Event) { e.PathTruncated = true }},
		{"register-error", func(e *Event) { e.RegisterRead = -14 }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			x, r := syntheticSession()
			r[2].Event.ArgumentRead = int32(len(r[2].Event.FilenameBytes) + 1)
			tc.change(r[2].Event)
			if err := acceptRecords(NewDecoder(x), r); !errors.Is(err, ErrIdentity) {
				t.Fatal("inconsistent native read accepted", err)
			}
		})
	}
}

func TestDecoderRequiresPresentZeroValuedEventFields(t *testing.T) {
	x, rows := syntheticSession()
	for _, field := range []string{"cpu", "sequence", "dirfd", "flags", "mode", "register_read", "argument_read", "path_truncated", "filename_bytes"} {
		t.Run(field, func(t *testing.T) {
			b, _ := json.Marshal(rows[2])
			var packet map[string]json.RawMessage
			if err := json.Unmarshal(b, &packet); err != nil {
				t.Fatal(err)
			}
			var raw map[string]json.RawMessage
			if err := json.Unmarshal(packet["event"], &raw); err != nil {
				t.Fatal(err)
			}
			packet["event"], _ = json.Marshal(raw)
			b, _ = json.Marshal(packet)
			control := NewDecoder(x)
			if err := acceptRecords(control, rows[:2]); err != nil {
				t.Fatal(err)
			}
			if err := control.Accept(b); err != nil {
				t.Fatal("unmutated reconstructed packet rejected", err)
			}
			delete(raw, field)
			packet["event"], _ = json.Marshal(raw)
			b, _ = json.Marshal(packet)
			d := NewDecoder(x)
			if err := acceptRecords(d, rows[:2]); err != nil {
				t.Fatal(err)
			}
			if err := d.Accept(b); !errors.Is(err, ErrJSON) || !strings.Contains(err.Error(), "missing/null event."+field) {
				t.Fatalf("missing %s: %v", field, err)
			}
		})
	}
}

func TestDecoderUnknownRawFidelity(t *testing.T) {
	x, rows := syntheticSession()
	d := NewDecoder(x)
	var full []byte
	for _, r := range rows {
		b, _ := json.Marshal(r)
		if r.Kind == "enrichment" {
			b = bytes.Replace(b, []byte(`"function_name":"Synthetic.leaf"`), []byte(`"function_name":"Synthetic.leaf","future": { "n" : 9007199254740993 }`), 1)
		}
		b = append(append(bytes.Clone(b[:len(b)-1]), []byte(`,"outer": { "n" : 18446744073709551615 }`)...), '}')
		full = append(full, b...)
		full = append(full, '\n')
		if err := d.Accept(b); err != nil {
			t.Fatal(err)
		}
		clear(b)
	}
	c, err := d.Finish(nil)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(c.RawJSONL, full) || string(c.Pairs[0].Evidence.Frames[0].Extensions["future"]) != `{ "n" : 9007199254740993 }` {
		t.Fatal("raw extension whitespace/integer fidelity lost")
	}
	h := sha256.Sum256(full)
	if c.RawSHA256 != hex.EncodeToString(h[:]) {
		t.Fatal("raw stream digest wrong")
	}
}

func TestDecoderUnavailableSkippedAndPartialRemainHonest(t *testing.T) {
	for _, outcome := range []string{"unavailable", "skipped"} {
		t.Run(outcome, func(t *testing.T) {
			x, r := syntheticSession()
			r[2].Event.CorrelationID = 0
			r[3].Evidence = &StackEvidence{Attribution: outcome, Walk: "unknown", ScopeLookup: "unknown", Reason: "definition_unavailable", Frames: []Frame{}}
			r[4].Summary.Stats.Resolved = 0
			if outcome == "unavailable" {
				r[4].Summary.Stats.Unavailable = 1
			} else {
				r[4].Summary.Stats.Skipped = 1
			}
			d := NewDecoder(x)
			if err := acceptRecords(d, r); err != nil {
				t.Fatal(err)
			}
			c, err := d.Finish(nil)
			if err != nil || c.Pairs[0].Event.CorrelationID != 0 || c.Pairs[0].Evidence.Attribution != outcome {
				t.Fatal(c, err)
			}
		})
	}
	x, r := syntheticSession()
	r[3].Evidence.Walk = "partial"
	r[3].Evidence.ScopeLookup = "unknown"
	r[3].Evidence.Frames[0].SourceFile = ""
	r[3].Evidence.Frames[0].SourceLine = nil
	r[3].Evidence.RawErrors = []string{"provider diagnostic"}
	s := r[4].Summary
	s.SelectedAccounting.Attempted = 3
	s.SelectedAccounting.Submitted = 2
	s.SelectedAccounting.ReserveFailed = 1
	*s.EventFinalization.NativeReserveFailures = 1
	*s.EventLossObservation.Observed = 1
	s.DefinitionReaderObservation.Lost = 2
	d := NewDecoder(x)
	if err := acceptRecords(d, r); err != nil {
		t.Fatal(err)
	}
	c, err := d.Finish(nil)
	if err != nil || c.Pairs[0].Evidence.Walk != "partial" || c.Summary.SelectedAccounting.Attempted != 3 {
		t.Fatal(c, err)
	}
}

func TestDecoderRecoveryClosesOnlyItsOriginalReference(t *testing.T) {
	x, r := syntheticSession()
	recovery := Record{SchemaVersion: 3, Kind: "recovery", SessionID: r[0].SessionID, EventID: r[2].EventID, PolicyEligibility: new(bool), Recovery: &RecoveryResult{CorrelationID: 17, BootNS: 103, Invalidated: true}}
	r[4].Summary.Stats.RecoveryAttempts = 1
	r[4].Summary.Stats.RecoveryInvalidations = 1
	rows := append(r[:4], recovery, r[4])
	d := NewDecoder(x)
	if err := acceptRecords(d, rows); err != nil {
		t.Fatal(err)
	}
	if _, err := d.Finish(nil); err != nil {
		t.Fatal(err)
	}
	for _, change := range []func(*Record){func(r *Record) { r.Recovery.CorrelationID++ }, func(r *Record) { r.EventID = "synthetic-session:0:2" }, func(r *Record) { r.Recovery.CorrelationID = 0 }} {
		x, r := syntheticSession()
		v := recovery
		v.Recovery = &RecoveryResult{CorrelationID: 17, BootNS: 103, Invalidated: true}
		change(&v)
		d := NewDecoder(x)
		if err := acceptRecords(d, append(r[:4], v)); !errors.Is(err, ErrPairing) {
			t.Fatal(err)
		}
	}
	d = NewDecoder(x)
	if err := acceptRecords(d, rows[:5]); err != nil {
		t.Fatal(err)
	}
	b, _ := json.Marshal(recovery)
	if err := d.Accept(b); !errors.Is(err, ErrPairing) {
		t.Fatal("duplicate recovery accepted", err)
	}
}

func TestDecoderPhysicalFinalityFlags(t *testing.T) {
	for _, field := range []string{"producers_quiesced", "buffer_drained", "receiver_joined", "final_counter_read", "resources_retired"} {
		t.Run(field, func(t *testing.T) {
			x, r := syntheticSession()
			d := NewDecoder(x)
			if err := acceptRecords(d, r[:4]); err != nil {
				t.Fatal(err)
			}
			b, _ := json.Marshal(r[4])
			b = bytes.Replace(b, []byte(`"`+field+`":true`), []byte(`"`+field+`":false`), 1)
			if err := d.Accept(b); !errors.Is(err, ErrFinality) {
				t.Fatal(err)
			}
		})
	}
}

func TestDecoderHardLimitsAndRetention(t *testing.T) {
	t.Run("frames", func(t *testing.T) {
		x, r := syntheticSession()
		r[3].Evidence.Frames = make([]Frame, 4097)
		if err := acceptRecords(NewDecoder(x), r); !errors.Is(err, ErrLimit) {
			t.Fatal(err)
		}
	})
	t.Run("rows", func(t *testing.T) {
		x, r := syntheticSession()
		d := NewDecoder(x)
		d.maxPairs = 0
		if err := acceptRecords(d, r[:2]); err != nil {
			t.Fatal(err)
		}
		for n := 1; n <= 21845; n++ {
			r[2].Event.Sequence = uint64(n)
			r[2].Event.CorrelationID = uint64(n)
			r[2].Event.EventID = fmt.Sprintf("synthetic-session:0:%d", n)
			r[2].EventID = r[2].Event.EventID
			r[3].EventID = r[2].EventID
			if err := acceptRecords(d, r[2:4]); err != nil {
				t.Fatal(n, err)
			}
			if n <= 21844 {
				recovery := Record{SchemaVersion: 3, Kind: "recovery", SessionID: r[0].SessionID, EventID: r[2].EventID, PolicyEligibility: new(bool), Recovery: &RecoveryResult{CorrelationID: uint64(n), BootNS: 103}}
				if err := acceptRecords(d, []Record{recovery}); err != nil {
					t.Fatal(n, err)
				}
			}
		}
		s := r[4].Summary
		s.Stats.Received = 21845
		s.Stats.Resolved = 21845
		s.Stats.RecoveryAttempts = 21844
		s.SelectedAccounting.Attempted = 21845
		s.SelectedAccounting.Submitted = 21845
		s.SelectedAccounting.Received = 21845
		s.SelectedAccounting.Terminals = 21845
		b, _ := json.Marshal(r[4])
		if err := d.Accept(b); !errors.Is(err, ErrLimit) {
			t.Fatal("row65537 was accepted", err)
		}
	})
	t.Run("tracked", func(t *testing.T) {
		x, r := syntheticSession()
		d := NewDecoder(x)
		d.maxPairs = 0
		if err := acceptRecords(d, r[:2]); err != nil {
			t.Fatal(err)
		}
		for n := 1; n <= 32769; n++ {
			r[2].Event.Sequence = uint64(n)
			r[2].Event.EventID = fmt.Sprintf("synthetic-session:0:%d", n)
			r[2].EventID = r[2].Event.EventID
			b, _ := json.Marshal(r[2])
			err := d.Accept(b)
			if n <= 32768 && err != nil {
				t.Fatal(n, err)
			}
			if n == 32769 && !errors.Is(err, ErrLimit) {
				t.Fatal("tracked cap not enforced", err)
			}
		}
	})
	t.Run("pairs", func(t *testing.T) {
		x, r := syntheticSession()
		d := NewDecoder(x)
		if err := acceptRecords(d, r[:2]); err != nil {
			t.Fatal(err)
		}
		for n := 1; n <= 4097; n++ {
			r[2].Event.Sequence = uint64(n)
			r[2].Event.EventID = fmt.Sprintf("synthetic-session:0:%d", n)
			r[2].EventID = r[2].Event.EventID
			r[3].EventID = r[2].EventID
			if err := acceptRecords(d, r[2:4]); err != nil {
				t.Fatal(n, err)
			}
		}
		s := r[4].Summary
		s.Stats.Received = 4097
		s.Stats.Resolved = 4097
		s.SelectedAccounting.Attempted = 4097
		s.SelectedAccounting.Submitted = 4097
		s.SelectedAccounting.Received = 4097
		s.SelectedAccounting.Terminals = 4097
		if err := acceptRecords(d, r[4:]); err != nil {
			t.Fatal(err)
		}
		c, err := d.Finish(nil)
		if err != nil || len(c.Pairs) != 4096 || c.Discarded != 1 {
			t.Fatal(len(c.Pairs), c.Discarded, err)
		}
	})
	t.Run("bytes", func(t *testing.T) {
		x, r := syntheticSession()
		d := NewDecoder(x)
		d.maxBytes = 1051000
		r[3].Evidence.Extensions = extensions{"large": json.RawMessage(`"` + strings.Repeat("x", 4000) + `"`)}
		if err := acceptRecords(d, r); err != nil {
			t.Fatal(err)
		}
		c, err := d.Finish(nil)
		if err != nil || len(c.Pairs) != 0 || c.Discarded != 1 || len(c.RawJSONL) > d.maxBytes {
			t.Fatal("byte cap did not discard and reconcile", c.Discarded, err)
		}
	})
}

func TestDecoderZeroValueFailsClosed(t *testing.T) {
	var d Decoder
	x, r := syntheticSession()
	_ = x
	b, _ := json.Marshal(r[0])
	if err := d.Accept(b); !errors.Is(err, ErrIdentity) {
		t.Fatal(err)
	}
}

func TestParseStartTicksRawPrecisionAndCommDelimiter(t *testing.T) {
	for _, n := range []string{"9007199254740993", "18446744073709551615"} {
		s := "123 (a ) strange comm) S " + strings.Repeat("0 ", 18) + n + " 0"
		got, err := ParseStartTicks(s)
		if err != nil || fmt.Sprint(got) != n {
			t.Fatal(got, err)
		}
	}
	for _, s := range []string{"", "123 (comm) S", "123 (comm) S " + strings.Repeat("0 ", 18) + "0", "123 (comm) S " + strings.Repeat("0 ", 18) + "18446744073709551616"} {
		if _, err := ParseStartTicks(s); err == nil {
			t.Fatal("malformed birth accepted", s)
		}
	}
}

func TestDecoderStrict2CompleteAndNoObservationFields(t *testing.T) {
	x, r := strictSession(0)
	d := NewDecoder(x)
	if err := acceptRecords(d, r); err != nil {
		t.Fatal(err)
	}
	c, err := d.Finish(nil)
	if err != nil || c.Profile != Strict25 {
		t.Fatal(c, err)
	}
	for _, row := range r {
		b, _ := json.Marshal(row)
		for _, key := range []string{`"policy_eligibility"`, `"validation_mode"`, `"mount_namespace"`} {
			if bytes.Contains(b, []byte(key)) {
				t.Fatal("schema2 gained observation fields", string(b))
			}
		}
	}
}

func TestDecoderDoesNotJoinByTimeOrTID(t *testing.T) {
	x, r := syntheticSession()
	first := r[2]
	second := r[2]
	copyEvent := *first.Event
	second.Event = &copyEvent
	second.Event.Sequence = 2
	second.Event.CPU = 1
	second.Event.EventID = "synthetic-session:1:2"
	second.EventID = second.Event.EventID
	second.Event.CorrelationID = 18
	terminal2 := r[3]
	terminal2.EventID = second.EventID
	copyEvidence := *terminal2.Evidence
	terminal2.Evidence = &copyEvidence
	terminal2.Evidence.Frames = []Frame{{FunctionName: "Second.leaf"}}
	s := r[4].Summary
	s.Stats.Received = 2
	s.Stats.Resolved = 2
	s.SelectedAccounting.Attempted = 2
	s.SelectedAccounting.Submitted = 2
	s.SelectedAccounting.Received = 2
	s.SelectedAccounting.Terminals = 2
	s.SelectedAccounting.PossibleCPUs = 2
	d := NewDecoder(x)
	if err := acceptRecords(d, []Record{r[0], r[1], first, second, terminal2, r[3], r[4]}); err != nil {
		t.Fatal(err)
	}
	c, err := d.Finish(nil)
	if err != nil || len(c.Pairs) != 2 || c.Pairs[0].Evidence.Frames[0].FunctionName != "Synthetic.leaf" || c.Pairs[1].Evidence.Frames[0].FunctionName != "Second.leaf" {
		t.Fatal("equal time/TID cross-wired", c, err)
	}
}

func TestDecoderAllReturnedReferencesAreOwned(t *testing.T) {
	x, r := syntheticSession()
	h := &HotSpotIdentity{State: "unknown", Epoch: new(uint64), ReadError: new(int32), Extensions: extensions{"future": json.RawMessage(`{ "n": 1 }`)}}
	r[2].Event.HotSpotIdentity = h
	r[3].Evidence.HotSpotIdentity = h
	r[3].Evidence.RawErrors = []string{"original"}
	f := &r[3].Evidence.Frames[0]
	f.SourceLine = new(uint32)
	f.SourceColumn = new(uint32)
	f.NativeAddress = new(uint64)
	f.RawBCI = new(uint32)
	f.Mapping = &Mapping{Start: 10, Extensions: extensions{"future": json.RawMessage(`1`)}}
	for _, e := range []*extensions{&r[1].Lifecycle.Extensions, &r[2].Event.Extensions, &r[3].Evidence.Extensions, &f.Extensions, &r[4].Summary.Extensions, &r[4].Summary.Stats.Extensions, &r[4].Summary.SelectedAccounting.Extensions, &r[4].Summary.EventFinalization.Extensions, &r[4].Summary.DefinitionStop.Extensions, &r[4].Summary.EventLossObservation.Extensions, &r[4].Summary.DefinitionReaderObservation.Extensions} {
		*e = extensions{"future": json.RawMessage(`123`)}
	}
	d := NewDecoder(x)
	if err := acceptRecords(d, r); err != nil {
		t.Fatal(err)
	}
	c, err := d.Finish(nil)
	if err != nil {
		t.Fatal(err)
	}
	before, _ := json.Marshal(c)
	p := &c.Pairs[0]
	*p.Event.HotSpotIdentity.Epoch = 9
	*p.Event.HotSpotIdentity.ReadError = 9
	*p.Evidence.HotSpotIdentity.Epoch = 9
	p.Evidence.RawErrors[0] = "changed"
	f = &p.Evidence.Frames[0]
	*f.SourceLine = 9
	*f.SourceColumn = 9
	*f.NativeAddress = 9
	*f.BCI = 9
	*f.RawBCI = 9
	f.Mapping.Start = 99
	s := &c.Summary
	s.SelectedAccounting.Attempted = 99
	s.EventFinalization.Scope = "changed"
	*s.EventFinalization.NativeReserveFailures = 99
	s.DefinitionStop.Semantics = "changed"
	s.DefinitionReaderObservation.Lost = 99
	s.EventLossObservation.Semantics = "changed"
	for _, e := range []extensions{p.Event.HotSpotIdentity.Extensions, p.Event.Extensions, p.Evidence.Extensions, f.Extensions, f.Mapping.Extensions, s.Extensions, s.Stats.Extensions, s.SelectedAccounting.Extensions, s.EventFinalization.Extensions, s.DefinitionStop.Extensions, s.EventLossObservation.Extensions, s.DefinitionReaderObservation.Extensions} {
		for _, v := range e {
			v[0] = '!'
		}
	}
	c2, err := d.Finish(nil)
	after, _ := json.Marshal(c2)
	if err != nil || !bytes.Equal(before, after) {
		t.Fatal("nested output aliases decoder", err)
	}
	l, _ := d.Ready()
	l.Extensions["future"][0] = '!'
	l2, _ := d.Ready()
	if string(l2.Extensions["future"]) != "123" {
		t.Fatal("ready raw values alias decoder")
	}
}

func TestDecoderExactUpperBoundInputs(t *testing.T) {
	x, r := syntheticSession()
	d := NewDecoder(x)
	b, _ := json.Marshal(r[0])
	b = append(b, bytes.Repeat([]byte{' '}, 1048576-len(b))...)
	if err := d.Accept(b); err != nil {
		t.Fatal("max line rejected", err)
	}
	x, r = syntheticSession()
	d = NewDecoder(x)
	b, _ = json.Marshal(r[0])
	b = append(append(bytes.Clone(b[:len(b)-1]), []byte(`,"depth":`+strings.Repeat("[", 63)+"0"+strings.Repeat("]", 63))...), '}')
	if err := d.Accept(b); err != nil {
		t.Fatal("nesting64 rejected", err)
	}
	x, r = syntheticSession()
	s := r[4].Summary
	s.SelectedAccounting.Attempted = ^uint64(0)
	s.SelectedAccounting.ReserveFailed = ^uint64(0) - 1
	*s.EventFinalization.NativeReserveFailures = ^uint64(0) - 1
	*s.EventLossObservation.Observed = ^uint64(0) - 1
	r[2].Event.CaptureBootNS = ^uint64(0)
	d = NewDecoder(x)
	if err := acceptRecords(d, r); err != nil {
		t.Fatal("uint64 maximum truncated or overflowed", err)
	}
	c, err := d.Finish(nil)
	if err != nil || c.Pairs[0].Event.CaptureBootNS != ^uint64(0) || c.Summary.SelectedAccounting.Attempted != ^uint64(0) {
		t.Fatal(err)
	}
}

func TestDecoderFinalDiagnosticsAndCPUCardinality(t *testing.T) {
	for _, tc := range []struct {
		name   string
		mutate func([]Record)
		class  error
		text   string
	}{
		{"native-message", func(r []Record) { r[4].Summary.EventFinalization.Error = "original native EACCES" }, ErrFinality, "original native EACCES"},
		{"summary-message", func(r []Record) { r[4].Summary.Errors = []string{"original producer failure"} }, ErrCollector, "original producer failure"},
		{"cpu-cardinality", func(r []Record) {
			r[2].Event.CPU = 1
			r[2].Event.EventID = "synthetic-session:1:1"
			r[2].EventID = r[2].Event.EventID
			r[3].EventID = r[2].EventID
		}, ErrAccounting, "CPU"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			x, r := syntheticSession()
			tc.mutate(r)
			err := acceptRecords(NewDecoder(x), r)
			if !errors.Is(err, tc.class) || !strings.Contains(err.Error(), tc.text) {
				t.Fatal("diagnostic/cardinality lost", err)
			}
		})
	}
}
