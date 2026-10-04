package capture

import (
	"context"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/symbolizer"
	"testing"
	"time"
)

func TestFullDrainRequiresStrictStructuredOwnership(t *testing.T) {
	c := Config{PID: 123, Image: "mw-hotspot-openat@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", Store: "/tmp/store", DrainFull: true}
	if c.Validate() == nil {
		t.Fatal("full drain accepted unbounded HotSpot identity")
	}
	c.StrictEpoch = true
	if err := c.Validate(); err != nil {
		t.Fatal(err)
	}
	c.DrainEvents = true
	if c.Validate() == nil {
		t.Fatal("conflicting native owners accepted")
	}
}
func TestFullDrainOwnedLifetimeSurvivesGracefulRequest(t *testing.T) {
	request, cancelRequest := context.WithCancel(context.Background())
	owned, cancelOwned := fullOwnedContext(request, Config{DrainFull: true}, time.Now().Add(time.Second))
	defer cancelOwned()
	cancelRequest()
	if owned.Err() != nil {
		t.Fatal("graceful request aborted queued definitions")
	}
	cancelOwned()
	if owned.Err() == nil {
		t.Fatal("fatal/owned abort ignored")
	}
}
func TestFullDrainModelRequiresBothAcknowledgedOwners(t *testing.T) {
	r := definitionStopModel(symbolizer.DefinitionStopObservation{ReaderStopped: true, ConsumerStopped: true, BuffersDrained: true})
	if !r.BuffersDrained {
		t.Fatal("acknowledged perf drain lost")
	}
	r = definitionStopModel(symbolizer.DefinitionStopObservation{ReaderStopped: true, BuffersDrained: true})
	if r.BuffersDrained {
		t.Fatal("unjoined consumer certified flushed")
	}
}
