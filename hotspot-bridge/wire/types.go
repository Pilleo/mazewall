// Package wire defines the collector's dependency-free consumer vocabulary.
package wire

type Profile string

const (
	Strict25   Profile = "temurin25_xint_serial"
	Observed25 Profile = "otel_hotspot25_observed_v1"
)

type Provider struct {
	Name             string `json:"name"`
	GadgetRevision   string `json:"gadget_revision"`
	ProfilerRevision string `json:"profiler_revision"`
	PatchSHA256      string `json:"patch_sha256"`
}

type Target struct {
	ContainerID, ImageID          string
	PID                           uint32
	StartTicks, CgroupID, MountNS uint64
}

type Expected struct {
	Target   Target
	Provider Provider
	Profile  Profile
	Schema   int
}
