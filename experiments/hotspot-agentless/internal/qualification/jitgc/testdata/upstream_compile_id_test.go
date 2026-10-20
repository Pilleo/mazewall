package hotspot

import (
	"bytes"
	"encoding/binary"
	"strings"
	"testing"

	"go.opentelemetry.io/ebpf-profiler/libpf"
	"go.opentelemetry.io/ebpf-profiler/remotememory"
)

func pinnedGenerationFixture(t *testing.T, current uint32) *hotspotInstance {
	t.Helper()
	raw := make([]byte, 64)
	binary.LittleEndian.PutUint32(raw[16:], current)
	data := &hotspotData{}
	_, err := data.GetOrInit(func() (hotspotVMData, error) {
		value := hotspotVMData{}
		value.vmStructs.Nmethod.Sizeof = 16
		value.vmStructs.Nmethod.CompileID = 0
		return value, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	instance, err := data.Attach(nil, 0, 0,
		remotememory.RemoteMemory{ReaderAt: bytes.NewReader(raw)})
	if err != nil {
		t.Fatal(err)
	}
	return instance.(*hotspotInstance)
}

func TestPinnedCompileIDCacheHit(t *testing.T) {
	instance := pinnedGenerationFixture(t, 9)
	cached := &hotspotJITInfo{compileID: 7}
	instance.addrToJITInfo.Add(libpf.Address(16), cached)
	got, err := instance.getJITInfo(libpf.Address(16), 7)
	if err != nil || got != cached {
		t.Fatal(got, err)
	}
}

func TestPinnedCompileIDRejectsStaleCache(t *testing.T) {
	instance := pinnedGenerationFixture(t, 9)
	instance.addrToJITInfo.Add(libpf.Address(16), &hotspotJITInfo{compileID: 7})
	got, err := instance.getJITInfo(libpf.Address(16), 8)
	if got != nil || err == nil || !strings.Contains(err.Error(), "JIT info evicted since eBPF snapshot") {
		t.Fatal("stale cache bypassed captured generation", got, err)
	}
}

func TestPinnedCompileIDRejectsFreshMismatch(t *testing.T) {
	instance := pinnedGenerationFixture(t, 9)
	got, err := instance.getJITInfo(libpf.Address(16), 8)
	if got != nil || err == nil || !strings.Contains(err.Error(), "JIT info evicted since eBPF snapshot") {
		t.Fatal("fresh nmethod bypassed captured generation", got, err)
	}
}
