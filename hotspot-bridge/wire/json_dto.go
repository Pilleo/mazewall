package wire

// Local aliases avoid recursive JSON methods and preserve the legacy field order.
func (v *Record) UnmarshalJSON(b []byte) error {
	type plain Record
	var p plain
	x, err := unmarshalExtended(b, &p, "schema_version,kind,session_id,event_id,event,evidence,summary,recovery,lifecycle,policy_eligibility")
	if err == nil {
		*v = Record(p)
		v.Extensions = x
	}
	return err
}
func (v Record) MarshalJSON() ([]byte, error) {
	type plain Record
	return marshalExtended(plain(v), v.Extensions)
}
func (v *Lifecycle) UnmarshalJSON(b []byte) error {
	type plain Lifecycle
	var p plain
	x, err := unmarshalExtended(b, &p, "pid,process_start_ticks,clock_domain,boot_ns,runtime_profile,jvm_build_id,jvm_sha256,provider,message,errno,errno_causes,validation_mode,cgroup_id,mount_namespace")
	if err == nil {
		*v = Lifecycle(p)
		v.Extensions = x
	}
	return err
}
func (v Lifecycle) MarshalJSON() ([]byte, error) {
	type plain Lifecycle
	return marshalExtended(plain(v), v.Extensions)
}
func (v *Event) UnmarshalJSON(b []byte) error {
	type plain Event
	var p plain
	x, err := unmarshalExtended(b, &p, "hotspot_identity,session_id,event_id,cpu,sequence,host_pid,host_tid,process_start_ticks,cgroup_id,capture_boot_ns,clock_domain,event_kind,correlation_id,dirfd,flags,mode,filename,filename_bytes,register_read,argument_read,path_truncated")
	if err == nil {
		*v = Event(p)
		v.Extensions = x
	}
	return err
}
func (v Event) MarshalJSON() ([]byte, error) {
	type plain Event
	return marshalExtended(plain(v), v.Extensions)
}
func (v *HotSpotIdentity) UnmarshalJSON(b []byte) error {
	type plain HotSpotIdentity
	var p plain
	x, err := unmarshalExtended(b, &p, "state,epoch,profile,read_error")
	if err == nil {
		*v = HotSpotIdentity(p)
		v.Extensions = x
	}
	return err
}
func (v HotSpotIdentity) MarshalJSON() ([]byte, error) {
	type plain HotSpotIdentity
	return marshalExtended(plain(v), v.Extensions)
}
func (v *Frame) UnmarshalJSON(b []byte) error {
	type plain Frame
	var p plain
	x, err := unmarshalExtended(b, &p, "provider_type,frame_type,function_name,function_offset,source_file,source_line,source_column,native_address,bci,raw_position,position_domain,error_frame,scope_lookup,scope_walk,bci_state,raw_bci,quality_diagnostic,mapping")
	if err == nil {
		*v = Frame(p)
		v.Extensions = x
	}
	return err
}
func (v Frame) MarshalJSON() ([]byte, error) {
	type plain Frame
	return marshalExtended(plain(v), v.Extensions)
}
func (v *Mapping) UnmarshalJSON(b []byte) error {
	type plain Mapping
	var p plain
	x, err := unmarshalExtended(b, &p, "file_id,filename,gnu_build_id,go_build_id,start,end,file_offset")
	if err == nil {
		*v = Mapping(p)
		v.Extensions = x
	}
	return err
}
func (v Mapping) MarshalJSON() ([]byte, error) {
	type plain Mapping
	return marshalExtended(plain(v), v.Extensions)
}
func (v *StackEvidence) UnmarshalJSON(b []byte) error {
	type plain StackEvidence
	var p plain
	x, err := unmarshalExtended(b, &p, "hotspot_identity,attribution,walk,scope_lookup,reason,provider,frames,raw_errors")
	if err == nil {
		*v = StackEvidence(p)
		v.Extensions = x
	}
	return err
}
func (v StackEvidence) MarshalJSON() ([]byte, error) {
	type plain StackEvidence
	return marshalExtended(plain(v), v.Extensions)
}
func (v *RecoveryResult) UnmarshalJSON(b []byte) error {
	type plain RecoveryResult
	var p plain
	x, err := unmarshalExtended(b, &p, "correlation_id,recovery_boot_ns,invalidated,error")
	if err == nil {
		*v = RecoveryResult(p)
		v.Extensions = x
	}
	return err
}
func (v RecoveryResult) MarshalJSON() ([]byte, error) {
	type plain RecoveryResult
	return marshalExtended(plain(v), v.Extensions)
}
func (v *Stats) UnmarshalJSON(b []byte) error {
	type plain Stats
	var p plain
	x, err := unmarshalExtended(b, &p, "received,resolved,unavailable,skipped,max_pending,max_pending_payload_bytes,recovery_attempts,recovery_invalidations,recovery_errors")
	if err == nil {
		*v = Stats(p)
		v.Extensions = x
	}
	return err
}
func (v Stats) MarshalJSON() ([]byte, error) {
	type plain Stats
	return marshalExtended(plain(v), v.Extensions)
}
func (v *Summary) UnmarshalJSON(b []byte) error {
	type plain Summary
	var p plain
	x, err := unmarshalExtended(b, &p, "unproven_collection_sources,selected_event_accounting,event_finalization_observation,definition_stop_observation,enrichment,event_loss,event_loss_observation,definition_loss,definition_reader_observation,collection_health,terminal_accounting_complete,errors")
	if err == nil {
		*v = Summary(p)
		v.Extensions = x
	}
	return err
}
func (v Summary) MarshalJSON() ([]byte, error) {
	type plain Summary
	return marshalExtended(plain(v), v.Extensions)
}
func (v *SelectedAccountingDiagnostic) UnmarshalJSON(b []byte) error {
	type plain SelectedAccountingDiagnostic
	var p plain
	x, err := unmarshalExtended(b, &p, "schema_version,scope,session_id,host_pid,process_start_ticks,map_id,possible_cpus,attempted,submitted,reserve_failed,received,terminals,complete,reason")
	if err == nil {
		*v = SelectedAccountingDiagnostic(p)
		v.Extensions = x
	}
	return err
}
func (v SelectedAccountingDiagnostic) MarshalJSON() ([]byte, error) {
	type plain SelectedAccountingDiagnostic
	return marshalExtended(plain(v), v.Extensions)
}
func (v *EventFinalizationObservation) UnmarshalJSON(b []byte) error {
	type plain EventFinalizationObservation
	var p plain
	x, err := unmarshalExtended(b, &p, "scope,phase,stop_cause,producers_quiesced,buffer_drained,receiver_joined,final_counter_read,native_reserve_failures,background_loss_sampler_started,resources_retired,loss_complete,error")
	if err == nil {
		*v = EventFinalizationObservation(p)
		v.Extensions = x
	}
	return err
}
func (v EventFinalizationObservation) MarshalJSON() ([]byte, error) {
	type plain EventFinalizationObservation
	return marshalExtended(plain(v), v.Extensions)
}
func (v *DefinitionStopObservation) UnmarshalJSON(b []byte) error {
	type plain DefinitionStopObservation
	var p plain
	x, err := unmarshalExtended(b, &p, "reader_stopped,consumer_stopped,buffers_drained,auxiliary_stopped,semantics")
	if err == nil {
		*v = DefinitionStopObservation(p)
		v.Extensions = x
	}
	return err
}
func (v DefinitionStopObservation) MarshalJSON() ([]byte, error) {
	type plain DefinitionStopObservation
	return marshalExtended(plain(v), v.Extensions)
}
func (v *LossObservation) UnmarshalJSON(b []byte) error {
	type plain LossObservation
	var p plain
	x, err := unmarshalExtended(b, &p, "observed,complete,semantics")
	if err == nil {
		*v = LossObservation(p)
		v.Extensions = x
	}
	return err
}
func (v LossObservation) MarshalJSON() ([]byte, error) {
	type plain LossObservation
	return marshalExtended(plain(v), v.Extensions)
}
func (v *ReaderObservation) UnmarshalJSON(b []byte) error {
	type plain ReaderObservation
	var p plain
	x, err := unmarshalExtended(b, &p, "lost,no_data,read_error,buffer_bytes_per_cpu,complete,semantics")
	if err == nil {
		*v = ReaderObservation(p)
		v.Extensions = x
	}
	return err
}
func (v ReaderObservation) MarshalJSON() ([]byte, error) {
	type plain ReaderObservation
	return marshalExtended(plain(v), v.Extensions)
}
