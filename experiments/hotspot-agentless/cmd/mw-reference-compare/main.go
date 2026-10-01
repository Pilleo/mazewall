package main

import (
	"encoding/json"
	"fmt"
	"io.mazewall/experiments/hotspot-agentless/internal/corpus"
	"os"
)

func run() error {
	if len(os.Args) != 4 {
		return fmt.Errorf("usage: <candidate-report.json> <reference-report.json> <output.json>")
	}
	var c corpus.Report
	var r corpus.ReferenceReport
	for _, input := range []struct {
		path  string
		value any
	}{{os.Args[1], &c}, {os.Args[2], &r}} {
		data, err := os.ReadFile(input.path)
		if err != nil {
			return err
		}
		if len(data) > 4*1024*1024 {
			return fmt.Errorf("report input exceeds bound")
		}
		if err := json.Unmarshal(data, input.value); err != nil {
			return err
		}
	}
	d, err := corpus.CompareReference(c, r)
	if err != nil {
		return err
	}
	data, err := json.MarshalIndent(d, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(os.Args[3], append(data, '\n'), 0600)
}
func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}
