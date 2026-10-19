package model

import (
	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"go.opentelemetry.io/ebpf-profiler/libpf"
)

type Mapping = wire.Mapping
type Frame = wire.Frame
type Provider = wire.Provider
type StackEvidence = wire.StackEvidence

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
