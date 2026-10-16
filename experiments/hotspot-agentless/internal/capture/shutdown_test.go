package capture

import (
	"github.com/inspektor-gadget/inspektor-gadget/pkg/symbolizer"
	"testing"
)

func TestJoinedDefinitionSnapshotNeverClaimsDrainedBuffers(t *testing.T) {
	s := definitionStopModel(symbolizer.DefinitionStopObservation{ReaderStopped: true, ConsumerStopped: true})
	if !s.ReaderStopped || !s.ConsumerStopped || s.BuffersDrained || s.Semantics != "owned_definition_workers_joined_partial_snapshot" {
		t.Fatal(s)
	}
}

func TestIncompleteDefinitionStopDoesNotClaimJoinedSnapshot(t *testing.T){
	s:=definitionStopModel(symbolizer.DefinitionStopObservation{ReaderStopped:true})
	if s.Semantics=="owned_definition_workers_joined_partial_snapshot"{t.Fatal("unfinished handler described as joined",s)}
}
