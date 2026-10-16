package loom

import "testing"

func TestCarrierReuseCannotCreateVirtualIdentity(t *testing.T) {
	if Scope(Facts{CarrierTID: 123, VirtualID: 7}) != "logical_unavailable" {
		t.Fatal("guessed logical identity")
	}
	if Scope(Facts{CarrierTID: 123}) != "carrier_only" {
		t.Fatal("physical carrier lost")
	}
}
func TestMountGenerationAndObjectLeaseRequired(t *testing.T) {
	f := Facts{CarrierTID: 123, VirtualID: 7, AtCapture: 4, AtDecode: 4, ObjectLease: true}
	if Scope(f) != "mounted_virtual" {
		t.Fatal("known mounted identity lost")
	}
	f.AtDecode = 5
	if Scope(f) != "logical_unavailable" {
		t.Fatal("remount admitted")
	}
	f.AtDecode = 4
	f.ObjectLease = false
	if Scope(f) != "logical_unavailable" {
		t.Fatal("GC object lease absent")
	}
	f.ObjectLease = true
	f.AtCapture = 0
	f.AtDecode = 0
	if Scope(f) != "logical_unavailable" {
		t.Fatal("missing mount stamp admitted")
	}
}
