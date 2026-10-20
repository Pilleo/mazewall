package wire

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"hash"
	"math"
	"strings"
	"syscall"
)

// Error classes are stable errors.Is boundaries; messages retain row context.
var (
	ErrJSON       = errors.New("invalid wire JSON")
	ErrLimit      = errors.New("wire limit exceeded")
	ErrIdentity   = errors.New("wire identity mismatch")
	ErrOrder      = errors.New("invalid session order")
	ErrPairing    = errors.New("invalid occurrence pairing")
	ErrAccounting = errors.New("invalid wire accounting")
	ErrFinality   = errors.New("native owners not final")
	ErrCollector  = errors.New("collector failed")
	ErrProcess    = errors.New("collector process failed")
)

type decoderPhase uint8

const (
	awaitStart decoderPhase = iota
	awaitReady
	collecting
	summarized
	failed
)

type occurrence struct {
	correlation         uint64
	capturedEpoch       uint64
	capturedStable      bool
	cpu                 uint32
	terminal, recovered bool
	pair                *Pair
	eventRow            int
}

// Decoder is single-owner state. Accept consumes one JSON row without its newline.
// Failure is sticky; Ready still exposes an owned diagnostic prefix.
type Decoder struct {
	expected                     Expected
	phase                        decoderPhase
	err                          error
	session                      string
	ready                        *Lifecycle
	summary                      *Summary
	seen                         map[string]*occurrence
	order                        []string
	counts                       Stats
	rows                         int
	raw                          [][]byte
	retainedBytes, retainedPairs int
	maxBytes, maxPairs           int
	discarded                    uint64
	digest                       hash.Hash
}

func NewDecoder(expected Expected) *Decoder {
	d := &Decoder{expected: expected, seen: make(map[string]*occurrence), maxBytes: 33554432, maxPairs: 4096, digest: sha256.New()}
	if err := validateExpected(expected); err != nil {
		d.fail(err)
	}
	return d
}

func (d *Decoder) fail(err error) error {
	if d.err == nil {
		d.err = err
	}
	d.phase = failed
	return d.err
}

func (d *Decoder) Accept(line []byte) error {
	if d.err != nil {
		return d.err
	}
	if d.digest == nil {
		return d.fail(fmt.Errorf("%w: uninitialized decoder", ErrIdentity))
	}
	if d.phase == summarized {
		return d.fail(fmt.Errorf("%w: row after summary", ErrOrder))
	}
	if len(line) > 1048576 || d.rows >= 65536 {
		return d.fail(fmt.Errorf("%w: line or row budget", ErrLimit))
	}
	if err := validateJSON(line); err != nil {
		return d.fail(err)
	}
	// Check raw presence before typed decoding can erase null scalar values.
	if err := requiredRecordFields(line); err != nil {
		return d.fail(err)
	}
	var r Record
	if err := json.Unmarshal(line, &r); err != nil {
		return d.fail(fmt.Errorf("%w: %v", ErrJSON, err))
	}
	if r.SchemaVersion != d.expected.Schema || (r.SchemaVersion == 3 && (r.PolicyEligibility == nil || *r.PolicyEligibility)) || (r.SchemaVersion == 2 && r.PolicyEligibility != nil) {
		return d.fail(fmt.Errorf("%w: schema/policy", ErrIdentity))
	}
	if r.SessionID == "" || len(r.SessionID) > 128 || strings.Contains(r.SessionID, ":") || (d.session != "" && r.SessionID != d.session) {
		return d.fail(fmt.Errorf("%w: session", ErrIdentity))
	}
	if err := recordShape(r); err != nil {
		return d.fail(err)
	}
	if r.Kind == "collector_error" {
		l := r.Lifecycle
		if err := d.lifecycle(*l, false); err != nil {
			return d.fail(err)
		}
		cause := ErrCollector
		if l.Errno != 0 {
			cause = errors.Join(cause, syscall.Errno(l.Errno))
		}
		if len(l.ErrnoCauses) > 1 {
			for _, errno := range l.ErrnoCauses[1:] {
				cause = errors.Join(cause, syscall.Errno(errno))
			}
		}
		return d.fail(fmt.Errorf("%w: %s (errno %d)", cause, l.Message, l.Errno))
	}
	var err error
	switch r.Kind {
	case "session_started":
		if d.phase != awaitStart {
			err = fmt.Errorf("%w: duplicate/late start", ErrOrder)
			break
		}
		err = d.lifecycle(*r.Lifecycle, false)
		if err == nil {
			d.session = r.SessionID
			d.phase = awaitReady
			err = d.keep(line)
		}
	case "collector_ready":
		if d.phase != awaitReady {
			err = fmt.Errorf("%w: ready without start", ErrOrder)
			break
		}
		err = d.lifecycle(*r.Lifecycle, true)
		if err == nil {
			d.ready = r.Lifecycle
			d.phase = collecting
			err = d.keep(line)
		}
	case "event", "enrichment", "recovery", "session_summary":
		if d.phase != collecting {
			err = fmt.Errorf("%w: %s before ready", ErrOrder, r.Kind)
			break
		}
		switch r.Kind {
		case "event":
			err = d.event(r, line)
		case "enrichment":
			err = d.terminal(r, line)
		case "recovery":
			err = d.recovery(r, line)
		case "session_summary":
			err = d.validateSummary(*r.Summary)
			if err == nil {
				d.summary = r.Summary
				d.phase = summarized
				err = d.keep(line)
			}
		}
	default:
		err = fmt.Errorf("%w: unknown row kind %q", ErrOrder, r.Kind)
	}
	if err != nil {
		return d.fail(fmt.Errorf("row %d (%s): %w", d.rows+1, r.Kind, err))
	}
	d.rows++
	_, _ = d.digest.Write(line)
	_, _ = d.digest.Write([]byte{'\n'})
	return nil
}

