package wire

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"testing"
)

// Actual native schema2 vocabulary, with separate captured/checked epoch pointers.
func strictSession(epoch uint64) (Expected, []Record) {
	x, r := syntheticSession()
	x.Schema = 2
	x.Profile = Strict25
	for i := range r {
		r[i].SchemaVersion = 2
		r[i].PolicyEligibility = nil
		if l := r[i].Lifecycle; l != nil {
			l.RuntimeProfile = string(Strict25)
			l.ValidationMode = ""
			l.CgroupID = 0
			l.MountNS = 0
		}
	}
	captured, checked := epoch, epoch
	r[2].Event.HotSpotIdentity = &HotSpotIdentity{State: "stable", Epoch: &captured, Profile: string(Strict25)}
	r[3].Evidence.HotSpotIdentity = &HotSpotIdentity{State: "safepoint_epoch_checked", Epoch: &checked, Profile: string(Strict25)}
	// Frozen native projection uses provider type5/jvm and interpreter scope.
	r[3].Evidence.ScopeLookup = "interpreter"
	f := &r[3].Evidence.Frames[0]
	f.ProviderType, f.FrameType, f.ScopeLookup, f.ScopeWalk = 5, "jvm", "interpreter", "unknown"
	f.RawBCI = new(uint32)
	return x, r
}

func TestDecoderStrictIdentityBinding(t *testing.T) {
	for _, epoch := range []uint64{0, 2} {
		for _, discard := range []bool{false, true} {
			t.Run(fmt.Sprintf("epoch%d/discard%t", epoch, discard), func(t *testing.T) {
				x, r := strictSession(epoch)
				d := NewDecoder(x)
				if discard {
					d.maxPairs = 0
				}
				c, err := consumeSession(d, encodedSession(t, r))
				if err != nil || c.Summary.Stats.Resolved != 1 {
					t.Fatal("native-vocabulary positive rejected", err)
				}
				if discard && (len(c.Pairs) != 0 || c.Discarded != 1) {
					t.Fatal("discarded positive did not reconcile", c)
				}
				for _, tc := range []struct {
					name   string
					mutate func([]Record)
				}{
					{"missing-captured", func(r []Record) { r[2].Event.HotSpotIdentity = nil }},
					{"missing-captured-epoch", func(r []Record) { r[2].Event.HotSpotIdentity.Epoch = nil }},
					{"captured-odd", func(r []Record) { *r[2].Event.HotSpotIdentity.Epoch = 1 }},
					{"captured-high", func(r []Record) { *r[2].Event.HotSpotIdentity.Epoch = 1 << 63 }},
					{"captured-max", func(r []Record) { *r[2].Event.HotSpotIdentity.Epoch = ^uint64(0) }},
					{"captured-profile", func(r []Record) { r[2].Event.HotSpotIdentity.Profile = string(Observed25) }},
					{"captured-missing-profile", func(r []Record) { r[2].Event.HotSpotIdentity.Profile = "" }},
					{"captured-error", func(r []Record) { e := int32(-13); r[2].Event.HotSpotIdentity.ReadError = &e }},
					{"captured-positive-error", func(r []Record) { e := int32(1); r[2].Event.HotSpotIdentity.ReadError = &e }},
					{"captured-unknown", func(r []Record) { r[2].Event.HotSpotIdentity.State = "unknown" }},
					{"captured-invalid-state", func(r []Record) { r[2].Event.HotSpotIdentity.State = "invalid" }},
					{"captured-cannot-be-checked", func(r []Record) { r[2].Event.HotSpotIdentity.State = "safepoint_epoch_checked" }},
					{"missing-checked", func(r []Record) { r[3].Evidence.HotSpotIdentity = nil }},
					{"missing-checked-epoch", func(r []Record) { r[3].Evidence.HotSpotIdentity.Epoch = nil }},
					{"checked-odd", func(r []Record) { *r[3].Evidence.HotSpotIdentity.Epoch = 1 }},
					{"checked-high", func(r []Record) { *r[3].Evidence.HotSpotIdentity.Epoch = 1 << 63 }},
					{"checked-profile", func(r []Record) { r[3].Evidence.HotSpotIdentity.Profile = string(Observed25) }},
					{"checked-missing-profile", func(r []Record) { r[3].Evidence.HotSpotIdentity.Profile = "" }},
					{"checked-error", func(r []Record) { e := int32(-13); r[3].Evidence.HotSpotIdentity.ReadError = &e }},
					{"checked-positive-error", func(r []Record) { e := int32(1); r[3].Evidence.HotSpotIdentity.ReadError = &e }},
					{"unchecked-terminal", func(r []Record) { r[3].Evidence.HotSpotIdentity.State = "stable" }},
					{"unknown-terminal", func(r []Record) { r[3].Evidence.HotSpotIdentity.State = "unknown" }},
					{"invalid-terminal-state", func(r []Record) { r[3].Evidence.HotSpotIdentity.State = "invalid" }},
					{"different-checked-epoch", func(r []Record) { *r[3].Evidence.HotSpotIdentity.Epoch = epoch + 2 }},
				} {
					t.Run(tc.name, func(t *testing.T) {
						x, r := strictSession(epoch)
						tc.mutate(r)
						d := NewDecoder(x)
						if discard {
							d.maxPairs = 0
						}
						_, err := consumeSession(d, encodedSession(t, r))
						if !errors.Is(err, ErrIdentity) {
							t.Fatalf("want ErrIdentity, got %v", err)
						}
						requireNoCapture(t, d, ErrIdentity)
					})
				}
				for _, path := range []string{"event.hotspot_identity", "event.hotspot_identity.epoch", "evidence.hotspot_identity", "evidence.hotspot_identity.epoch"} {
					t.Run("null-"+path, func(t *testing.T) {
						x, r := strictSession(epoch)
						encoded := encodedSession(t, r)
						row := 2
						if strings.HasPrefix(path, "evidence") {
							row = 3
						}
						encoded[row] = changeMember(t, encoded[row], strings.Split(path, "."), json.RawMessage("null"), false)
						d := NewDecoder(x)
						if discard {
							d.maxPairs = 0
						}
						_, err := consumeSession(d, encoded)
						if !errors.Is(err, ErrIdentity) {
							t.Fatal(err)
						}
						requireNoCapture(t, d, ErrIdentity)
					})
				}
			})
		}
	}
}

