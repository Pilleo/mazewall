package jitgc

import "testing"

func TestCompiledCaptureNeedsLifetimeLease(t *testing.T) {
	f := Facts{PinnedBuild: true, Useful: 10, Marked: 10, Compiler: true, CaptureGeneration: 7, DecodeGeneration: 7, MetadataLease: true}
	if Evaluate(f) == nil {
		t.Fatal("compiled lifetime lease absent")
	}
	f.CodeLease = true
	if err := Evaluate(f); err != nil {
		t.Fatal(err)
	}
	f.DecodeGeneration = 8
	if Evaluate(f) == nil {
		t.Fatal("reused nmethod admitted")
	}
}
func TestConcurrentGCNeedsIndependentReclamationLease(t *testing.T) {
	f := Facts{PinnedBuild: true, Useful: 10, Marked: 10, ConcurrentGC: true, MetadataLease: true}
	if Evaluate(f) == nil {
		t.Fatal("concurrent reclamation unprotected")
	}
	f.ConcurrentReclamationLease = true
	if err := Evaluate(f); err != nil {
		t.Fatal(err)
	}
}
func TestNegativeCorpusNeverQualifies(t *testing.T) {
	good := Facts{PinnedBuild: true, Useful: 10, Marked: 10, MetadataLease: true}
	for _, mutate := range []func(*Facts){func(f *Facts) { f.PinnedBuild = false }, func(f *Facts) { f.Useful = 0 }, func(f *Facts) { f.Wrong = 1 }, func(f *Facts) { f.Mixed = 1 }, func(f *Facts) { f.OutOfOrder = 1 }, func(f *Facts) { f.MetadataLease = false }} {
		bad := good
		mutate(&bad)
		if Evaluate(bad) == nil {
			t.Fatal(bad)
		}
	}
}