func (d *Decoder) keep(line []byte) error {
	if len(line)+1 > d.maxBytes-d.retainedBytes {
		return fmt.Errorf("%w: retained metadata", ErrLimit)
	}
	d.raw = append(d.raw, bytes.Clone(line))
	d.retainedBytes += len(line) + 1
	return nil
}

func (d *Decoder) event(r Record, line []byte) error {
	e := r.Event
	if err := d.validateEvent(r); err != nil {
		return err
	}
	if _, ok := d.seen[r.EventID]; ok {
		return fmt.Errorf("%w: duplicate event", ErrPairing)
	}
	if len(d.seen) >= 32768 {
		return fmt.Errorf("%w: tracked occurrences", ErrLimit)
	}
	o := &occurrence{correlation: e.CorrelationID, cpu: e.CPU, eventRow: -1}
	// Keep only validated scalar identity when retention discards the payload.
	if d.expected.Schema == 2 && e.HotSpotIdentity != nil && e.HotSpotIdentity.State == "stable" {
		o.capturedEpoch, o.capturedStable = *e.HotSpotIdentity.Epoch, true
	}
	// Leave room for the final summary even when pair retention fills up.
	if d.retainedPairs < d.maxPairs && len(line)+1 <= d.maxBytes-d.retainedBytes-1048577 {
		o.pair = &Pair{Event: *e}
		o.eventRow = len(d.raw)
		_ = d.keep(line)
		d.retainedPairs++
	} else {
		d.discarded++
	}
	d.seen[r.EventID] = o
	d.order = append(d.order, r.EventID)
	d.counts.Received++
	return nil
}

