package accounting

import (
	"reflect"
	"testing"
)

func TestFinalReaderDoesNotProveMissingDefinitionAdmission(t *testing.T) {
	b := Binding{Session: "s", PID: 123, Birth: 456, MapID: 7, PossibleCPUs: 1}
	lanes := map[Lane]LaneEvidence{}
	for _, lane := range []Lane{SelectedEvents, Enrichment, Wire, Retention} {
		lanes[lane] = LaneEvidence{Binding: b, Final: true, Counts: Counts{Known: true, Quiesced: true, Joined: true}}
	}
	if got := MissingEvidence(b, lanes); !reflect.DeepEqual(got, []Lane{Definitions}) {
		t.Fatal(got)
	}
	lanes[Definitions] = LaneEvidence{Binding: b, Final: false}
	if got := MissingEvidence(b, lanes); !reflect.DeepEqual(got, []Lane{Definitions}) {
		t.Fatal("non-final definitions became complete", got)
	}
}

func TestLaneEvidenceRequiresValidBindingFinalityAndEquations(t *testing.T) {
	b := Binding{Session: "s", PID: 123, Birth: 456, MapID: 7, PossibleCPUs: 1}
	required := []Lane{SelectedEvents, Definitions, Enrichment, Wire, Retention}
	valid := LaneEvidence{Binding: b, Final: true, Counts: Counts{Known: true, Quiesced: true, Joined: true, Attempted: 3, Submitted: 2, ReserveFailed: 1, Received: 2, Terminals: 2}}
	for _, kind := range []string{"valid", "absent", "session", "pid", "birth", "map", "cpus", "final", "known", "quiesced", "joined", "submitted", "reserve", "received", "terminals"} {
		t.Run(kind, func(t *testing.T) {
			for _, lane := range required {
				lanes := map[Lane]LaneEvidence{}
				for _, name := range required {
					lanes[name] = valid
				}
				v := valid
				switch kind {
				case "session":
					v.Binding.Session = "foreign"
				case "pid":
					v.Binding.PID++
				case "birth":
					v.Binding.Birth++
				case "map":
					v.Binding.MapID++
				case "cpus":
					v.Binding.PossibleCPUs++
				case "final":
					v.Final = false
				case "known":
					v.Counts.Known = false
				case "quiesced":
					v.Counts.Quiesced = false
				case "joined":
					v.Counts.Joined = false
				case "submitted":
					v.Counts.Submitted = 4
				case "reserve":
					v.Counts.ReserveFailed = 0
				case "received":
					v.Counts.Received = 3
				case "terminals":
					v.Counts.Terminals = 1
				}
				lanes[lane] = v
				if kind == "absent" {
					delete(lanes, lane)
				}
				got := MissingEvidence(b, lanes)
				if kind == "valid" {
					if len(got) != 0 {
						t.Fatal(got)
					}
				} else if !reflect.DeepEqual(got, []Lane{lane}) {
					t.Fatal(lane, got)
				}
			}
		})
	}
	for _, invalid := range []Binding{{}, {PID: 123, Birth: 456, MapID: 7, PossibleCPUs: 1}, {Session: "s", Birth: 456, MapID: 7, PossibleCPUs: 1}, {Session: "s", PID: 123, MapID: 7, PossibleCPUs: 1}, {Session: "s", PID: 123, Birth: 456, PossibleCPUs: 1}, {Session: "s", PID: 123, Birth: 456, MapID: 7}} {
		lanes := map[Lane]LaneEvidence{}
		for _, lane := range required {
			lanes[lane] = LaneEvidence{Binding: invalid, Final: true, Counts: valid.Counts}
		}
		if got := MissingEvidence(invalid, lanes); !reflect.DeepEqual(got, required) {
			t.Fatal("invalid expected identity certified", invalid, got)
		}
	}
	if got := MissingEvidence(b, nil); !reflect.DeepEqual(got, required) {
		t.Fatal(got)
	}
}

func TestUnrecognizedLanesAreDeterministicAndNeverComplete(t *testing.T) {
	b := Binding{Session: "s", PID: 123, Birth: 456, MapID: 7, PossibleCPUs: 1}
	lanes := map[Lane]LaneEvidence{}
	for _, lane := range []Lane{SelectedEvents, Definitions, Enrichment, Wire, Retention, "z", "", "a"} {
		lanes[lane] = LaneEvidence{Binding: b, Final: true, Counts: Counts{Known: true, Quiesced: true, Joined: true}}
	}
	want := []Lane{"unrecognized:", "unrecognized:a", "unrecognized:z"}
	for n := 0; n < 20; n++ {
		if got := MissingEvidence(b, lanes); !reflect.DeepEqual(got, want) {
			t.Fatal(got)
		}
	}
}
