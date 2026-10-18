package wire

import "encoding/json"

// Extensions owns the original JSON values of fields unknown to this version.
// It is deliberately not a map[string]any: integer and raw-value fidelity matter.
type extensions = map[string]json.RawMessage

type Record struct {
	SchemaVersion     int             `json:"schema_version"`
	Kind              string          `json:"kind"`
	SessionID         string          `json:"session_id"`
	EventID           string          `json:"event_id,omitempty"`
	Event             *Event          `json:"event,omitempty"`
	Evidence          *StackEvidence  `json:"evidence,omitempty"`
	Summary           *Summary        `json:"summary,omitempty"`
	Recovery          *RecoveryResult `json:"recovery,omitempty"`
	Lifecycle         *Lifecycle      `json:"lifecycle,omitempty"`
	PolicyEligibility *bool           `json:"policy_eligibility,omitempty"`
	Extensions        extensions      `json:"-"`
}

type Lifecycle struct {
	PID               uint32     `json:"pid"`
	ProcessStartTicks uint64     `json:"process_start_ticks,omitempty"`
	ClockDomain       string     `json:"clock_domain"`
	BootNS            uint64     `json:"boot_ns"`
	RuntimeProfile    string     `json:"runtime_profile"`
	JVMBuildID        string     `json:"jvm_build_id,omitempty"`
	JVMSHA256         string     `json:"jvm_sha256,omitempty"`
	Provider          Provider   `json:"provider"`
	Message           string     `json:"message,omitempty"`
	Errno             int        `json:"errno,omitempty"`
	ValidationMode    string     `json:"validation_mode,omitempty"`
	CgroupID          uint64     `json:"cgroup_id,omitempty"`
	MountNS           uint64     `json:"mount_namespace,omitempty"`
	Extensions        extensions `json:"-"`
}

type RecoveryResult struct {
	CorrelationID uint64     `json:"correlation_id"`
	BootNS        uint64     `json:"recovery_boot_ns"`
	Invalidated   bool       `json:"invalidated"`
	Error         string     `json:"error,omitempty"`
	Extensions    extensions `json:"-"`
}

type Stats struct {
	Received              uint64     `json:"received"`
	Resolved              uint64     `json:"resolved"`
	Unavailable           uint64     `json:"unavailable"`
	Skipped               uint64     `json:"skipped"`
	MaxPending            int        `json:"max_pending"`
	MaxPendingBytes       int        `json:"max_pending_payload_bytes"`
	RecoveryAttempts      uint64     `json:"recovery_attempts"`
	RecoveryInvalidations uint64     `json:"recovery_invalidations"`
	RecoveryErrors        uint64     `json:"recovery_errors"`
	Extensions            extensions `json:"-"`
}

type Lane string

const (
	SelectedEvents Lane = "selected_events"
	Definitions    Lane = "definitions"
	Enrichment     Lane = "enrichment"
	Wire           Lane = "wire"
	Retention      Lane = "retention"
)

type Summary struct {
	UnprovenCollectionSources   []Lane                        `json:"unproven_collection_sources,omitempty"`
	SelectedAccounting          *SelectedAccountingDiagnostic `json:"selected_event_accounting,omitempty"`
	EventFinalization           *EventFinalizationObservation `json:"event_finalization_observation,omitempty"`
	DefinitionStop              *DefinitionStopObservation    `json:"definition_stop_observation,omitempty"`
	Stats                       Stats                         `json:"enrichment"`
	EventLoss                   *uint64                       `json:"event_loss,omitempty"`
	EventLossObservation        *LossObservation              `json:"event_loss_observation,omitempty"`
	DefinitionLoss              *uint64                       `json:"definition_loss,omitempty"`
	DefinitionReaderObservation *ReaderObservation            `json:"definition_reader_observation,omitempty"`
	CollectionHealth            string                        `json:"collection_health"`
	TerminalAccountingComplete  bool                          `json:"terminal_accounting_complete"`
	Errors                      []string                      `json:"errors,omitempty"`
	Extensions                  extensions                    `json:"-"`
}

type SelectedAccountingDiagnostic struct {
	SchemaVersion uint32     `json:"schema_version"`
	Scope         string     `json:"scope"`
	SessionID     string     `json:"session_id"`
	PID           uint32     `json:"host_pid"`
	Birth         uint64     `json:"process_start_ticks"`
	MapID         uint32     `json:"map_id"`
	PossibleCPUs  int        `json:"possible_cpus"`
	Attempted     uint64     `json:"attempted"`
	Submitted     uint64     `json:"submitted"`
	ReserveFailed uint64     `json:"reserve_failed"`
	Received      uint64     `json:"received"`
	Terminals     uint64     `json:"terminals"`
	Complete      bool       `json:"complete"`
	Reason        string     `json:"reason,omitempty"`
	Extensions    extensions `json:"-"`
}

type EventFinalizationObservation struct {
	Scope                        string     `json:"scope"`
	Phase                        string     `json:"phase"`
	StopCause                    string     `json:"stop_cause"`
	ProducersQuiesced            bool       `json:"producers_quiesced"`
	BufferDrained                bool       `json:"buffer_drained"`
	ReceiverJoined               bool       `json:"receiver_joined"`
	FinalCounterRead             bool       `json:"final_counter_read"`
	NativeReserveFailures        *uint64    `json:"native_reserve_failures,omitempty"`
	BackgroundLossSamplerStarted bool       `json:"background_loss_sampler_started"`
	ResourcesRetired             bool       `json:"resources_retired"`
	LossComplete                 bool       `json:"loss_complete"`
	Error                        string     `json:"error,omitempty"`
	Extensions                   extensions `json:"-"`
}

type DefinitionStopObservation struct {
	ReaderStopped    bool       `json:"reader_stopped"`
	ConsumerStopped  bool       `json:"consumer_stopped"`
	BuffersDrained   bool       `json:"buffers_drained"`
	AuxiliaryStopped bool       `json:"auxiliary_stopped"`
	Semantics        string     `json:"semantics"`
	Extensions       extensions `json:"-"`
}

type LossObservation struct {
	Observed   *uint64    `json:"observed,omitempty"`
	Complete   bool       `json:"complete"`
	Semantics  string     `json:"semantics"`
	Extensions extensions `json:"-"`
}

type ReaderObservation struct {
	Lost              uint64     `json:"lost"`
	NoData            uint64     `json:"no_data"`
	ReadError         uint64     `json:"read_error"`
	BufferBytesPerCPU int        `json:"buffer_bytes_per_cpu"`
	Complete          bool       `json:"complete"`
	Semantics         string     `json:"semantics"`
	Extensions        extensions `json:"-"`
}

type Pair struct {
	Event    Event
	Evidence StackEvidence
}

type Capture struct {
	SessionID             string
	Target                Target
	Profile               Profile
	JVMBuildID, JVMSHA256 string
	Provider              Provider
	Pairs                 []Pair
	Summary               Summary
	Discarded             uint64
	RawSHA256             string
	// RawJSONL retains bounded original rows, including unknown outer fields.
	// Discarded pair/recovery rows may be absent; RawSHA256 covers every input row.
	RawJSONL []byte
}
