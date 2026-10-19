package wire

import (
	"bytes"
	"encoding/json"
	"errors"
	"reflect"
	"strings"
	"syscall"
	"testing"
)

func TestCollectorFailureRetainsCompoundPermissionCauses(t *testing.T) {
	for _, causes := range [][]int{{5}, {5, 13}, {5, 1}, {5, 1, 13}, {5, 13, 1}, {13, 1}, {1, 13}, {4095, 13}} {
		t.Run(string(mustJSON(t, causes)), func(t *testing.T) {
			x, rows := syntheticSession()
			failure := rows[1]
			life := *failure.Lifecycle
			failure.Kind, failure.Lifecycle = "collector_error", &life
			life.Errno, life.ErrnoCauses = causes[0], causes
			life.Message = "original compound native failure"
			d := NewDecoder(x)
			err := acceptRecords(d, []Record{rows[0], failure})
			if !errors.Is(err, ErrCollector) || !strings.Contains(err.Error(), life.Message) {
				t.Fatal("collector failure lost", err)
			}
			for _, cause := range causes {
				if !errors.Is(err, syscall.Errno(cause)) {
					t.Fatal("compound native causes were flattened", cause, err)
				}
			}
			waitErr := errors.New("original Wait failure")
			capture, err := d.Finish(waitErr)
			if !errors.Is(err, waitErr) || !errors.Is(err, ErrProcess) || !reflect.DeepEqual(capture, Capture{}) {
				t.Fatal("failed capture published or Wait error lost", capture, err)
			}
			for _, cause := range causes {
				if !errors.Is(err, syscall.Errno(cause)) {
					t.Fatal("Finish lost native cause", cause, err)
				}
			}
		})
	}
}

