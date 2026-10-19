package capture

import (
	"bytes"
	"context"
	"encoding/json"
	"strings"
	"testing"

	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
)

func TestObservedPublicEntrypointsValidateBeforeAcquireOrEmit(t *testing.T) {
	for _, run := range []func() error{
		func() error { return RunObserved(context.Background(), Config{}, nil) },
		func() error { return RunObservedControlled(context.Background(), Config{}, nil, nil) },
	} {
		if err := run(); err == nil || !strings.Contains(err.Error(), "observed profile/expected target") {
			t.Fatal("invalid observed scope did not reach validation", err)
		}
	}
}

func TestObservedPublicValidScopeRequiresParentControl(t *testing.T) {
	cfg := Config{PID: 123, Image: "gadget@sha256:" + strings.Repeat("a", 64), Store: "unused", DrainFull: true, ExpectedTarget: wire.Target{PID: 123, StartTicks: 456, CgroupID: 789, MountNS: 42}}
	var out bytes.Buffer
	if err := RunObservedControlled(context.Background(), cfg, &out, nil); err == nil || !strings.Contains(err.Error(), "parent control pipe") || out.Len() != 0 {
		t.Fatal("valid scope must reach control boundary before emission or native work", err, out.String())
	}
}

func TestObservedWireUsesActualTupleAndFalsePolicyOnEveryRow(t *testing.T) {
	var out bytes.Buffer
	sink := newObservedSink(&out)
	target := wire.Target{PID: 123, StartTicks: 456, CgroupID: 789, MountNS: 42}
	for _, kind := range []string{"session_started", "collector_ready", "event", "enrichment", "recovery", "session_summary", "collector_error"} {
		r := model.Record{Kind: kind, SessionID: "synthetic"}
		if strings.HasPrefix(kind, "collector_") || kind == "session_started" {
			var err error
			r, err = observedLifecycleRecord(kind, "synthetic", target, "actual-build", "actual-sha")
			if err != nil {
				t.Fatal(err)
			}
		}
		if err := sink.Write(context.Background(), r); err != nil {
			t.Fatal(err)
		}
	}
	for _, line := range bytes.Split(bytes.TrimSpace(out.Bytes()), []byte{'\n'}) {
		var r model.Record
		if err := json.Unmarshal(line, &r); err != nil {
			t.Fatal(err)
		}
		if r.SchemaVersion != 3 || r.PolicyEligibility == nil || *r.PolicyEligibility {
			t.Fatal("policy/schema", string(line))
		}
		if r.Lifecycle != nil && (r.Lifecycle.CgroupID != 789 || r.Lifecycle.MountNS != 42 || r.Lifecycle.RuntimeProfile != "otel_hotspot25_observed_v1" || r.Lifecycle.ValidationMode != "upstream_generation_checks" || r.Lifecycle.JVMBuildID != "actual-build") {
			t.Fatal(string(line))
		}
	}
	var strict bytes.Buffer
	if err := newProductionSink(&strict).Write(context.Background(), model.Record{Kind: "session_summary"}); err != nil {
		t.Fatal(err)
	}
	if bytes.Contains(strict.Bytes(), []byte("policy_eligibility")) || bytes.Contains(strict.Bytes(), []byte("validation_mode")) {
		t.Fatal("strict wire changed", strict.String())
	}
}

func TestObservedCandidateConfigRejectsFieldsBeforeResources(t *testing.T) {
	c := Config{PID: 123, Image: "gadget@sha256:" + strings.Repeat("a", 64), Store: "unused", DrainFull: true, profile: wire.Observed25, ExpectedTarget: wire.Target{PID: 123, StartTicks: 456, CgroupID: 789, MountNS: 42}}
	if err := c.Validate(); err != nil {
		t.Fatal("valid candidate scope", err)
	}
	for _, mutate := range []func(*Config){func(c *Config) { c.StrictEpoch = true }, func(c *Config) { c.ExpectedTarget.StartTicks = 0 }, func(c *Config) { c.ExpectedTarget.CgroupID = 0 }, func(c *Config) { c.ExpectedTarget.MountNS = 0 }, func(c *Config) { c.ExpectedTarget.PID++ }, func(c *Config) { c.DrainFull = false }, func(c *Config) { c.profile = "typo" }} {
		x := c
		mutate(&x)
		if x.Validate() == nil {
			t.Fatal("invalid scope", x)
		}
	}
}

func TestObservedCapturedTupleRejectsChangedCgroupAndGeneration(t *testing.T) {
	expected := wire.Target{PID: 123, StartTicks: 456, CgroupID: 789, MountNS: 42}
	event := model.Event{PID: 123, ProcessStartTicks: 456, CgroupID: 789}
	if err := validateObservedEvent(expected, event); err != nil {
		t.Fatal(err)
	}
	for _, change := range []func(*model.Event){func(e *model.Event) { e.PID++ }, func(e *model.Event) { e.ProcessStartTicks++ }, func(e *model.Event) { e.CgroupID++ }} {
		e := event
		change(&e)
		if validateObservedEvent(expected, e) == nil {
			t.Fatal("foreign event admitted", e)
		}
	}
}
