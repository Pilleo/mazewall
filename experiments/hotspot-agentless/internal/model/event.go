package model

// Event is an occurrence at the selected handler entry, not an open result.
type Event struct {
	SessionID         string `json:"session_id"`
	EventID           string `json:"event_id"`
	CPU               uint32 `json:"cpu"`
	Sequence          uint64 `json:"sequence"`
	PID               uint32 `json:"host_pid"`
	TID               uint32 `json:"host_tid"`
	ProcessStartTicks uint64 `json:"process_start_ticks"`
	CgroupID          uint64 `json:"cgroup_id"`
	CaptureBootNS     uint64 `json:"capture_boot_ns"`
	ClockDomain       string `json:"clock_domain"`
	Kind              string `json:"event_kind"`
	CorrelationID     uint64 `json:"correlation_id"`
	DirFD             int32  `json:"dirfd"`
	Flags             uint64 `json:"flags"`
	Mode              uint64 `json:"mode"`
	Filename          string `json:"filename"`
	FilenameBytes     []byte `json:"filename_bytes"`
	RegisterRead      int32  `json:"register_read"`
	ArgumentRead      int32  `json:"argument_read"`
	PathTruncated     bool   `json:"path_truncated"`
}

type StackRef struct {
	SessionID         string
	PID, TID          uint32
	ProcessStartTicks uint64
	CorrelationID     uint64
	CaptureBootNS     uint64
}

func (e Event) StackRef() StackRef {
	return StackRef{SessionID: e.SessionID, PID: e.PID, TID: e.TID, ProcessStartTicks: e.ProcessStartTicks, CorrelationID: e.CorrelationID, CaptureBootNS: e.CaptureBootNS}
}
