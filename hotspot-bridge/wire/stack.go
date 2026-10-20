package wire

type Mapping struct {
	FileID     string     `json:"file_id"`
	Filename   string     `json:"filename"`
	GNUBuildID string     `json:"gnu_build_id,omitempty"`
	GoBuildID  string     `json:"go_build_id,omitempty"`
	Start      uint64     `json:"start"`
	End        uint64     `json:"end"`
	FileOffset uint64     `json:"file_offset"`
	Extensions extensions `json:"-"`
}

type Frame struct {
	ProviderType      uint8      `json:"provider_type"`
	FrameType         string     `json:"frame_type"`
	FunctionName      string     `json:"function_name"`
	FunctionOffset    uint32     `json:"function_offset"`
	SourceFile        string     `json:"source_file,omitempty"`
	SourceLine        *uint32    `json:"source_line,omitempty"`
	SourceColumn      *uint32    `json:"source_column,omitempty"`
	NativeAddress     *uint64    `json:"native_address,omitempty"`
	BCI               *uint32    `json:"bci,omitempty"`
	RawPosition       uint64     `json:"raw_position"`
	PositionDomain    string     `json:"position_domain"`
	ErrorFrame        bool       `json:"error_frame"`
	ScopeLookup       string     `json:"scope_lookup,omitempty"`
	ScopeWalk         string     `json:"scope_walk,omitempty"`
	BCIState          string     `json:"bci_state,omitempty"`
	RawBCI            *uint32    `json:"raw_bci,omitempty"`
	QualityDiagnostic string     `json:"quality_diagnostic,omitempty"`
	Mapping           *Mapping   `json:"mapping,omitempty"`
	Extensions        extensions `json:"-"`
}

type StackEvidence struct {
	HotSpotIdentity *HotSpotIdentity `json:"hotspot_identity,omitempty"`
	Attribution     string           `json:"attribution"`
	Walk            string           `json:"walk"`
	ScopeLookup     string           `json:"scope_lookup"`
	Reason          string           `json:"reason,omitempty"`
	Provider        Provider         `json:"provider"`
	Frames          []Frame          `json:"frames"`
	RawErrors       []string         `json:"raw_errors,omitempty"`
	Extensions      extensions       `json:"-"`
}
