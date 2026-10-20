package candidate

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"unicode/utf8"

	"io.mazewall/experiments/hotspot-agentless/internal/driver"
	"io.mazewall/experiments/hotspot-agentless/internal/qualification/jitgc"
)

func writeCaptureAudit(assets, output string) error {
	file, err := os.Open(filepath.Join(assets, "manifest.json"))
	if err != nil {
		return err
	}
	raw, readErr := io.ReadAll(io.LimitReader(file, 65537))
	if err := errors.Join(readErr, file.Close()); err != nil {
		return err
	}
	if len(raw) > 65536 || !utf8.Valid(raw) {
		return fmt.Errorf("invalid audit manifest bytes")
	}
	var manifest struct {
		CaptureABI       uint32 `json:"capture_abi"`
		ProfilerRevision string `json:"profiler_revision"`
		PatchSHA256      string `json:"patch_sha256"`
	}
	if err := json.Unmarshal(raw, &manifest); err != nil {
		return err
	}
	audit, err := jitgc.AuditCapture(manifest.CaptureABI, manifest.ProfilerRevision, manifest.PatchSHA256)
	if err != nil {
		return err
	}
	return driver.WriteJSON(filepath.Join(output, "capture-audit.json"), audit)
}
