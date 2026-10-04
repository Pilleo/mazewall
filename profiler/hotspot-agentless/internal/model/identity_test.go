package model

import (
	"encoding/json"
	"go.opentelemetry.io/ebpf-profiler/libpf"
	"strings"
	"testing"
)

func TestCheckedIdentityPreservesObservedEpochZero(t *testing.T) {
	i := CapturedHotSpotIdentity(libpf.HotSpotIdentity{State: libpf.HotSpotEpochStable, Epoch: 0})
	e := Event{HotSpotIdentity: i}
	if e.StackRef().HotSpotIdentity.State != libpf.HotSpotEpochStable {
		t.Fatal("stable zero lost")
	}
	b, err := json.Marshal(i)
	if err != nil || !strings.Contains(string(b), `"epoch":0`) {
		t.Fatal(string(b), err)
	}
	c := CheckedHotSpotIdentity(e.StackRef().HotSpotIdentity)
	if c.State != "safepoint_epoch_checked" || c.Profile != "temurin25_xint_serial" {
		t.Fatal(c)
	}
	if CapturedHotSpotIdentity(libpf.HotSpotIdentity{}) != nil {
		t.Fatal("unknown identity invented epoch")
	}
}
func TestMissingEpochCannotBecomeStableZero(t *testing.T) {
	e := Event{HotSpotIdentity: &HotSpotIdentity{State: "stable"}}
	if e.StackRef().HotSpotIdentity.Validate() == nil {
		t.Fatal("missing epoch became measured zero")
	}
}