func (d *Decoder) terminal(r Record, line []byte) error {
	o, ok := d.seen[r.EventID]
	if !ok || o.terminal {
		return fmt.Errorf("%w: orphan/duplicate terminal", ErrPairing)
	}
	e := r.Evidence
	if len(e.Frames) > 4096 {
		return fmt.Errorf("%w: frames", ErrLimit)
	}
	if e.Provider != (Provider{}) && e.Provider != d.expected.Provider {
		return fmt.Errorf("%w: terminal provider", ErrIdentity)
	}
	if err := validateHotSpotIdentity(e.HotSpotIdentity, true, d.expected.Profile); err != nil {
		return err
	}
	switch e.Attribution {
	case "event_correlated":
		if o.correlation == 0 || e.Provider != d.expected.Provider || len(e.Frames) == 0 {
			return fmt.Errorf("%w: resolved evidence", ErrIdentity)
		}
		if d.expected.Schema == 2 && (!o.capturedStable || e.HotSpotIdentity == nil || e.HotSpotIdentity.State != "safepoint_epoch_checked" || *e.HotSpotIdentity.Epoch != o.capturedEpoch) {
			return fmt.Errorf("%w: strict terminal does not match captured HotSpot epoch", ErrIdentity)
		}
		d.counts.Resolved++
	case "unavailable":
		d.counts.Unavailable++
	case "skipped":
		d.counts.Skipped++
	default:
		return fmt.Errorf("%w: unknown attribution %q", ErrAccounting, e.Attribution)
	}
	o.terminal = true
	if o.pair != nil {
		if len(line)+1 <= d.maxBytes-d.retainedBytes-1048577 {
			o.pair.Evidence = *e
			_ = d.keep(line)
		} else {
			d.retainedBytes -= len(d.raw[o.eventRow]) + 1
			d.raw[o.eventRow] = nil
			o.pair = nil
			d.retainedPairs--
			d.discarded++
		}
	}
	return nil
}

func (d *Decoder) recovery(r Record, line []byte) error {
	o, ok := d.seen[r.EventID]
	a := r.Recovery
	if !ok || !o.terminal || o.recovered || a.CorrelationID == 0 || a.CorrelationID != o.correlation || a.BootNS == 0 {
		return fmt.Errorf("%w: recovery reference", ErrPairing)
	}
	o.recovered = true
	d.counts.RecoveryAttempts++
	if a.Invalidated {
		d.counts.RecoveryInvalidations++
	}
	if a.Error != "" {
		d.counts.RecoveryErrors++
	}
	if o.pair != nil && len(line)+1 <= d.maxBytes-d.retainedBytes-1048577 {
		return d.keep(line)
	}
	return nil
}

func (d *Decoder) Ready() (Lifecycle, bool) {
	if d.ready == nil {
		return Lifecycle{}, false
	}
	return cloneLifecycle(*d.ready), true
}

// Finish declares that the caller reached actual EOF and successfully joined its
// readers and Wait. It checks logical completion; Accept cannot observe OS EOF.
// A nonnil Wait error permanently invalidates this session, preserving its cause.
func (d *Decoder) Finish(processErr error) (Capture, error) {
	if processErr != nil {
		d.err = errors.Join(d.err, fmt.Errorf("%w: %w", ErrProcess, processErr))
		d.phase = failed
	}
	if d.err != nil {
		return Capture{}, d.err
	}
	if d.phase != summarized {
		return Capture{}, d.fail(fmt.Errorf("%w: missing final summary", ErrOrder))
	}
	c := Capture{SessionID: d.session, Target: d.expected.Target, Profile: d.expected.Profile, Provider: d.expected.Provider, JVMBuildID: d.ready.JVMBuildID, JVMSHA256: d.ready.JVMSHA256, Summary: cloneSummary(*d.summary), Discarded: d.discarded, RawSHA256: hex.EncodeToString(d.digest.Sum(nil))}
	for _, id := range d.order {
		if p := d.seen[id].pair; p != nil {
			c.Pairs = append(c.Pairs, clonePair(*p))
		}
	}
	for _, line := range d.raw {
		if line != nil {
			c.RawJSONL = append(c.RawJSONL, line...)
			c.RawJSONL = append(c.RawJSONL, '\n')
		}
	}
	return c, nil
}

func recordShape(r Record) error {
	payloads := 0
	for _, set := range []bool{r.Event != nil, r.Evidence != nil, r.Summary != nil, r.Recovery != nil, r.Lifecycle != nil} {
		if set {
			payloads++
		}
	}
	valid := false
	switch r.Kind {
	case "session_started", "collector_ready", "collector_error":
		valid = r.Lifecycle != nil && r.EventID == ""
	case "event":
		valid = r.Event != nil && r.EventID != ""
	case "enrichment":
		valid = r.Evidence != nil && r.EventID != ""
	case "recovery":
		valid = r.Recovery != nil && r.EventID != ""
	case "session_summary":
		valid = r.Summary != nil && r.EventID == ""
	}
	if payloads != 1 || !valid {
		return fmt.Errorf("%w: row payload", ErrOrder)
	}
	return nil
}

