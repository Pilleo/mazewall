package wire

import (
	"encoding/json"
	"errors"
	"fmt"
	"reflect"
	"strconv"
	"strings"
	"testing"
)

// Every mutation starts from this complete synthetic session, including a false
// recovery outcome, zero loss counters, unknown identities and an unknown mapping.
func presenceSession() (Expected, []Record) {
	x, r := syntheticSession()
	r[2].Event.HotSpotIdentity = &HotSpotIdentity{State: "unknown"}
	r[3].Evidence.HotSpotIdentity = &HotSpotIdentity{State: "unknown"}
	r[3].Evidence.Frames[0].Mapping = &Mapping{}
	recovery := Record{SchemaVersion: 3, Kind: "recovery", SessionID: r[0].SessionID, EventID: r[2].EventID, PolicyEligibility: new(bool), Recovery: &RecoveryResult{CorrelationID: 17, BootNS: 103, Invalidated: false}}
	r[4].Summary.Stats.RecoveryAttempts = 1
	return x, []Record{r[0], r[1], r[2], r[3], recovery, r[4]}
}

func encodedSession(t *testing.T, rows []Record) [][]byte {
	t.Helper()
	result := make([][]byte, len(rows))
	for i, r := range rows {
		var err error
		result[i], err = json.Marshal(r)
		if err != nil {
			t.Fatal(err)
		}
	}
	return result
}

func consumeSession(d *Decoder, rows [][]byte) (Capture, error) {
	for _, row := range rows {
		if err := d.Accept(row); err != nil {
			return Capture{}, err
		}
	}
	return d.Finish(nil)
}

