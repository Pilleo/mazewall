package preflight

import (
	"errors"
	"io/fs"
	"testing"
)

// These cases catch acceptance of an unsupported host or an unreadable target.
func TestCheckRefusesUnsupportedEnvironment(t *testing.T) {
	valid := Snapshot{
		OS: "linux", Arch: "amd64", Kernel: "7.0.0-test", PID: 123,
		BTFReadable: true, Probe: "__x64_sys_openat", ProbeAvailable: true,
		WrapperABI: true, BPFPermission: true, TargetStartTicks: 987,
		TargetUID: 1000, TargetMetadataReadable: true,
	}
	cases := []struct {
		name   string
		change func(*Snapshot)
		want   string
	}{
		{"wrong-os", func(s *Snapshot) { s.OS = "darwin" }, "unsupported_os"},
		{"wrong-architecture", func(s *Snapshot) { s.Arch = "arm64" }, "unsupported_architecture"},
		{"missing-btf", func(s *Snapshot) { s.BTFReadable = false }, "btf_unreadable"},
		{"invalid-target", func(s *Snapshot) { s.PID = 0 }, "invalid_target"},
		{"gone-target", func(s *Snapshot) { s.TargetStartTicks = 0 }, "target_unavailable"},
		{"unreadable-metadata", func(s *Snapshot) { s.TargetMetadataReadable = false }, "target_metadata_unreadable"},
		{"missing-probe", func(s *Snapshot) { s.ProbeAvailable = false }, "probe_unavailable"},
		{"wrong-probe-abi", func(s *Snapshot) { s.WrapperABI = false }, "unsupported_probe_abi"},
		{"missing-permission", func(s *Snapshot) { s.BPFPermission = false }, "bpf_permission_denied"},
		{"root-java", func(s *Snapshot) { s.TargetUID = 0 }, "privileged_target"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			s := valid
			tc.change(&s)
			r := Check(s)
			if r.Ready || !r.HasFailure(tc.want) {
				t.Fatalf("expected refusal %q, got %+v", tc.want, r)
			}
		})
	}
	if r := Check(valid); !r.Ready || len(r.Failures) != 0 {
		t.Fatalf("supported snapshot refused: %+v", r)
	}
}

func TestTargetStartTicksHandlesSpacesAndParentheses(t *testing.T) {
	// Field 22 is starttime; the task comm can itself contain parentheses.
	stat := "123 (fixture (worker)) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 987 0 0"
	got, err := ParseStartTicks(stat)
	if err != nil || got != 987 {
		t.Fatalf("starttime=%d, err=%v", got, err)
	}
	for _, malformed := range []string{"", "123 (truncated) S 1", "123 (bad) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 nope"} {
		if _, err := ParseStartTicks(malformed); err == nil {
			t.Fatalf("accepted malformed proc stat %q", malformed)
		}
	}
}

func TestProbeMatchingDoesNotAcceptPrefix(t *testing.T) {
	if ProbeListed("__x64_sys_openat2\n__x64_sys_openat.constprop.0\n", "__x64_sys_openat") {
		t.Fatal("accepted a different probe")
	}
	if !ProbeListed("__x64_sys_openat [kernel]\n", "__x64_sys_openat") {
		t.Fatal("failed to match exact probe")
	}
}

func TestPermissionFailureRetainsNativeCause(t *testing.T) {
	s := Snapshot{NativeError: fs.ErrPermission}
	r := Check(s)
	if !errors.Is(r.NativeError, fs.ErrPermission) {
		t.Fatal("native permission error discarded")
	}
}