func validateExpected(x Expected) error {
	if !((x.Schema == 2 && x.Profile == Strict25) || (x.Schema == 3 && x.Profile == Observed25)) || x.Target.PID == 0 || x.Target.StartTicks == 0 || (x.Schema == 3 && (x.Target.CgroupID == 0 || x.Target.MountNS == 0)) || !validProvider(x.Provider) {
		return fmt.Errorf("%w: expected schema/profile/provider/target", ErrIdentity)
	}
	return nil
}

func validHex(value string, n int) bool {
	if len(value) != n {
		return false
	}
	for _, c := range value {
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
			return false
		}
	}
	return true
}
func validProvider(p Provider) bool {
	return p.Name == "otel-ebpf-profiler-via-gadget" && validHex(p.GadgetRevision, 40) && validHex(p.ProfilerRevision, 40) && validHex(p.PatchSHA256, 64)
}

func (d *Decoder) lifecycle(l Lifecycle, ready bool) error {
	x := d.expected
	if l.PID != x.Target.PID || l.ClockDomain != "CLOCK_BOOTTIME" || l.BootNS == 0 || l.RuntimeProfile != string(x.Profile) || l.Provider != x.Provider {
		return fmt.Errorf("%w: lifecycle", ErrIdentity)
	}
	if l.ProcessStartTicks != 0 && l.ProcessStartTicks != x.Target.StartTicks {
		return fmt.Errorf("%w: lifecycle birth", ErrIdentity)
	}
	if ready && (l.ProcessStartTicks != x.Target.StartTicks || l.JVMBuildID == "" || !validHex(l.JVMBuildID, len(l.JVMBuildID)) || !validHex(l.JVMSHA256, 64) || l.Message != "" || l.Errno != 0) {
		return fmt.Errorf("%w: ready target/build", ErrIdentity)
	}
	if x.Schema == 3 {
		if (ready || l.CgroupID != 0) && l.CgroupID != x.Target.CgroupID || (ready || l.MountNS != 0) && l.MountNS != x.Target.MountNS || (ready || l.ValidationMode != "") && l.ValidationMode != "upstream_generation_checks" {
			return fmt.Errorf("%w: observed target/validation", ErrIdentity)
		}
	} else if l.ValidationMode != "" || l.CgroupID != 0 || l.MountNS != 0 {
		return fmt.Errorf("%w: schema2 observation fields", ErrIdentity)
	}
	return nil
}

func (d *Decoder) validateEvent(r Record) error {
	e := r.Event
	x := d.expected
	if e.EventID != r.EventID || e.EventID != fmt.Sprintf("%s:%d:%d", d.session, e.CPU, e.Sequence) || e.Sequence == 0 {
		return fmt.Errorf("%w: event ID/sequence", ErrPairing)
	}
	if e.SessionID != d.session || e.PID != x.Target.PID || e.TID == 0 || e.ProcessStartTicks != x.Target.StartTicks || e.CgroupID == 0 || e.CgroupID != x.Target.CgroupID || e.ClockDomain != "CLOCK_BOOTTIME" || e.Kind != "openat_handler_entry" || e.CaptureBootNS == 0 || e.CorrelationID > math.MaxInt64 || e.RegisterRead > 0 || e.Filename != string([]rune(string(e.FilenameBytes))) {
		return fmt.Errorf("%w: event target/clock/arguments", ErrIdentity)
	}
	if e.ArgumentRead > 0 {
		if e.ArgumentRead > 256 || int(e.ArgumentRead) != len(e.FilenameBytes)+1 || e.RegisterRead != 0 {
			return fmt.Errorf("%w: filename read count", ErrIdentity)
		}
	} else if len(e.FilenameBytes) != 0 || e.RegisterRead != 0 && e.ArgumentRead != e.RegisterRead {
		return fmt.Errorf("%w: filename read error", ErrIdentity)
	}
	if e.PathTruncated != (e.ArgumentRead == 256) {
		return fmt.Errorf("%w: filename truncation", ErrIdentity)
	}
	return validateHotSpotIdentity(e.HotSpotIdentity, false, x.Profile)
}
