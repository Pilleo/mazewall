package capture

import (
	"bytes"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/datasource"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/gadget-service/api"
	"testing"
)

func TestDecoderRejectsMissingSchemaAndPreservesOwnedRawFilename(t *testing.T) {
	ds, err := datasource.New(datasource.TypeSingle, "openat")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := newEventDecoder(ds, "session-A", 123, 987); err == nil {
		t.Fatal("accepted missing capture fields")
	}
	for _, name := range []string{"capture_boot_ns", "sequence", "process_start_ticks", "cgroup_id", "flags", "mode", "ustack.otel_correlation_id"} {
		if _, err := ds.AddField(name, api.Kind_Uint64); err != nil {
			t.Fatal(err)
		}
	}
	for _, name := range []string{"cpu", "tgid", "tid", "path_truncated"} {
		if _, err := ds.AddField(name, api.Kind_Uint32); err != nil {
			t.Fatal(err)
		}
	}
	for _, name := range []string{"dirfd", "register_read", "argument_read"} {
		if _, err := ds.AddField(name, api.Kind_Int32); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := ds.AddField("filename", api.Kind_Bytes); err != nil {
		t.Fatal(err)
	}
	decoder, err := newEventDecoder(ds, "session-A", 123, 987)
	if err != nil {
		t.Fatal(err)
	}
	packet, err := ds.NewPacketSingle()
	if err != nil {
		t.Fatal(err)
	}
	for name, val := range map[string]uint64{"capture_boot_ns": 100, "sequence": 1, "process_start_ticks": 987, "cgroup_id": 20, "flags": 0, "mode": 0, "ustack.otel_correlation_id": 7} {
		if err := ds.GetField(name).PutUint64(packet, val); err != nil {
			t.Fatal(err)
		}
	}
	for name, val := range map[string]uint32{"cpu": 2, "tgid": 123, "tid": 456, "path_truncated": 0} {
		if err := ds.GetField(name).PutUint32(packet, val); err != nil {
			t.Fatal(err)
		}
	}
	for name, val := range map[string]int32{"dirfd": -100, "register_read": 0, "argument_read": 4} {
		if err := ds.GetField(name).PutInt32(packet, val); err != nil {
			t.Fatal(err)
		}
	}
	filename := []byte{'a', 0xff, 'b', 0}
	if err := ds.GetField("filename").Set(packet, filename); err != nil {
		t.Fatal(err)
	}
	event, err := decoder.Decode(packet)
	if err != nil {
		t.Fatal(err)
	}
	filename[0] = 'z'
	if !bytes.Equal(event.FilenameBytes, []byte{'a', 0xff, 'b'}) || event.EventID != "session-A:2:1" || event.CorrelationID != 7 {
		t.Fatalf("capture ownership or identity lost: %+v", event)
	}
	if err := ds.GetField("process_start_ticks").PutUint64(packet, 988); err != nil {
		t.Fatal(err)
	}
	if _, err := decoder.Decode(packet); err == nil {
		t.Fatal("reused PID generation accepted")
	}
}