func TestDecoderStrictNonSuccessAndObservedUnknownIdentity(t *testing.T) {
	for _, outcome := range []string{"unavailable", "skipped"} {
		for _, state := range []string{"absent", "unknown", "read_error", "in_progress", "changed", "unsupported", "regressed", "stable"} {
			t.Run(outcome+"/"+state, func(t *testing.T) {
				x, r := strictSession(0)
				r[2].Event.HotSpotIdentity = nil
				if state != "absent" {
					r[2].Event.HotSpotIdentity = &HotSpotIdentity{State: state, Profile: string(Strict25)}
					if state == "stable" {
						r[2].Event.HotSpotIdentity.Epoch = new(uint64)
					}
				}
				r[3].Evidence = &StackEvidence{Attribution: outcome, Walk: "unknown", ScopeLookup: "unknown", Reason: "explicit_native_unavailable", Frames: []Frame{}}
				r[4].Summary.Stats.Resolved = 0
				if outcome == "unavailable" {
					r[4].Summary.Stats.Unavailable = 1
				} else {
					r[4].Summary.Stats.Skipped = 1
				}
				if c, err := consumeSession(NewDecoder(x), encodedSession(t, r)); err != nil || c.Pairs[0].Evidence.Attribution != outcome {
					t.Fatal("non-success upgraded/rejected", err)
				}
			})
		}
	}
	for _, readError := range []json.RawMessage{json.RawMessage("0"), json.RawMessage("null")} {
		x, r := strictSession(0)
		encoded := encodedSession(t, r)
		for _, row := range []int{2, 3} {
			payload := "event"
			if row == 3 {
				payload = "evidence"
			}
			encoded[row] = changeMember(t, encoded[row], []string{payload, "hotspot_identity", "read_error"}, readError, false)
		}
		if _, err := consumeSession(NewDecoder(x), encoded); err != nil {
			t.Fatal("native omitempty zero read-error semantics lost", err)
		}
	}
	x, r := syntheticSession()
	r[2].Event.HotSpotIdentity = &HotSpotIdentity{State: "unknown"}
	r[3].Evidence.HotSpotIdentity = &HotSpotIdentity{State: "unknown"}
	c, err := consumeSession(NewDecoder(x), encodedSession(t, r))
	if err != nil || c.Profile != Observed25 {
		t.Fatal("observed unknown required strict authority", err)
	}
}

func TestDecoderDiscardedStrictIdentityDoesNotRetainExtensions(t *testing.T) {
	x, r := strictSession(2)
	r[2].Event.HotSpotIdentity.Extensions = extensions{"large": json.RawMessage(`"` + strings.Repeat("x", 900000) + `"`)}
	d := NewDecoder(x)
	d.maxPairs = 0
	c, err := consumeSession(d, encodedSession(t, r))
	if err != nil || c.Discarded != 1 || len(c.Pairs) != 0 {
		t.Fatal(c, err)
	}
	if len(c.RawJSONL) > 10000 || strings.Contains(string(c.RawJSONL), "large") {
		t.Fatal("discarded extension charged to retained output")
	}
}