// Change exactly one raw member, preserving integer bytes and the whole envelope.
func changeMember(t *testing.T, raw []byte, path []string, value json.RawMessage, remove bool) []byte {
	t.Helper()
	if index, err := strconv.Atoi(path[0]); err == nil {
		var a []json.RawMessage
		if err := json.Unmarshal(raw, &a); err != nil {
			t.Fatal(err)
		}
		if len(path) == 1 {
			a[index] = value
		} else {
			a[index] = changeMember(t, a[index], path[1:], value, remove)
		}
		b, err := json.Marshal(a)
		if err != nil {
			t.Fatal(err)
		}
		return b
	}
	var object map[string]json.RawMessage
	if err := json.Unmarshal(raw, &object); err != nil {
		t.Fatal(err)
	}
	if len(path) == 1 {
		if remove {
			delete(object, path[0])
		} else {
			object[path[0]] = value
		}
	} else {
		object[path[0]] = changeMember(t, object[path[0]], path[1:], value, remove)
	}
	b, err := json.Marshal(object)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func requireNoCapture(t *testing.T, d *Decoder, class error) {
	t.Helper()
	c, err := d.Finish(nil)
	if !errors.Is(err, class) || !reflect.DeepEqual(c, Capture{}) {
		t.Fatal("failed session published", c, err)
	}
}

func TestDecoderRequiredPayloadMembers(t *testing.T) {
	x, rows := presenceSession()
	encoded := encodedSession(t, rows)
	if c, err := consumeSession(NewDecoder(x), encoded); err != nil || len(c.Pairs) != 1 {
		t.Fatal("complete unmutated control rejected", err)
	}
	for _, object := range []struct {
		row                int
		path, want, fields string
	}{
		{0, "lifecycle", "lifecycle", "pid,clock_domain,boot_ns,runtime_profile,provider"},
		{1, "lifecycle.provider", "lifecycle.provider", "name,gadget_revision,profiler_revision,patch_sha256"},
		{2, "event.hotspot_identity", "event.hotspot_identity", "state"},
		{3, "evidence", "evidence", "attribution,walk,scope_lookup,provider,frames"},
		{3, "evidence.provider", "evidence.provider", "name,gadget_revision,profiler_revision,patch_sha256"},
		{3, "evidence.hotspot_identity", "evidence.hotspot_identity", "state"},
		{3, "evidence.frames.0", "evidence.frames[0]", "provider_type,frame_type,function_name,function_offset,raw_position,position_domain,error_frame"},
		{3, "evidence.frames.0.mapping", "evidence.frames[0].mapping", "file_id,filename,start,end,file_offset"},
		{4, "recovery", "recovery", "correlation_id,recovery_boot_ns,invalidated"},
		{5, "summary", "summary", "enrichment,collection_health,terminal_accounting_complete"},
		{5, "summary.enrichment", "summary.enrichment", "received,resolved,unavailable,skipped,max_pending,max_pending_payload_bytes,recovery_attempts,recovery_invalidations,recovery_errors"},
		{5, "summary.selected_event_accounting", "summary.selected_event_accounting", "schema_version,scope,session_id,host_pid,process_start_ticks,map_id,possible_cpus,attempted,submitted,reserve_failed,received,terminals,complete"},
		{5, "summary.event_finalization_observation", "summary.event_finalization_observation", "scope,phase,stop_cause,producers_quiesced,buffer_drained,receiver_joined,final_counter_read,background_loss_sampler_started,resources_retired,loss_complete"},
		{5, "summary.definition_stop_observation", "summary.definition_stop_observation", "reader_stopped,consumer_stopped,buffers_drained,auxiliary_stopped,semantics"},
		{5, "summary.event_loss_observation", "summary.event_loss_observation", "complete,semantics"},
		{5, "summary.definition_reader_observation", "summary.definition_reader_observation", "lost,no_data,read_error,buffer_bytes_per_cpu,complete,semantics"},
	} {
		for _, field := range strings.Split(object.fields, ",") {
			for _, remove := range []bool{true, false} {
				name := object.want + "." + field + map[bool]string{true: "/missing", false: "/null"}[remove]
				t.Run(name, func(t *testing.T) {
					mutated := append([][]byte(nil), encoded...)
					mutated[object.row] = changeMember(t, encoded[object.row], strings.Split(object.path+"."+field, "."), json.RawMessage("null"), remove)
					d := NewDecoder(x)
					_, err := consumeSession(d, mutated)
					want := "missing/null " + object.want + "." + field
					if !errors.Is(err, ErrJSON) || !strings.Contains(err.Error(), want) {
						t.Fatalf("want ErrJSON at %s, got %v", want, err)
					}
					requireNoCapture(t, d, ErrJSON)
				})
			}
		}
	}
}

func TestDecoderOptionalLocationsAndExplicitOutcomes(t *testing.T) {
	for _, nulls := range []bool{false, true} {
		t.Run(fmt.Sprint("nullable-locations-", nulls), func(t *testing.T) {
			x, r := presenceSession()
			encoded := encodedSession(t, r)
			for _, field := range []string{"source_file", "source_line", "source_column", "native_address", "bci", "raw_bci", "scope_lookup", "scope_walk", "bci_state", "quality_diagnostic"} {
				encoded[3] = changeMember(t, encoded[3], []string{"evidence", "frames", "0", field}, json.RawMessage("null"), !nulls)
			}
			c, err := consumeSession(NewDecoder(x), encoded)
			if err != nil || len(c.Pairs) != 1 {
				t.Fatal(err)
			}
			if _, err := CanonicalStackID(Scope{}, []Frame{{}}); err != nil {
				t.Fatal("typed zero frame is not a missing raw field", err)
			}
		})
	}
	for _, outcome := range []string{"unavailable", "skipped"} {
		t.Run(outcome, func(t *testing.T) {
			x, r := presenceSession()
			r[3].Evidence = &StackEvidence{Attribution: outcome, Walk: "unknown", ScopeLookup: "unknown", Frames: []Frame{}}
			r[5].Summary.Stats.Resolved = 0
			if outcome == "unavailable" {
				r[5].Summary.Stats.Unavailable = 1
			} else {
				r[5].Summary.Stats.Skipped = 1
			}
			encoded := encodedSession(t, r)
			if _, err := consumeSession(NewDecoder(x), encoded); err != nil {
				t.Fatal("explicit empty/false/zero rejected", err)
			}
			for _, remove := range []bool{false, true} {
				d := NewDecoder(x)
				mutated := append([][]byte(nil), encoded...)
				mutated[3] = changeMember(t, encoded[3], []string{"evidence", "frames"}, json.RawMessage("null"), remove)
				_, err := consumeSession(d, mutated)
				if !errors.Is(err, ErrJSON) || !strings.Contains(err.Error(), "evidence.frames") {
					t.Fatal("missing unavailable frames admitted", err)
				}
				requireNoCapture(t, d, ErrJSON)
			}
		})
	}
}

func TestStandaloneDTOsRemainPermissive(t *testing.T) {
	var r Record
	if err := json.Unmarshal([]byte(`{"evidence":{"walk":null,"frames":[{"error_frame":null}]},"recovery":{"invalidated":null}}`), &r); err != nil {
		t.Fatal("raw admission leaked into DTO compatibility", err)
	}
	if r.Evidence.Frames[0].ErrorFrame || r.Recovery.Invalidated {
		t.Fatal("standalone zero values changed")
	}
}

func TestDecoderNullableObjectsAndFrameObjectBoundary(t *testing.T) {
	x, r := presenceSession()
	encoded := encodedSession(t, r)
	if _, err := consumeSession(NewDecoder(x), encoded); err != nil {
		t.Fatal(err)
	}
	for _, path := range [][]string{{"evidence", "frames", "0", "mapping"}, {"summary", "definition_reader_observation"}} {
		for _, remove := range []bool{false, true} {
			rows := append([][]byte(nil), encoded...)
			row := 3
			if path[0] == "summary" {
				row = 5
			}
			rows[row] = changeMember(t, rows[row], path, json.RawMessage("null"), remove)
			if _, err := consumeSession(NewDecoder(x), rows); err != nil {
				t.Fatal("optional object became mandatory", path, err)
			}
		}
	}
	rows := append([][]byte(nil), encoded...)
	rows[3] = changeMember(t, rows[3], []string{"evidence", "frames", "0"}, json.RawMessage("null"), false)
	d := NewDecoder(x)
	_, err := consumeSession(d, rows)
	if !errors.Is(err, ErrJSON) || !strings.Contains(err.Error(), "evidence.frames[0]") {
		t.Fatal("null frame object lost path", err)
	}
	requireNoCapture(t, d, ErrJSON)
}
