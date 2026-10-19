// Package bundle verifies local collector assets without invoking them.
package bundle

type Manifest struct {
	SchemaVersion    int               `json:"schema_version"`
	CollectorVersion string            `json:"collector_version"`
	CaptureABI       int               `json:"capture_abi"`
	WireSchema       int               `json:"wire_schema"`
	Platform         string            `json:"platform"`
	GadgetRevision   string            `json:"gadget_revision"`
	ProfilerRevision string            `json:"profiler_revision"`
	PatchSHA256      string            `json:"patch_sha256"`
	GadgetDigest     string            `json:"gadget_digest"`
	Files            map[string]string `json:"files"`
}
