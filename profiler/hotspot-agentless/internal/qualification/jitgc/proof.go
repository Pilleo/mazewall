// Package jitgc evaluates offline candidate evidence; it never admits a production runtime.
package jitgc

import "fmt"

type Facts struct {
	PinnedBuild, Compiler, ConcurrentGC                  bool
	MetadataLease, CodeLease, ConcurrentReclamationLease bool
	CaptureGeneration, DecodeGeneration                  uint64
	Marked, Useful, Wrong, Mixed, OutOfOrder             uint64
}

func Evaluate(f Facts) error {
	if !f.PinnedBuild || f.Marked == 0 || f.Useful == 0 || f.Useful > f.Marked || f.Wrong != 0 || f.Mixed != 0 || f.OutOfOrder != 0 || !f.MetadataLease {
		return fmt.Errorf("runtime candidate lacks useful lifetime-correct evidence")
	}
	if f.Compiler && (!f.CodeLease || f.CaptureGeneration == 0 || f.CaptureGeneration != f.DecodeGeneration) {
		return fmt.Errorf("compiled generation lease absent or changed")
	}
	if f.ConcurrentGC && !f.ConcurrentReclamationLease {
		return fmt.Errorf("concurrent metadata reclamation not protected")
	}
	return nil
}
