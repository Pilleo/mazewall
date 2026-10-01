package model

import "go.opentelemetry.io/ebpf-profiler/libpf"

type Mapping struct {
	FileID     string `json:"file_id"`
	Filename   string `json:"filename"`
	GNUBuildID string `json:"gnu_build_id,omitempty"`
	GoBuildID  string `json:"go_build_id,omitempty"`
	Start      uint64 `json:"start"`
	End        uint64 `json:"end"`
	FileOffset uint64 `json:"file_offset"`
}

type Frame struct {
	ProviderType      uint8    `json:"provider_type"`
	FrameType         string   `json:"frame_type"`
	FunctionName      string   `json:"function_name"`
	FunctionOffset    uint32   `json:"function_offset"`
	SourceFile        string   `json:"source_file,omitempty"`
	SourceLine        *uint32  `json:"source_line,omitempty"`
	SourceColumn      *uint32  `json:"source_column,omitempty"`
	NativeAddress     *uint64  `json:"native_address,omitempty"`
	BCI               *uint32  `json:"bci,omitempty"`
	RawPosition       uint64   `json:"raw_position"`
	PositionDomain    string   `json:"position_domain"`
	ErrorFrame        bool     `json:"error_frame"`
	ScopeLookup       string   `json:"scope_lookup,omitempty"`
	ScopeWalk         string   `json:"scope_walk,omitempty"`
	BCIState          string   `json:"bci_state,omitempty"`
	RawBCI            *uint32  `json:"raw_bci,omitempty"`
	QualityDiagnostic string   `json:"quality_diagnostic,omitempty"`
	Mapping           *Mapping `json:"mapping,omitempty"`
}

type Provider struct {
	Name             string `json:"name"`
	GadgetRevision   string `json:"gadget_revision"`
	ProfilerRevision string `json:"profiler_revision"`
	PatchSHA256      string `json:"patch_sha256"`
}

type StackEvidence struct {
	Attribution string   `json:"attribution"`
	Walk        string   `json:"walk"`
	ScopeLookup string   `json:"scope_lookup"`
	Reason      string   `json:"reason,omitempty"`
	Provider    Provider `json:"provider"`
	Frames      []Frame  `json:"frames"`
	RawErrors   []string `json:"raw_errors,omitempty"`
}

func Unavailable(reason string, err error) StackEvidence {
	e := StackEvidence{Attribution: "unavailable", Walk: "unknown", ScopeLookup: "unknown", Reason: reason, Frames: []Frame{}}
	if err != nil {
		e.RawErrors = []string{err.Error()}
	}
	return e
}

// Normalize preserves provider sequence and position domains. BCI requires
// explicit provider evidence; the legacy position alone cannot establish it.
func Normalize(raw libpf.Frames) []Frame {
	result := make([]Frame, 0, len(raw))
	for _, handle := range raw {
		v := handle.Value()
		f := Frame{ProviderType: uint8(v.Type), FrameType: v.Type.String(), FunctionName: v.FunctionName.String(), FunctionOffset: v.FunctionOffset, SourceFile: v.SourceFile.String(), RawPosition: uint64(v.AddressOrLineno), PositionDomain: "provider_specific", ErrorFrame: v.Type.IsError()}
		if v.SourceLine != 0 {
			line := uint32(v.SourceLine)
			f.SourceLine = &line
		}
		if v.SourceColumn != 0 {
			col := uint32(v.SourceColumn)
			f.SourceColumn = &col
		}
		switch {
		case v.Type.IsError():
			f.PositionDomain = "provider_error_code"
		case v.Type == libpf.NativeFrame:
			address := uint64(v.AddressOrLineno)
			f.NativeAddress = &address
			f.PositionDomain = "elf_virtual_address"
		case v.Type == libpf.KernelFrame:
			address := uint64(v.AddressOrLineno)
			f.NativeAddress = &address
			f.PositionDomain = "kernel_address"
		case v.Type == libpf.HotSpotFrame:
			f.PositionDomain = "provider_hotspot_bci_or_fallback"
			normalizeHotSpotQuality(&f, v.HotSpotQuality)
		}
		if v.Mapping.Valid() {
			mapping := v.Mapping.Value()
			f.Mapping = &Mapping{Start: uint64(mapping.Start), End: uint64(mapping.End), FileOffset: mapping.FileOffset}
			if mapping.File != (libpf.FrameMappingFile{}) {
				file := mapping.File.Value()
				f.Mapping.FileID = file.FileID.StringNoQuotes()
				f.Mapping.Filename = file.FileName.String()
				f.Mapping.GNUBuildID = file.GnuBuildID
				f.Mapping.GoBuildID = file.GoBuildID
			}
		}
		result = append(result, f)
	}
	return result
}
