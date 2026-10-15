package tooling

import (
	"bytes"
	"debug/buildinfo"
	"debug/elf"
	"fmt"
	"os"
	"strings"
)

type footprintReport struct {
	BaselineBytes   int64  `json:"baseline_bytes"`
	CandidateBytes  int64  `json:"candidate_bytes"`
	CandidateSHA256 string `json:"candidate_sha256"`
	BuildInfo       string `json:"build_info"`
}

func verifyReleaseFootprint(baseline, candidate, identity string) (footprintReport, error) {
	var report footprintReport
	if !shaPattern.MatchString(identity) {
		return report, fmt.Errorf("invalid verified preparation identity")
	}
	base, err := os.Stat(baseline)
	if err != nil {
		return report, err
	}
	selected, err := os.Stat(candidate)
	if err != nil {
		return report, err
	}
	if selected.Size() >= base.Size() {
		return report, fmt.Errorf("candidate has no collector byte reduction")
	}
	binary, err := elf.Open(candidate)
	if err != nil {
		return report, err
	}
	defer binary.Close()
	for _, section := range binary.Sections {
		if section.Name == ".symtab" || strings.HasPrefix(section.Name, ".debug_") || strings.HasPrefix(section.Name, ".zdebug_") {
			return report, fmt.Errorf("release retains debug/symbol section: %s", section.Name)
		}
	}
	for _, segment := range binary.Progs {
		if segment.Type == elf.PT_INTERP {
			return report, fmt.Errorf("collector is no longer static")
		}
	}
	info, err := buildinfo.ReadFile(candidate)
	if err != nil {
		return report, err
	}
	if info.Path != "io.mazewall/experiments/hotspot-agentless/cmd/mw-hotspot-collector" {
		return report, fmt.Errorf("unexpected collector build path: %s", info.Path)
	}
	settings := map[string]string{}
	for _, setting := range info.Settings {
		settings[setting.Key] = setting.Value
	}
	for key, expected := range map[string]string{"CGO_ENABLED": "0", "GOARCH": "amd64", "GOOS": "linux", "-trimpath": "true"} {
		if settings[key] != expected {
			return report, fmt.Errorf("missing release provenance: %s=%s", key, expected)
		}
	}
	data, err := os.ReadFile(candidate)
	if err != nil {
		return report, err
	}
	if !bytes.Contains(data, []byte(identity)) {
		return report, fmt.Errorf("missing linked preparation identity")
	}
	hash, err := fileHash(candidate)
	if err != nil {
		return report, err
	}
	return footprintReport{BaselineBytes: base.Size(), CandidateBytes: selected.Size(), CandidateSHA256: hash, BuildInfo: info.String()}, nil
}
