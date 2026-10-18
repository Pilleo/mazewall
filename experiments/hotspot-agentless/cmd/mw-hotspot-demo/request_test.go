package main

import (
	"strings"
	"testing"

	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
)

func observedArgs() []string {
	return []string{"--capture", "--protocol-version=3", "--runtime-profile=otel_hotspot25_observed_v1", "--pid=123", "--expected-start-ticks=456", "--expected-cgroup-id=789", "--expected-mntns=42", "--hotspot-identity=unknown", "--drain-full", "--control-stdin", "--duration=15s"}
}

func TestParseObservedRequestDoesNotAttach(t *testing.T) {
	r, err := parseCaptureRequest(observedArgs())
	if err != nil || r.Protocol != 3 || r.Profile != wire.Observed25 || r.Target != (wire.Target{PID: 123, StartTicks: 456, CgroupID: 789, MountNS: 42}) || !r.Control || !r.DrainFull || r.StrictEpoch {
		t.Fatal("valid ordinary-JVM request rejected before admission", r, err)
	}
}

func TestObservationNegotiationRefusesSpecificMismatch(t *testing.T) {
	for _, tc := range []struct{ from, to, want string }{
		{"--protocol-version=3", "--protocol-version=2", "profile/schema"},
		{"--runtime-profile=otel_hotspot25_observed_v1", "--runtime-profile=temurin25_xint_serial", "profile/schema"},
		{"--runtime-profile=otel_hotspot25_observed_v1", "--runtime-profile=typo", "runtime profile"},
		{"--hotspot-identity=unknown", "--hotspot-identity=safepoint-epoch", "observed negotiation"},
		{"--hotspot-identity=unknown", "--hotspot-identity=typo", "identity mode"},
		{"--drain-full", "--drain-full=false", "observed negotiation"},
		{"--control-stdin", "--control-stdin=false", "observed negotiation"},
		{"--expected-start-ticks=456", "--expected-start-ticks=0", "expected target"},
		{"--expected-cgroup-id=789", "--expected-cgroup-id=0", "expected target"},
		{"--expected-mntns=42", "--expected-mntns=0", "expected target"},
	} {
		t.Run(tc.to, func(t *testing.T) {
			args := observedArgs()
			for i, arg := range args {
				if arg == tc.from {
					args[i] = tc.to
				}
			}
			if _, err := parseCaptureRequest(args); err == nil || !strings.Contains(err.Error(), tc.want) {
				t.Fatalf("want %q, got %v", tc.want, err)
			}
		})
	}
}

func TestObservedShippedDispatchRejectsBeforeAcquisition(t *testing.T) {
	// A syntactically valid nonexistent PID with no assets must fail usage/admission,
	// before preflight, signal/control owners, or any target/resource acquisition.
	if code := run(observedArgs()); code != 2 {
		t.Fatalf("unqualified observed dispatch: %d", code)
	}
}

func TestStrictAndExperimentalNegotiationRemainSeparate(t *testing.T) {
	for _, args := range [][]string{
		{"--capture", "--protocol-version=2", "--pid=123", "--hotspot-identity=safepoint-epoch", "--drain-full"},
		{"--capture", "--pid=123"},
		{"--preflight"},
	} {
		if _, err := parseCaptureRequest(args); err != nil {
			t.Fatal(args, err)
		}
	}
}
