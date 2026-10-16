// Package loom distinguishes offline carrier/mount evidence, not causal async parents.
package loom

type Facts struct {
	CarrierTID                     uint32
	VirtualID, AtCapture, AtDecode uint64
	ObjectLease                    bool
}

func Scope(f Facts) string {
	if f.CarrierTID == 0 {
		return "logical_unavailable"
	}
	if f.VirtualID == 0 {
		return "carrier_only"
	}
	if !f.ObjectLease || f.AtCapture == 0 || f.AtCapture != f.AtDecode {
		return "logical_unavailable"
	}
	return "mounted_virtual"
}
