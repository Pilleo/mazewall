package main

import (
	"encoding/json"
	"fmt"
	"io.mazewall/experiments/hotspot-agentless/internal/benchmark"
	"os"
	"path/filepath"
)

func main() {
	readiness := len(os.Args) == 3 && os.Args[1] == "--readiness"
	if len(os.Args) != 2 && !readiness {
		fmt.Fprintln(os.Stderr, "usage: mw-benchmark-report [--readiness] <run-directory>")
		os.Exit(2)
	}
	dir := os.Args[len(os.Args)-1]
	b, e := os.ReadFile(filepath.Join(dir, "benchmark-manifest.json"))
	if e != nil {
		fail(e)
	}
	var m benchmark.RunManifest
	if e = json.Unmarshal(b, &m); e != nil {
		fail(e)
	}
	if readiness {
		events, err := os.Open(filepath.Join(dir, "events.jsonl"))
		if err != nil {
			fail(err)
		}
		defer events.Close()
		r, err := benchmark.ObserveReadiness(m, events)
		if err != nil {
			fail(err)
		}
		if err = json.NewEncoder(os.Stdout).Encode(r); err != nil {
			fail(err)
		}
		return
	}
	target, e := os.Open(filepath.Join(dir, "benchmark-target.json"))
	if e != nil {
		fail(e)
	}
	defer target.Close()
	events, e := os.Open(filepath.Join(dir, "events.jsonl"))
	if e != nil {
		fail(e)
	}
	defer events.Close()
	r, e := benchmark.Analyze(m, target, events)
	if e != nil {
		fail(e)
	}
	if e = json.NewEncoder(os.Stdout).Encode(r); e != nil {
		fail(e)
	}
}
func fail(e error) { fmt.Fprintln(os.Stderr, e); os.Exit(1) }
