package capture

import (
	"errors"
	"fmt"
	"go.opentelemetry.io/ebpf-profiler/libpf"
	"slices"

	"github.com/inspektor-gadget/inspektor-gadget/pkg/datasource"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
)

type eventDecoder struct {
	fields     map[string]datasource.FieldAccessor
	sessionID  string
	pid        uint32
	startTicks uint64
}

func newEventDecoder(ds datasource.DataSource, sessionID string, pid uint32, startTicks uint64, strict ...bool) (eventDecoder, error) {
	d := eventDecoder{fields: map[string]datasource.FieldAccessor{}, sessionID: sessionID, pid: pid, startTicks: startTicks}
	for _, name := range []string{"capture_boot_ns", "sequence", "process_start_ticks", "cgroup_id", "cpu", "tgid", "tid", "dirfd", "flags", "mode", "register_read", "argument_read", "path_truncated", "filename", "ustack.otel_correlation_id"} {
		field := ds.GetField(name)
		if field == nil {
			return eventDecoder{}, fmt.Errorf("capture field %q missing", name)
		}
		d.fields[name] = field
	}
	for _, name := range []string{"hotspot_epoch", "hotspot_epoch_state", "hotspot_epoch_read_error"} {
		field := ds.GetField(name)
		if field != nil {
			d.fields[name] = field
		} else if len(strict) > 0 && strict[0] {
			return eventDecoder{}, fmt.Errorf("strict epoch field %q missing", name)
		}
	}
	return d, nil
}

func (d eventDecoder) Decode(data datasource.Data) (model.Event, error) {
	var errs []error
	u64 := func(name string) uint64 {
		v, err := d.fields[name].Uint64(data)
		if err != nil {
			errs = append(errs, fmt.Errorf("%s: %w", name, err))
		}
		return v
	}
	u32 := func(name string) uint32 {
		v, err := d.fields[name].Uint32(data)
		if err != nil {
			errs = append(errs, fmt.Errorf("%s: %w", name, err))
		}
		return v
	}
	i32 := func(name string) int32 {
		v, err := d.fields[name].Int32(data)
		if err != nil {
			errs = append(errs, fmt.Errorf("%s: %w", name, err))
		}
		return v
	}
	e := model.Event{SessionID: d.sessionID, ClockDomain: "CLOCK_BOOTTIME", Kind: "openat_handler_entry", CaptureBootNS: u64("capture_boot_ns"), Sequence: u64("sequence"), ProcessStartTicks: u64("process_start_ticks"), CgroupID: u64("cgroup_id"), CPU: u32("cpu"), PID: u32("tgid"), TID: u32("tid"), DirFD: i32("dirfd"), Flags: u64("flags"), Mode: u64("mode"), RegisterRead: i32("register_read"), ArgumentRead: i32("argument_read"), PathTruncated: u32("path_truncated") != 0, CorrelationID: u64("ustack.otel_correlation_id")}
	if err := errors.Join(errs...); err != nil {
		return model.Event{}, err
	}
	if d.fields["hotspot_epoch_state"] != nil {
		id := libpf.HotSpotIdentity{Epoch: u64("hotspot_epoch"), State: libpf.HotSpotEpochState(u32("hotspot_epoch_state")), ReadError: i32("hotspot_epoch_read_error")}
		if err := errors.Join(errors.Join(errs...), id.Validate()); err != nil {
			return model.Event{}, err
		}
		e.HotSpotIdentity = model.CapturedHotSpotIdentity(id)
	}
	if e.PID != d.pid || e.ProcessStartTicks != d.startTicks || e.TID == 0 || e.Sequence == 0 || e.CaptureBootNS == 0 {
		return model.Event{}, fmt.Errorf("capture process/occurrence identity mismatch")
	}
	if e.ArgumentRead > 0 {
		bytes := d.fields["filename"].Get(data)
		if int(e.ArgumentRead) > len(bytes) {
			return model.Event{}, fmt.Errorf("filename read count exceeds snapshot")
		}
		e.FilenameBytes = slices.Clone(bytes[:int(e.ArgumentRead)-1])
		e.Filename = string(e.FilenameBytes)
	} else {
		e.FilenameBytes = []byte{}
	}
	e.EventID = fmt.Sprintf("%s:%d:%d", d.sessionID, e.CPU, e.Sequence)
	return e, nil
}