func mustJSON(t *testing.T, v any) []byte {
	t.Helper()
	b, err := json.Marshal(v)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestCollectorFailureRejectsInvalidErrnoCauses(t *testing.T) {
	for _, raw := range []string{"null", "[]", "{}", "5", "\"5\"", "[null]", "[5,null]", "[5,1,13,2]", "[5,5]", "[5,13,13]", "[0]", "[-1]", "[4096]", "[5,4096]", "[5,0]", "[5,-1]", "[5,2]", "[13,5]", "[13]", "[5.0]", "[5,1.5]", "[\"5\"]", "[true]"} {
		t.Run(raw, func(t *testing.T) {
			x, rows := syntheticSession()
			failure := rows[1]
			failure.Kind = "collector_error"
			failure.Lifecycle.Errno = 5
			b := mustJSON(t, failure)
			b = bytes.Replace(b, []byte(`"errno":5`), []byte(`"errno":5,"errno_causes":`+raw), 1)
			d := NewDecoder(x)
			if err := acceptRecords(d, rows[:1]); err != nil {
				t.Fatal(err)
			}
			if err := d.Accept(b); err == nil || errors.Is(err, ErrCollector) || errors.Is(err, syscall.EIO) || errors.Is(err, syscall.EACCES) {
				t.Fatal("invalid causes became trusted collector errors", err)
			}
			if capture, err := d.Finish(nil); err == nil || !reflect.DeepEqual(capture, Capture{}) {
				t.Fatal("invalid causes published a capture", capture, err)
			}
		})
	}
}

func TestErrnoCausesRequireSchema3CollectorFailure(t *testing.T) {
	for _, kind := range []string{"session_started", "collector_ready", "collector_error"} {
		for _, schema := range []int{2, 3} {
			if kind == "collector_error" && schema == 3 {
				continue
			}
			x, rows := syntheticSession()
			if schema == 2 {
				x.Schema, x.Profile = 2, Strict25
				for _, r := range rows[:2] {
					r.Lifecycle.RuntimeProfile = string(Strict25)
					r.Lifecycle.CgroupID, r.Lifecycle.MountNS, r.Lifecycle.ValidationMode = 0, 0, ""
				}
				for i := range rows[:2] {
					rows[i].SchemaVersion, rows[i].PolicyEligibility = 2, nil
				}
			}
			r := rows[1]
			r.Kind = kind
			if kind != "collector_ready" {
				r.Lifecycle.Errno = 5
			}
			for _, raw := range []string{"[5,13]", "[5]", "null", "[]"} {
				d := NewDecoder(x)
				if kind != "session_started" {
					if err := acceptRecords(d, rows[:1]); err != nil {
						t.Fatal(err)
					}
				}
				b := mustJSON(t, r)
				b = bytes.Replace(b, []byte(`"pid":`), []byte(`"errno_causes":`+raw+`,"pid":`), 1)
				if err := d.Accept(b); !errors.Is(err, ErrJSON) || errors.Is(err, ErrCollector) {
					t.Fatal("causes accepted outside schema3 collector_error", schema, kind, raw, err)
				}
			}
		}
	}
}

func TestCollectorFailureBindsIdentityBeforeJoiningCauses(t *testing.T) {
	for _, mutate := range []func(*Record){
		func(r *Record) { r.SessionID = "foreign" },
		func(r *Record) { r.Lifecycle.PID++ },
		func(r *Record) { r.Lifecycle.ProcessStartTicks++ },
		func(r *Record) { r.Lifecycle.CgroupID++ },
		func(r *Record) { r.Lifecycle.MountNS++ },
		func(r *Record) { r.Lifecycle.Provider.Name = "foreign" },
	} {
		x, rows := syntheticSession()
		r := rows[1]
		r.Kind, r.Lifecycle.Errno, r.Lifecycle.ErrnoCauses = "collector_error", 5, []int{5, 13}
		mutate(&r)
		d := NewDecoder(x)
		if err := acceptRecords(d, []Record{rows[0], r}); !errors.Is(err, ErrIdentity) || errors.Is(err, syscall.EIO) || errors.Is(err, syscall.EACCES) {
			t.Fatal("unbound errno trusted", err)
		}
	}
}

func TestLifecycleOwnsErrnoCausesAndRecognizesKnownField(t *testing.T) {
	original := Lifecycle{Errno: 5, ErrnoCauses: []int{5, 13}}
	copy := cloneLifecycle(original)
	copy.ErrnoCauses[1] = 1
	if original.ErrnoCauses[1] != 13 {
		t.Fatal("lifecycle copy aliases causes")
	}
	var decoded Lifecycle
	if err := json.Unmarshal([]byte(`{"errno":5,"errno_causes":[5,13],"future":true}`), &decoded); err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(decoded.ErrnoCauses, []int{5, 13}) || len(decoded.Extensions) != 1 || string(decoded.Extensions["future"]) != "true" {
		t.Fatal("known causes duplicated in extensions", decoded)
	}
	if b := mustJSON(t, decoded); bytes.Count(b, []byte(`"errno_causes"`)) != 1 {
		t.Fatal("causes not emitted exactly once", string(b))
	}
}

func TestCollectorFailureDoesNotParseMessageForCauses(t *testing.T) {
	x, rows := syntheticSession()
	r := rows[1]
	r.Kind, r.Lifecycle.Errno = "collector_error", 5
	r.Lifecycle.Message = "permission denied EPERM EACCES errno13"
	err := acceptRecords(NewDecoder(x), []Record{rows[0], r})
	if !errors.Is(err, syscall.EIO) || errors.Is(err, syscall.EPERM) || errors.Is(err, syscall.EACCES) {
		t.Fatal("message supplied a typed cause", err)
	}
}

func TestCollectorFailureRequiresMatchingNonzeroPrimary(t *testing.T) {
	for _, primary := range []int{0, -1, 4096} {
		x, rows := syntheticSession()
		r := rows[1]
		r.Kind, r.Lifecycle.Errno, r.Lifecycle.ErrnoCauses = "collector_error", primary, []int{primary}
		if err := NewDecoder(x).Accept(mustJSON(t, r)); !errors.Is(err, ErrJSON) {
			t.Fatal("invalid primary accepted with matching cause", primary, err)
		}
	}
}

func TestSingleFailureEncodingRemainsUnchanged(t *testing.T) {
	for _, schema := range []int{2, 3} {
		r := Record{SchemaVersion: schema, Kind: "collector_error", SessionID: "failure", Lifecycle: &Lifecycle{PID: 123, ClockDomain: "CLOCK_BOOTTIME", BootNS: 100, RuntimeProfile: string(Strict25), Message: "permission denied", Errno: 13}}
		want := `{"schema_version":2,"kind":"collector_error","session_id":"failure","lifecycle":{"pid":123,"clock_domain":"CLOCK_BOOTTIME","boot_ns":100,"runtime_profile":"temurin25_xint_serial","provider":{"name":"","gadget_revision":"","profiler_revision":"","patch_sha256":""},"message":"permission denied","errno":13}}`
		if schema == 3 {
			r.PolicyEligibility = new(bool)
			r.Lifecycle.RuntimeProfile = string(Observed25)
			want = `{"schema_version":3,"kind":"collector_error","session_id":"failure","lifecycle":{"pid":123,"clock_domain":"CLOCK_BOOTTIME","boot_ns":100,"runtime_profile":"otel_hotspot25_observed_v1","provider":{"name":"","gadget_revision":"","profiler_revision":"","patch_sha256":""},"message":"permission denied","errno":13},"policy_eligibility":false}`
		}
		if got := string(mustJSON(t, r)); got != want {
			t.Fatal("ordinary failure bytes changed", got)
		}
	}
}
