package preflight

import (
	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"strings"
)

// Snapshot records observations, not promises that attaching an event probe works.
type Snapshot struct {
	OS                     string            `json:"os"`
	Arch                   string            `json:"architecture"`
	Kernel                 string            `json:"kernel"`
	PID                    int               `json:"target_pid"`
	TargetUID              uint32            `json:"target_uid"`
	TargetStartTicks       uint64            `json:"target_start_ticks"`
	TargetMetadataReadable bool              `json:"target_metadata_readable"`
	BTFReadable            bool              `json:"btf_readable"`
	Probe                  string            `json:"probe"`
	ProbeAvailable         bool              `json:"probe_available"`
	WrapperABI             bool              `json:"wrapper_abi_verified"`
	BPFPermission          bool              `json:"bpf_program_load_verified"`
	Details                map[string]string `json:"details,omitempty"`
	NativeError            error             `json:"-"`
}

type Report struct {
	SchemaVersion int      `json:"schema_version"`
	Ready         bool     `json:"ready_for_attach_test"`
	Snapshot      Snapshot `json:"environment"`
	Failures      []string `json:"failures"`
	NativeError   error    `json:"-"`
}

func Check(s Snapshot) Report {
	r := Report{SchemaVersion: 1, Snapshot: s, Failures: []string{}, NativeError: s.NativeError}
	checks := []struct {
		ok   bool
		code string
	}{
		{s.OS == "linux", "unsupported_os"},
		{s.Arch == "amd64", "unsupported_architecture"},
		{s.BTFReadable, "btf_unreadable"},
		{s.PID > 0, "invalid_target"},
		{s.TargetStartTicks != 0, "target_unavailable"},
		{s.TargetMetadataReadable, "target_metadata_unreadable"},
		{s.ProbeAvailable, "probe_unavailable"},
		{s.WrapperABI, "unsupported_probe_abi"},
		{s.BPFPermission, "bpf_permission_denied"},
		{s.TargetUID != 0, "privileged_target"},
	}
	for _, c := range checks {
		if !c.ok {
			r.Failures = append(r.Failures, c.code)
		}
	}
	r.Ready = len(r.Failures) == 0
	return r
}

func (r Report) HasFailure(code string) bool {
	for _, f := range r.Failures {
		if f == code {
			return true
		}
	}
	return false
}

func ParseStartTicks(stat string) (uint64, error) {
	return wire.ParseStartTicks(stat)
}

func ProbeListed(functions, probe string) bool {
	for line := range strings.SplitSeq(functions, "\n") {
		fields := strings.Fields(line)
		if len(fields) > 0 && fields[0] == probe {
			return true
		}
	}
	return false
}
