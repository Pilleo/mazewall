package benchmark

import (
	"os"
	"testing"
)

func TestResourcesObserveActualProcessAndRejectIncompleteWindows(t *testing.T) {
	sample, err := readResourceSample(os.Getpid())
	if err != nil || sample[0] <= 0 || sample[2] <= 0 {
		t.Fatalf("actual process resource sample: %v %v", sample, err)
	}
	if _, err := readResourceSample(-1); err == nil {
		t.Fatal("nonexistent process treated as observed")
	}
	sampler := resourceSampler{samples: [][3]int64{{100, 10, 20}, {300, 30, 40}}}
	observation, err := sampler.observation(100)
	if err != nil || observation.CPUSeconds != .2 || observation.PeakRSSKiB != 40 || observation.WindowNS != 200 {
		t.Fatalf("wrong resource window: %+v %v", observation, err)
	}
	for _, variant := range []*resourceSampler{{samples: sampler.samples, failures: []string{"permission denied"}}, {samples: sampler.samples[:1]}, {samples: [][3]int64{{300, 10, 20}, {100, 30, 40}}}, {samples: [][3]int64{{100, 30, 20}, {300, 10, 40}}}} {
		if _, err := variant.observation(100); err == nil {
			t.Fatal("incomplete or reversed resource observation admitted")
		}
	}
}
