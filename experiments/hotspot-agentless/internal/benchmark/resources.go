package benchmark

import (
	"fmt"
	"golang.org/x/sys/unix"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

type resourceSampler struct {
	stopSignal chan struct{}
	done       chan struct{}
	once       sync.Once
	samples    [][3]int64
	failures   []string
}

func startResourceSampler(pid int) *resourceSampler {
	s := &resourceSampler{stopSignal: make(chan struct{}), done: make(chan struct{}), samples: [][3]int64{}, failures: []string{}}
	go func() {
		defer close(s.done)
		ticker := time.NewTicker(20 * time.Millisecond)
		defer ticker.Stop()
		for {
			sample, err := readResourceSample(pid)
			if err != nil {
				s.failures = append(s.failures, err.Error())
			} else {
				s.samples = append(s.samples, sample)
			}
			select {
			case <-s.stopSignal:
				return
			case <-ticker.C:
			}
		}
	}()
	return s
}

func (s *resourceSampler) stop() { s.once.Do(func() { close(s.stopSignal) }); <-s.done }

func readResourceSample(pid int) ([3]int64, error) {
	var sample [3]int64
	stat, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid))
	if err != nil {
		return sample, err
	}
	index := strings.LastIndexByte(string(stat), ')')
	if index < 0 {
		return sample, fmt.Errorf("missing process stat command boundary")
	}
	fields := strings.Fields(string(stat[index+1:]))
	if len(fields) < 13 {
		return sample, fmt.Errorf("truncated process resource fields")
	}
	user, err := strconv.ParseInt(fields[11], 10, 64)
	if err != nil {
		return sample, err
	}
	system, err := strconv.ParseInt(fields[12], 10, 64)
	if err != nil {
		return sample, err
	}
	status, err := os.ReadFile(fmt.Sprintf("/proc/%d/status", pid))
	if err != nil {
		return sample, err
	}
	rss := int64(-1)
	for _, line := range strings.Split(string(status), "\n") {
		if strings.HasPrefix(line, "VmRSS:") {
			fields := strings.Fields(line)
			if len(fields) != 3 || fields[2] != "kB" {
				return sample, fmt.Errorf("invalid process RSS units")
			}
			rss, err = strconv.ParseInt(fields[1], 10, 64)
			if err != nil {
				return sample, err
			}
			break
		}
	}
	if rss < 0 || user < 0 || system < 0 {
		return sample, fmt.Errorf("missing/negative process resource sample")
	}
	var clock unix.Timespec
	if err := unix.ClockGettime(unix.CLOCK_MONOTONIC, &clock); err != nil {
		return sample, err
	}
	return [3]int64{clock.Nano(), user + system, rss}, nil
}

func (s *resourceSampler) observation(ticks int64) (ResourceObservation, error) {
	var result ResourceObservation
	if len(s.failures) > 0 || len(s.samples) < 2 {
		return result, fmt.Errorf("incomplete collector resource samples: %v", s.failures)
	}
	first, last := s.samples[0], s.samples[len(s.samples)-1]
	if ticks <= 0 || last[0] <= first[0] || last[1] < first[1] {
		return result, fmt.Errorf("invalid collector resource window")
	}
	result = ResourceObservation{CPUSeconds: float64(last[1]-first[1]) / float64(ticks), Samples: len(s.samples), WindowNS: uint64(last[0] - first[0]), Semantics: "collector_process_attach_to_target_done"}
	for _, sample := range s.samples {
		if uint64(sample[2]) > result.PeakRSSKiB {
			result.PeakRSSKiB = uint64(sample[2])
		}
	}
	return result, nil
}
