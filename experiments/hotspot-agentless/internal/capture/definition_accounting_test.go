// Copyright 2026 The mazewall Authors
// SPDX-License-Identifier: Apache-2.0

package capture

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io.mazewall/experiments/hotspot-agentless/internal/accounting"
	"math"
	"strings"
	"testing"
	"time"

	cebpf "github.com/cilium/ebpf"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/operators"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/symbolizer"
	"go.opentelemetry.io/ebpf-profiler/libpf"
	"go.opentelemetry.io/ebpf-profiler/periodiccaller"
	"go.opentelemetry.io/ebpf-profiler/tracer"
	"golang.org/x/sys/unix"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
)

type mockBindingSymbolizer struct {
	binding libpf.TraceDefinitionBinding
	err     error
	calls   int
}

type hostileProjectionError struct{}

const queueDiagnosticFixture = `{"unit":"owned-map-update-request","requests_owned":18446744073709551615,"queue_accepted":2,"admission_held":3,"refused":4,"applied":5,"applied_published":6,"applied_not_published":7,"failed":8,"aborted":9,"pending":0,"cleanup_failed":11,"pending_known":false,"all_workers_ended":true,"caller_joined_claim":false,"scope_joined_witness":true,"held":false,"last_unavailable":true,"last_published":false,"outcome":1,"proof_error_present":false,"last_apply_error_present":true,"last_cleanup_error_present":false}`

const processingBindingFixture = `,"expected_binding":{"source":1,"session":"session","host_tgid":123,"birth":18446744073709551615,"counter_map_id":7,"perf_map_id":8,"possible_cpus":2,"generation":9}`
const processingDiagnosticFixture = `{"unit":"handle-trace-input","has_binding":true` + processingBindingFixture + `,"epoch_target":{"pid":123,"process_start_ticks":18446744073709551615,"counter_address":7,"initial_epoch":0},"observed_pid":123,"admitted":18446744073709551615,"pre_reporter_rejected":1,"reporter_ok":2,"reporter_error":3,"in_flight":0,"target_bound":4,"foreign_to_binding":5,"binding_unknown":6,"overflow":false,"has_last_admitted":true,"last_admitted":{"pid":123,"tid":124,"cpu":-1,"origin":18446744073709551615,"ktime":-2,"correlation_id":8,"hotspot_identity":{"epoch":0,"state":6,"read_error":-13}},"has_last_classified":true,"last_classified":{"pid":125,"tid":126,"cpu":2,"origin":3,"ktime":4,"correlation_id":5,"hotspot_identity":{"epoch":6,"state":1,"read_error":0}},"last_terminal":3,"outcome":3,"reporter_error_present":true,"proof_error_present":true,"getter_error_present":true}`

const channelDiagnosticFixture = `{"unit":"completed-receive","nil_barrier":0,"non_nil_to_handle_trace":18446744073709551615,"close_exit":2,"cancel_exit":3,"overflow":false,"retired":true,"last":{"pid":123,"tid":124,"cpu":-1,"origin":18446744073709551615,"k_time":-2,"correlation_id":9,"hotspot_identity":{"epoch":0,"state":6,"read_error":-13}}}`
const storeDiagnosticFixture = `{"unit":"store-call","inputs":36,"in_flight":8,"invalid_identity":1,"identity_validate":2,"invalid_checked":3,"mismatch":4,"new_distinct":5,"preserve_checked":6,"dedup":7,"overflow":false,"invalid":false,"last":{"id":18446744073709551615,"pid":123,"tid":124,"hotspot_identity":{"epoch":0,"state":6,"read_error":-13},"terminal":6}}`
const storeDiagnosticMaxFixture = `{"unit":"store-call","inputs":18446744073709551615,"in_flight":0,"invalid_identity":0,"identity_validate":0,"invalid_checked":0,"mismatch":0,"new_distinct":0,"preserve_checked":0,"dedup":18446744073709551615,"overflow":false,"invalid":false,"last":{"id":0,"pid":0,"tid":0,"hotspot_identity":{"epoch":0,"state":0,"read_error":0},"terminal":0}}`

const ownerQueueFixture = `{"unit":"owned-map-update-request","requests_owned":2,"queue_accepted":2,"admission_held":0,"refused":0,"applied":1,"applied_published":1,"applied_not_published":0,"failed":0,"aborted":0,"pending":0,"cleanup_failed":0,"pending_known":true,"all_workers_ended":true,"caller_joined_claim":true,"scope_joined_witness":true,"held":false,"last_unavailable":false,"last_published":true,"outcome":3,"proof_error_present":false,"last_apply_error_present":false,"last_cleanup_error_present":false}`
const ownerProcessingFixture = `{"unit":"handle-trace-input","has_binding":true,"expected_binding":{"source":1,"session":"session-123","host_tgid":1234,"birth":5678,"counter_map_id":10,"perf_map_id":20,"possible_cpus":4,"generation":1},"epoch_target":{"pid":1234,"process_start_ticks":5678,"counter_address":7,"initial_epoch":0},"observed_pid":1234,"admitted":2,"pre_reporter_rejected":0,"reporter_ok":2,"reporter_error":0,"in_flight":0,"target_bound":2,"foreign_to_binding":0,"binding_unknown":0,"overflow":false,"has_last_admitted":false,"has_last_classified":false,"last_terminal":0,"outcome":3,"reporter_error_present":false,"proof_error_present":false,"getter_error_present":false}`
const ownerStoreFixture = `{"unit":"store-call","inputs":3,"in_flight":0,"invalid_identity":0,"identity_validate":0,"invalid_checked":0,"mismatch":0,"new_distinct":3,"preserve_checked":0,"dedup":0,"overflow":false,"invalid":false,"last":{"id":18446744073709551615,"pid":0,"tid":0,"hotspot_identity":{"epoch":0,"state":0,"read_error":0},"terminal":0}}`
const ownerEnvelopeFixture = `{"schema":1,"missing":[],"queue":` + ownerQueueFixture + `,"processing":` + ownerProcessingFixture + `,"channel":` + channelDiagnosticFixture + `,"store":` + ownerStoreFixture + `}`

func pruneObservedFixture(source int) string {
	return fmt.Sprintf(`{"source":%d,"observed":true,"has_binding":true,"binding_unrepresentable":false,"binding":{"source":%d,"session":"gadget","host_tgid":4321,"birth":8765,"generation":42},"counts":{"admitted":2,"completed":1,"failed":1,"aborted":0,"skipped":0,"incomplete":0,"in_flight":0,"overflow":false},"outcome":3,"held_work":false,"observation_error_present":false}`, source, source)
}
func pruneAbsentFixture(source int) string {
	return fmt.Sprintf(`{"source":%d,"observed":false,"has_binding":false,"binding_unrepresentable":false,"binding":{"source":0,"session":"","host_tgid":0,"birth":0,"generation":0},"counts":{"admitted":0,"completed":0,"failed":0,"aborted":0,"skipped":0,"incomplete":0,"in_flight":0,"overflow":false},"outcome":0,"held_work":false,"observation_error_present":false}`, source)
}
func pruneEnvelopeFixture(prune string) string {
	return strings.TrimSuffix(ownerEnvelopeFixture, "}") + `,"prune":` + prune + `}`
}
func populatedPruneFixture() string {
	return `{"unit":"admitted-prune-callback","loop_state":"joined","book_present":true,"book_frozen":true,"rows":[` + pruneObservedFixture(5) + `,` + pruneObservedFixture(6) + `,` + pruneObservedFixture(7) + `,` + pruneObservedFixture(8) + `]}`
}

func TestRetainedOuterPruneProjectsExistingKinds(t *testing.T) {
	raw := json.RawMessage(pruneEnvelopeFixture(populatedPruneFixture()))
	d, p := retainedOwnerProjection(t, raw)
	if p.Disposition != definitionSourceUnknown || p.OwnerDiagnostic == nil || string(p.OwnerDiagnostic.Raw) != string(raw) { t.Fatal("outer packet lost or admitted") }
	for i := 0; i < 4; i++ {
		row := p.Rows[10+i]
		if row.OwnerFacts == nil || row.OwnerFacts.Prune == nil || row.OwnerFacts.Prune.Row.Source != uint64(i+5) || row.OwnerFacts.Prune.Row.Counts.Admitted != 2 || row.Enablement != definitionSourceUnknown || row.Binding != definitionSourceUnknown { t.Fatalf("prune kind %d lost or given authority", i+5) }
	}
	if p.Rows[22].OwnerFacts.Queue.QueueAccepted != 2 || p.Rows[23].OwnerFacts.Channel.NonNilToHandleTrace != math.MaxUint64 || p.Rows[24].OwnerFacts.Processing.Admitted != 2 || p.Rows[25].OwnerFacts.Store.Inputs != 3 { t.Fatal("child leaves lost") }
	if strings.Contains(strings.Join(p.Missing, ","), "prune-not-forwarded") || !strings.Contains(strings.Join(p.Missing, ","), "unknown-extra-source") { t.Fatal("prune presence or source authority misclassified") }
	raw[0] = 'x'; p.Rows[10].OwnerFacts.Prune.Row.Counts.Admitted = 99
	again := projectDefinitionSources(d)
	if again.OwnerDiagnostic.Raw[0] != '{' || again.Rows[10].OwnerFacts.Prune.Row.Counts.Admitted != 2 { t.Fatal("prune memo or raw aliases caller") }
	var summary model.Summary; _ = applyCollectionInventory(nil, nil, nil, &summary, again)
	found := false; for _, lane := range summary.UnprovenCollectionSources { if lane == accounting.Definitions { found = true } }
	if !found { t.Fatal("prune facts admitted Definitions") }
}

func TestRetainedOuterPruneMissingAndRejectingCases(t *testing.T) {
	base := populatedPruneFixture()
	for _, tc := range []struct { name, prune, reason string; row int }{
		{"one-kind-absent", strings.Replace(base, pruneObservedFixture(6), pruneAbsentFixture(6), 1), "prune-kind-not-observed", 11},
		{"held", strings.Replace(base, `"held_work":false`, `"held_work":true`, 1), "held-work", 10},
		{"overflow", strings.Replace(base, `"overflow":false`, `"overflow":true`, 1), "counter-overflow", 10},
		{"error", strings.Replace(base, `"observation_error_present":false`, `"observation_error_present":true`, 1), "local-read-failure", 10},
		{"binding-source", strings.Replace(base, `"binding":{"source":5`, `"binding":{"source":6`, 1), "binding-mismatch", 10},
		{"duplicate", strings.Replace(base, `"generation":42`, `"generation":42,"generation":42`, 1), "malformed-prune-leaf", 10},
		{"unknown", strings.Replace(base, `"held_work":false`, `"held_work":false,"extra":0`, 1), "malformed-prune-leaf", 10},
		{"null-required", strings.Replace(base, `"held_work":false`, `"held_work":null`, 1), "malformed-prune-leaf", 10},
		{"negative-counter", strings.Replace(base, `"admitted":2`, `"admitted":-1`, 1), "malformed-prune-leaf", 10},
		{"bad-loop-state", strings.Replace(base, `"loop_state":"joined"`, `"loop_state":"other"`, 1), "malformed-prune-leaf", 10},
		{"bad-enum", strings.Replace(base, `"outcome":3`, `"outcome":4`, 1), "malformed-prune-leaf", 10},
		{"short-array", strings.Replace(base, `,`+pruneObservedFixture(8), "", 1), "malformed-prune-leaf", 10},
	} {
		t.Run(tc.name, func(t *testing.T) {
			raw := pruneEnvelopeFixture(tc.prune); _, p := retainedOwnerProjection(t, json.RawMessage(raw))
			row := p.Rows[tc.row]
			if !strings.Contains(strings.Join(row.Missing, ","), tc.reason) { t.Fatalf("missing reason %s: %+v", tc.reason, row) }
			if tc.name == "one-kind-absent" { if row.Disposition != definitionSourceUnknown || row.OwnerFacts.Prune.Row.Observed { t.Fatal("absent kind treated as success") } } else if row.Disposition != definitionSourceRejected { t.Fatal("contradiction not rejected") }
			if p.OwnerDiagnostic == nil || string(p.OwnerDiagnostic.Raw) != raw || p.Rows[22].OwnerFacts == nil || p.Rows[23].OwnerFacts == nil || p.Rows[24].OwnerFacts == nil || p.Rows[25].OwnerFacts == nil { t.Fatal("raw or child leaf lost") }
		})
	}
	_, missing := retainedOwnerProjection(t, json.RawMessage(ownerEnvelopeFixture))
	for i := 10; i < 14; i++ { if missing.Rows[i].OwnerFacts != nil || !strings.Contains(strings.Join(missing.Rows[i].Missing, ","), "prune-leaf-not-forwarded") { t.Fatal("absent prune not distinguished") } }
	unrepresentable := strings.Replace(pruneObservedFixture(5), `"has_binding":true,"binding_unrepresentable":false,"binding":{"source":5,"session":"gadget","host_tgid":4321,"birth":8765,"generation":42}`, `"has_binding":false,"binding_unrepresentable":true,"binding":{"source":0,"session":"","host_tgid":0,"birth":0,"generation":0}`, 1)
	_, unrepresented := retainedOwnerProjection(t, json.RawMessage(pruneEnvelopeFixture(strings.Replace(base, pruneObservedFixture(5), unrepresentable, 1))))
	if unrepresented.Rows[10].Binding != definitionSourceUnknown || !unrepresented.Rows[10].OwnerFacts.Prune.Row.BindingUnrepresentable || !strings.Contains(strings.Join(unrepresented.Rows[10].Missing, ","), "prune-binding-unrepresentable") { t.Fatal("unrepresentable prune binding was treated as absent or valid") }
	max := strings.Replace(strings.Replace(strings.Replace(base, `"admitted":2`, `"admitted":18446744073709551615`, 1), `"completed":1`, `"completed":18446744073709551615`, 1), `"failed":1`, `"failed":0`, 1)
	_, p := retainedOwnerProjection(t, json.RawMessage(pruneEnvelopeFixture(max)))
	if p.Rows[10].OwnerFacts == nil || p.Rows[10].OwnerFacts.Prune.Row.Counts.Admitted != math.MaxUint64 || p.Rows[10].Disposition != definitionSourceUnknown { t.Fatal("maximum prune count changed") }
}

func retainedOwnerProjection(t *testing.T, raw json.RawMessage) (*definitionAccounting, definitionSourceConservation) {
	t.Helper()
	closed := make(chan struct{}); close(closed)
	o := &diagnosticTestOwner{ready: func() <-chan struct{} { return closed }, get: func() (json.RawMessage, error) { return raw, nil }}
	d := &definitionAccounting{expected: validBindingFixture()}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second); defer cancel()
	d.retainOwnerDiagnostics(ctx, o, nil)
	return d, projectDefinitionSources(d)
}

func TestRetainedOwnerEnvelopeProjectsDistinctRows(t *testing.T) {
	raw := json.RawMessage(ownerEnvelopeFixture)
	d, p := retainedOwnerProjection(t, raw)
	if p.Disposition != definitionSourceUnknown || p.Rows[22].OwnerFacts == nil || p.Rows[23].OwnerFacts == nil || p.Rows[24].OwnerFacts == nil || p.Rows[25].OwnerFacts == nil { t.Fatal("known rows lost or globally accepted") }
	if p.Rows[22].OwnerFacts.Queue.QueueAccepted != 2 || p.Rows[22].Binding != definitionSourceUnknown || p.Rows[23].OwnerFacts.Channel.NonNilToHandleTrace != math.MaxUint64 || p.Rows[24].OwnerFacts.Processing.Admitted != 2 || p.Rows[25].OwnerFacts.Store.Inputs != 3 { t.Fatal("source units or exact counts changed") }
	if !strings.Contains(strings.Join(p.Missing, ","), "unknown-extra-source") || !strings.Contains(strings.Join(p.Missing, ","), "prune-not-forwarded") { t.Fatal("unknown source or outer prune missingness lost") }
	projectDefinitionAuxiliaryRows(&p, d.expected, auxiliaryProjectionFixture(d.expected))
	if p.Rows[24].Binding == definitionSourceRejected { t.Fatal("scope generation was compared to trace generation") }
	raw[0] = 'x'; p.Rows[22].OwnerFacts.Queue.QueueAccepted = 99
	late := &diagnosticTestOwner{ready: func() <-chan struct{} { t.Fatal("terminal memo re-read readiness"); return nil }, get: func() (json.RawMessage, error) { t.Fatal("terminal memo re-read data"); return nil, nil }}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second); defer cancel()
	d.retainOwnerDiagnostics(ctx, late, nil)
	again := projectDefinitionSources(d)
	if again.Rows[22].OwnerFacts.Queue.QueueAccepted != 2 || again.OwnerDiagnostic.Raw[0] != '{' { t.Fatal("memo or retained raw bytes were mutated") }
	withoutStoreRow := again; withoutStoreRow.Rows = append([]definitionSourceRow(nil), again.Rows[:25]...)
	applyDefinitionOwnerRows(&withoutStoreRow, d.ownerDiagnostic.Load().Rows)
	if withoutStoreRow.Disposition != definitionSourceRejected || !strings.Contains(strings.Join(withoutStoreRow.Missing, ","), "owner-row-missing") { t.Fatal("missing obligation row was hidden") }
	d.expected.Generation++
	if projectDefinitionSources(d).Rows[24].Binding == definitionSourceRejected { t.Fatal("memo re-read a later expected binding") }
	var summary model.Summary; _ = applyCollectionInventory(nil, nil, nil, &summary, again)
	found := false; for _, lane := range summary.UnprovenCollectionSources { if lane == accounting.Definitions { found = true } }
	if !found { t.Fatal("owner facts admitted Definitions") }
}

func TestRetainedOwnerEnvelopeRejectsContradictions(t *testing.T) {
	for _, tc := range []struct { name, raw string; row int; reason string }{
		{"binding", strings.Replace(ownerEnvelopeFixture, `"generation":1`, `"generation":2`, 1), 24, "binding-mismatch"},
		{"channel-overflow", strings.Replace(ownerEnvelopeFixture, channelDiagnosticFixture, strings.Replace(channelDiagnosticFixture, `"overflow":false`, `"overflow":true`, 1), 1), 23, "counter-overflow"},
		{"queue-held", strings.Replace(ownerEnvelopeFixture, `"held":false`, `"held":true`, 1), 22, "held-work"},
		{"processing-read-error", strings.Replace(ownerEnvelopeFixture, `"getter_error_present":false`, `"getter_error_present":true`, 1), 24, "local-read-failure"},
		{"processing-held", strings.Replace(ownerEnvelopeFixture, ownerProcessingFixture, strings.Replace(ownerProcessingFixture, `"in_flight":0`, `"in_flight":1`, 1), 1), 24, "held-work"},
		{"queue-sum-overflow", strings.Replace(strings.Replace(strings.Replace(ownerEnvelopeFixture, `"requests_owned":2`, `"requests_owned":18446744073709551615`, 1), `"queue_accepted":2`, `"queue_accepted":18446744073709551615`, 1), `"refused":0`, `"refused":1`, 1), 22, "counter-overflow"},
		{"store-invalid", strings.Replace(ownerEnvelopeFixture, ownerStoreFixture, strings.Replace(ownerStoreFixture, `"invalid":false`, `"invalid":true`, 1), 1), 25, "local-invalid"},
		{"bad-enum", strings.Replace(ownerEnvelopeFixture, ownerProcessingFixture, strings.Replace(ownerProcessingFixture, `"outcome":3`, `"outcome":4`, 1), 1), 24, "malformed-processing-leaf"},
		{"duplicate-envelope", strings.Replace(ownerEnvelopeFixture, `"missing":[]`, `"missing":[],"missing":[]`, 1), 22, "unsupported-or-malformed-owner-envelope"},
		{"unknown-envelope", strings.Replace(ownerEnvelopeFixture, `"missing":[]`, `"missing":[],"outer_unknown":{}`, 1), 22, "unsupported-or-malformed-owner-envelope"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			_, p := retainedOwnerProjection(t, json.RawMessage(tc.raw))
			if p.Rows[tc.row].Disposition != definitionSourceRejected || !strings.Contains(strings.Join(p.Rows[tc.row].Missing, ","), tc.reason) { t.Fatalf("unexpected row: %+v", p.Rows[tc.row]) }
			if p.OwnerDiagnostic == nil || string(p.OwnerDiagnostic.Raw) != tc.raw { t.Fatal("rejected raw packet lost") }
		})
	}
	withoutQueue := strings.Replace(ownerEnvelopeFixture, `,"queue":`+ownerQueueFixture, "", 1)
	_, p := retainedOwnerProjection(t, json.RawMessage(withoutQueue))
	if p.Rows[22].OwnerFacts != nil || p.Rows[22].Missing[0] != "queue-not-forwarded" || p.Rows[24].OwnerFacts == nil { t.Fatal("missing queue conflated with other units") }
	missingQueueBinding := strings.Replace(withoutQueue, `"missing":[]`, `"missing":["queue-binding-absent"]`, 1)
	_, p = retainedOwnerProjection(t, json.RawMessage(missingQueueBinding))
	if !strings.Contains(strings.Join(p.Rows[22].Missing, ","), "queue-binding-absent") || p.Rows[22].Binding != definitionSourceUnknown { t.Fatal("queue binding absence was not retained") }
	withoutBinding := strings.Replace(ownerProcessingFixture, `,"expected_binding":{"source":1,"session":"session-123","host_tgid":1234,"birth":5678,"counter_map_id":10,"perf_map_id":20,"possible_cpus":4,"generation":1}`, "", 1)
	packet := strings.Replace(strings.Replace(ownerEnvelopeFixture, ownerProcessingFixture, withoutBinding, 1), `"missing":[]`, `"missing":["binding-unrepresentable"]`, 1)
	_, p = retainedOwnerProjection(t, json.RawMessage(packet))
	if p.Rows[24].OwnerFacts == nil || p.Rows[24].OwnerFacts.ProcessingBinding != "unrepresentable" || !strings.Contains(strings.Join(p.Rows[24].Missing, ","), "binding-unrepresentable") { t.Fatal("unrepresentable binding was treated as absent") }
}

func TestRetainedOwnerEnvelopeEachLeafOmission(t *testing.T) {
	leaves := []struct { key, source, unit, missing, fixture string }{
		{"queue", "async-queue", "owned-map-update-request", "queue-not-forwarded", ownerQueueFixture},
		{"processing", "processing", "handle-trace-input", "processing-not-forwarded", ownerProcessingFixture},
		{"channel", "channel", "completed-receive", "channel-not-forwarded", channelDiagnosticFixture},
		{"store", "store", "store-call", "store-not-forwarded", ownerStoreFixture},
	}
	for _, omitted := range leaves {
		t.Run(omitted.key, func(t *testing.T) {
			fragment := `,"` + omitted.key + `":` + omitted.fixture
			packet := strings.Replace(ownerEnvelopeFixture, fragment, "", 1)
			if packet == ownerEnvelopeFixture { t.Fatal("fixture leaf was not removed") }
			raw := json.RawMessage(packet)
			d, p := retainedOwnerProjection(t, raw)
			if len(p.Rows) != 26 || p.Disposition != definitionSourceUnknown || p.OwnerDiagnostic == nil || string(p.OwnerDiagnostic.Raw) != packet { t.Fatal("obligations, raw packet or unknown disposition changed") }
			if !strings.Contains(strings.Join(p.Missing, ","), "enabled-source-authority-absent") || !strings.Contains(strings.Join(p.Missing, ","), "unknown-extra-source") { t.Fatal("source authority was inferred") }
			names := make(map[string]int)
			for _, row := range p.Rows {
				names[row.Source]++
				if row.Enablement != definitionSourceUnknown || row.Upstream != definitionSourceUnknown || row.Next != definitionSourceUnknown { t.Fatalf("authority invented for %s", row.Source) }
			}
			for _, name := range []string{"selected", "native-trace", "native-report-pid", "native-report-output", "pid-mailbox", "pid-processing", "scope", "metrics", "pid-cleanup", "interpreter-metrics", "prune-cycle", "prune-scan-otel", "prune-scan-symtab", "prune-scan-debuginfod", "kobject-receive", "module-queued", "module-coalesced", "symbol-queued", "symbol-coalesced", "module-exec", "symbol-exec", "report-reload", "async-queue", "channel", "processing", "store"} {
				if names[name] != 1 { t.Fatalf("obligation %s missing or duplicated", name) }
			}
			for _, leaf := range leaves {
				var row *definitionSourceRow
				for i := range p.Rows { if p.Rows[i].Source == leaf.source { row = &p.Rows[i]; break } }
				if row == nil || row.Unit != leaf.unit { t.Fatalf("missing obligation %s", leaf.source) }
				if leaf.key == omitted.key {
					if row.OwnerFacts != nil || row.Disposition != definitionSourceUnknown || !strings.Contains(strings.Join(row.Missing, ","), leaf.missing) { t.Fatalf("omitted row %s: %+v", leaf.source, row) }
					continue
				}
				if row.OwnerFacts == nil { t.Fatalf("supplied %s was dropped", leaf.source) }
				switch leaf.key {
				case "queue": if row.OwnerFacts.Queue == nil || row.OwnerFacts.Queue.QueueAccepted != 2 || row.Binding != definitionSourceUnknown { t.Fatal("queue facts or binding changed") }
				case "processing": if row.OwnerFacts.Processing == nil || row.OwnerFacts.Processing.Admitted != 2 || row.OwnerFacts.ProcessingBinding != "represented" { t.Fatal("processing facts or binding changed") }
				case "channel": if row.OwnerFacts.Channel == nil || row.OwnerFacts.Channel.NonNilToHandleTrace != math.MaxUint64 { t.Fatal("channel facts changed") }
				case "store": if row.OwnerFacts.Store == nil || row.OwnerFacts.Store.Inputs != 3 { t.Fatal("store facts changed") }
				}
			}
			raw[0] = 'x'
			if string(projectDefinitionSources(d).OwnerDiagnostic.Raw) != packet { t.Fatal("retained packet aliases caller") }
			var summary model.Summary; _ = applyCollectionInventory(nil, nil, nil, &summary, p)
			for _, required := range []accounting.Lane{accounting.Definitions, accounting.Wire, accounting.Retention} {
				found := false; for _, lane := range summary.UnprovenCollectionSources { if lane == required { found = true } }
				if !found { t.Fatalf("%s became proven", required) }
			}
		})
	}
}

func TestRetainedOwnerEnvelopeDecodeCannotOutliveBudget(t *testing.T) {
	start := time.Unix(100, 0)
	ctx := &diagnosticOpenDeadlineContext{deadline: start.Add(time.Second), done: make(chan struct{})}
	closed := make(chan struct{}); close(closed)
	o := &diagnosticTestOwner{ready: func() <-chan struct{} { return closed }, get: func() (json.RawMessage, error) { return json.RawMessage(ownerEnvelopeFixture), nil }}
	d := &definitionAccounting{expected: validBindingFixture()}; checks := 0
	d.retainOwnerDiagnosticsAt(ctx, o, nil, func() time.Time { checks++; if checks >= 10 { return start.Add(2*time.Second) }; return start })
	got := d.ownerDiagnostic.Load()
	if got == nil || got.Reason != "budget-expired" || len(got.Raw) != 0 || got.Rows != nil { t.Fatal("late typed memo escaped original budget") }
}

func TestDecodeChannelStoreDiagnosticsKeepUnitsAndFacts(t *testing.T) {
	channelRaw, storeRaw := json.RawMessage(channelDiagnosticFixture), json.RawMessage(storeDiagnosticFixture)
	channel, reason := decodeChannelDiagnostic(channelRaw)
	if reason != queueDiagnosticDecoded || channel == nil || channel.Unit != "completed-receive" || channel.NonNilToHandleTrace != math.MaxUint64 || channel.NilBarrier != 0 || channel.CloseExit != 2 || channel.CancelExit != 3 || !channel.Retired || channel.Overflow || channel.LocalReason != ownerLeafUnknown { t.Fatal("channel facts changed") }
	if channel.Last.CPU != -1 || channel.Last.KTime != -2 || channel.Last.Origin != math.MaxUint64 || channel.Last.HotSpotIdentity.Epoch != 0 || channel.Last.HotSpotIdentity.State != 6 || channel.Last.HotSpotIdentity.ReadError != -13 { t.Fatal("channel last identity changed") }
	store, reason := decodeStoreDiagnostic(storeRaw)
	if reason != queueDiagnosticDecoded || store == nil || store.Unit != "store-call" || store.Inputs != 36 || store.InFlight != 8 || store.InvalidIdentity != 1 || store.IdentityValidate != 2 || store.InvalidChecked != 3 || store.Mismatch != 4 || store.NewDistinct != 5 || store.PreserveChecked != 6 || store.Dedup != 7 || store.LocalReason != ownerLeafUnknown { t.Fatal("store terminal order or local equation changed") }
	if store.Last.ID != math.MaxUint64 || store.Last.Terminal != 6 || store.Last.HotSpotIdentity.State != 6 { t.Fatal("store last identity changed") }
	maxStore, reason := decodeStoreDiagnostic(json.RawMessage(storeDiagnosticMaxFixture))
	if reason != queueDiagnosticDecoded || maxStore.Inputs != math.MaxUint64 || maxStore.Dedup != math.MaxUint64 || maxStore.LocalReason != ownerLeafUnknown || maxStore.Last.ID != 0 { t.Fatal("maximum counter or zero last facts changed") }
	overflow, reason := decodeStoreDiagnostic(json.RawMessage(strings.Replace(storeDiagnosticMaxFixture, `"in_flight":0`, `"in_flight":1`, 1)))
	if reason != queueDiagnosticDecoded || overflow.LocalReason != ownerLeafOverflow { t.Fatal("checked local sum overflow lost") }
	channelRaw[0], storeRaw[0] = 'x', 'x'
	if channel.Raw[0] != '{' || store.Raw[0] != '{' { t.Fatal("raw bytes alias caller") }
	for _, terminal := range []string{"0", "1", "2", "3", "4", "5", "6"} {
		packet := strings.Replace(storeDiagnosticFixture, `"terminal":6`, `"terminal":`+terminal, 1)
		if _, reason := decodeStoreDiagnostic(json.RawMessage(packet)); reason != queueDiagnosticDecoded { t.Fatalf("terminal %s rejected", terminal) }
	}
}

func TestDecodeChannelStoreDiagnosticsRejectMalformedAndKeepLocalFailures(t *testing.T) {
	for _, tc := range []struct { name, packet string; channel bool }{
		{"channel-missing-zero", strings.Replace(channelDiagnosticFixture, `,"nil_barrier":0`, "", 1), true},
		{"channel-null", strings.Replace(channelDiagnosticFixture, `"retired":true`, `"retired":null`, 1), true},
		{"channel-bool-type", strings.Replace(channelDiagnosticFixture, `"retired":true`, `"retired":"true"`, 1), true},
		{"channel-duplicate", strings.Replace(channelDiagnosticFixture, `"nil_barrier":0`, `"nil_barrier":0,"nil_barrier":0`, 1), true},
		{"channel-nested-unknown", strings.Replace(channelDiagnosticFixture, `"k_time":-2`, `"k_time":-2,"ktime":-2`, 1), true},
		{"channel-identity", strings.Replace(channelDiagnosticFixture, `"state":6`, `"state":7`, 1), true},
		{"channel-negative", strings.Replace(channelDiagnosticFixture, `"nil_barrier":0`, `"nil_barrier":-1`, 1), true},
		{"channel-overflow-number", strings.Replace(channelDiagnosticFixture, `"nil_barrier":0`, `"nil_barrier":18446744073709551616`, 1), true},
		{"store-missing-false", strings.Replace(storeDiagnosticFixture, `,"invalid":false`, "", 1), false},
		{"store-null", strings.Replace(storeDiagnosticFixture, `"inputs":36`, `"inputs":null`, 1), false},
		{"store-string-number", strings.Replace(storeDiagnosticFixture, `"inputs":36`, `"inputs":"36"`, 1), false},
		{"store-nested-duplicate", strings.Replace(storeDiagnosticFixture, `"terminal":6`, `"terminal":6,"terminal":6`, 1), false},
		{"store-terminal-array", strings.Replace(storeDiagnosticFixture, `"terminal":6`, `"terminal":[0,1,2,3,4,5,6]`, 1), false},
		{"store-extra-array", strings.Replace(storeDiagnosticFixture, `"inputs":36`, `"inputs":36,"terminals":[0,1,2,3,4,5,6]`, 1), false},
		{"store-terminal-range", strings.Replace(storeDiagnosticFixture, `"terminal":6`, `"terminal":7`, 1), false},
		{"store-identity-range", strings.Replace(storeDiagnosticFixture, `"state":6`, `"state":7`, 1), false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if tc.channel { if got, reason := decodeChannelDiagnostic(json.RawMessage(tc.packet)); got != nil || reason != queueDiagnosticMalformed { t.Fatal("malformed channel accepted") } } else { if got, reason := decodeStoreDiagnostic(json.RawMessage(tc.packet)); got != nil || reason != queueDiagnosticMalformed { t.Fatal("malformed store accepted") } }
		})
	}
	channel, reason := decodeChannelDiagnostic(json.RawMessage(strings.Replace(channelDiagnosticFixture, `"overflow":false`, `"overflow":true`, 1)))
	if reason != queueDiagnosticDecoded || channel.LocalReason != ownerLeafOverflow || !channel.Overflow { t.Fatal("channel overflow fact lost") }
	for _, tc := range []struct { packet string; want ownerLeafLocalReason }{
		{strings.Replace(storeDiagnosticFixture, `"overflow":false`, `"overflow":true`, 1), ownerLeafOverflow},
		{strings.Replace(storeDiagnosticFixture, `"invalid":false`, `"invalid":true`, 1), ownerLeafInvalid},
		{strings.Replace(storeDiagnosticFixture, `"inputs":36`, `"inputs":35`, 1), ownerLeafEquationMismatch},
	} {
		got, reason := decodeStoreDiagnostic(json.RawMessage(tc.packet))
		if reason != queueDiagnosticDecoded || got.LocalReason != tc.want { t.Fatal("store local failure not retained") }
	}
	if _, reason := decodeChannelDiagnostic(nil); reason != queueDiagnosticAbsent { t.Fatal("absent channel lost") }
	if _, reason := decodeStoreDiagnostic(json.RawMessage(strings.Repeat(" ", 32769))); reason != queueDiagnosticOversize { t.Fatal("store size bound lost") }
}

func TestDecodeProcessingDiagnosticPreservesProducerFacts(t *testing.T) {
	raw := json.RawMessage(processingDiagnosticFixture)
	got, reason := decodeProcessingDiagnostic(raw)
	if reason != queueDiagnosticDecoded || got == nil { t.Fatalf("processing rejected: %v", reason) }
	if got.BindingState != processingBindingRepresented || got.ExpectedBinding == nil || got.ExpectedBinding.Birth != math.MaxUint64 || got.ExpectedBinding.PossibleCPUs != 2 || got.EpochTarget.ProcessStartTicks != math.MaxUint64 || got.Admitted != math.MaxUint64 { t.Fatal("binding or unsigned facts changed") }
	if got.PreReporterRejected != 1 || got.ReporterOK != 2 || got.ReporterError != 3 || got.InFlight != 0 || got.TargetBound != 4 || got.ForeignToBinding != 5 || got.BindingUnknown != 6 || got.Overflow || !got.ProofErrorPresent || !got.GetterErrorPresent || !got.ReporterErrorPresent { t.Fatal("processing counters or error presence changed") }
	if got.LastAdmitted == nil || got.LastAdmitted.CPU != -1 || got.LastAdmitted.KTime != -2 || got.LastAdmitted.Origin != math.MaxUint64 || got.LastAdmitted.HotSpotIdentity.Epoch != 0 || got.LastAdmitted.HotSpotIdentity.State != 6 || got.LastAdmitted.HotSpotIdentity.ReadError != -13 || got.LastClassified == nil || got.LastTerminal != 3 || got.Outcome != 3 { t.Fatal("last trace facts changed") }
	raw[0] = 'x'
	if got.Raw[0] != '{' { t.Fatal("processing raw bytes alias caller") }
	without := strings.Replace(processingDiagnosticFixture, processingBindingFixture, "", 1)
	unrepresentable, reason := decodeProcessingDiagnostic(json.RawMessage(without))
	if reason != queueDiagnosticDecoded || unrepresentable.BindingState != processingBindingUnrepresentable || !unrepresentable.HasBinding || unrepresentable.ExpectedBinding != nil { t.Fatal("unrepresentable binding was lost") }
	absent, reason := decodeProcessingDiagnostic(json.RawMessage(strings.Replace(without, `"has_binding":true`, `"has_binding":false`, 1)))
	if reason != queueDiagnosticDecoded || absent.BindingState != processingBindingAbsent || absent.HasBinding { t.Fatal("absent binding conflated with unrepresentable") }
	for _, value := range []string{"1", "2", "3"} {
		packet := strings.Replace(processingDiagnosticFixture, `"outcome":3`, `"outcome":`+value, 1)
		if _, reason := decodeProcessingDiagnostic(json.RawMessage(packet)); reason != queueDiagnosticDecoded { t.Fatalf("outcome %s rejected", value) }
	}
	for _, value := range []string{"0", "1", "2", "3"} {
		packet := strings.Replace(processingDiagnosticFixture, `"last_terminal":3`, `"last_terminal":`+value, 1)
		if _, reason := decodeProcessingDiagnostic(json.RawMessage(packet)); reason != queueDiagnosticDecoded { t.Fatalf("terminal %s rejected", value) }
	}
}

func TestDecodeProcessingDiagnosticRejectsMalformedLeaves(t *testing.T) {
	for _, tc := range []struct { name, packet string }{
		{"missing-zero", strings.Replace(processingDiagnosticFixture, `,"in_flight":0`, "", 1)},
		{"missing-false", strings.Replace(processingDiagnosticFixture, `,"overflow":false`, "", 1)},
		{"null", strings.Replace(processingDiagnosticFixture, `"in_flight":0`, `"in_flight":null`, 1)},
		{"nested-required", strings.Replace(processingDiagnosticFixture, `,"possible_cpus":2`, "", 1)},
		{"duplicate", strings.Replace(processingDiagnosticFixture, `"outcome":3`, `"outcome":3,"outcome":3`, 1)},
		{"nested-duplicate", strings.Replace(processingDiagnosticFixture, `"generation":9`, `"generation":9,"generation":9`, 1)},
		{"unknown", strings.Replace(processingDiagnosticFixture, `"outcome":3`, `"outcome":3,"extra":0`, 1)},
		{"nested-unknown", strings.Replace(processingDiagnosticFixture, `"read_error":-13`, `"read_error":-13,"extra":0`, 1)},
		{"string-number", strings.Replace(processingDiagnosticFixture, `"in_flight":0`, `"in_flight":"0"`, 1)},
		{"string-bool", strings.Replace(processingDiagnosticFixture, `"overflow":false`, `"overflow":"false"`, 1)},
		{"negative-unsigned", strings.Replace(processingDiagnosticFixture, `"in_flight":0`, `"in_flight":-1`, 1)},
		{"unsigned-overflow", strings.Replace(processingDiagnosticFixture, `"in_flight":0`, `"in_flight":18446744073709551616`, 1)},
		{"pid-overflow", strings.Replace(processingDiagnosticFixture, `"host_tgid":123`, `"host_tgid":4294967296`, 1)},
		{"negative-cpus", strings.Replace(processingDiagnosticFixture, `"possible_cpus":2`, `"possible_cpus":-1`, 1)},
		{"long-session", strings.Replace(processingDiagnosticFixture, `"session":"session"`, `"session":"`+strings.Repeat("x", 257)+`"`, 1)},
		{"invalid-session-utf8", strings.Replace(processingDiagnosticFixture, `"session":"session"`, `"session":"`+string([]byte{0xff})+`"`, 1)},
		{"outcome-zero", strings.Replace(processingDiagnosticFixture, `"outcome":3`, `"outcome":0`, 1)},
		{"outcome-four", strings.Replace(processingDiagnosticFixture, `"outcome":3`, `"outcome":4`, 1)},
		{"outcome-over-byte", strings.Replace(processingDiagnosticFixture, `"outcome":3`, `"outcome":256`, 1)},
		{"terminal-four", strings.Replace(processingDiagnosticFixture, `"last_terminal":3`, `"last_terminal":4`, 1)},
		{"identity-state", strings.Replace(processingDiagnosticFixture, `"state":6`, `"state":7`, 1)},
		{"last-presence", strings.Replace(processingDiagnosticFixture, `"has_last_admitted":true`, `"has_last_admitted":false`, 1)},
		{"binding-presence", strings.Replace(processingDiagnosticFixture, `"has_binding":true`, `"has_binding":false`, 1)},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got, reason := decodeProcessingDiagnostic(json.RawMessage(tc.packet)); got != nil || reason != queueDiagnosticMalformed { t.Fatalf("malformed processing accepted: %v", reason) }
		})
	}
	if _, reason := decodeProcessingDiagnostic(nil); reason != queueDiagnosticAbsent { t.Fatal("absent processing was not distinguished") }
	if _, reason := decodeProcessingDiagnostic(json.RawMessage(strings.Repeat(" ", 32769))); reason != queueDiagnosticOversize { t.Fatal("oversized processing was not distinguished") }
}

func TestDecodeQueueDiagnosticPreservesRawProducerFacts(t *testing.T) {
	raw := json.RawMessage(queueDiagnosticFixture)
	got, reason := decodeQueueDiagnostic(raw)
	if reason != queueDiagnosticDecoded || got == nil { t.Fatalf("queue rejected: %v", reason) }
	if got.Unit != "owned-map-update-request" || got.RequestsOwned != math.MaxUint64 || got.QueueAccepted != 2 || got.AdmissionHeld != 3 || got.Refused != 4 || got.Applied != 5 || got.AppliedPublished != 6 || got.AppliedNotPublished != 7 || got.Failed != 8 || got.Aborted != 9 || got.Pending != 0 || got.CleanupFailed != 11 { t.Fatal("queue counters changed") }
	if got.PendingKnown || !got.AllWorkersEnded || got.CallerJoinedClaim || !got.ScopeJoinedWitness || got.Held || !got.LastUnavailable || got.LastPublished || got.Outcome != 1 || got.ProofErrorPresent || !got.LastApplyErrorPresent || got.LastCleanupErrorPresent { t.Fatal("queue flags changed") }
	raw[0] = 'x'
	if got.Raw[0] != '{' { t.Fatal("queue raw bytes alias caller") }
	for _, outcome := range []string{"2", "3"} {
		packet := strings.Replace(queueDiagnosticFixture, `"outcome":1`, `"outcome":`+outcome, 1)
		if _, reason := decodeQueueDiagnostic(json.RawMessage(packet)); reason != queueDiagnosticDecoded { t.Fatalf("outcome %s rejected", outcome) }
	}
}

func TestDecodeQueueDiagnosticRejectsMalformedLeaves(t *testing.T) {
	for _, tc := range []struct { name, packet string }{
		{"missing-zero", strings.Replace(queueDiagnosticFixture, `,"pending":0`, "", 1)},
		{"missing-false", strings.Replace(queueDiagnosticFixture, `,"pending_known":false`, "", 1)},
		{"null", strings.Replace(queueDiagnosticFixture, `"pending":0`, `"pending":null`, 1)},
		{"unknown", strings.Replace(queueDiagnosticFixture, `"outcome":1`, `"outcome":1,"binding":{}`, 1)},
		{"duplicate", strings.Replace(queueDiagnosticFixture, `"outcome":1`, `"outcome":1,"outcome":1`, 1)},
		{"string-number", strings.Replace(queueDiagnosticFixture, `"pending":0`, `"pending":"0"`, 1)},
		{"negative", strings.Replace(queueDiagnosticFixture, `"pending":0`, `"pending":-1`, 1)},
		{"overflow", strings.Replace(queueDiagnosticFixture, `"pending":0`, `"pending":18446744073709551616`, 1)},
		{"outcome-zero", strings.Replace(queueDiagnosticFixture, `"outcome":1`, `"outcome":0`, 1)},
		{"outcome-four", strings.Replace(queueDiagnosticFixture, `"outcome":1`, `"outcome":4`, 1)},
		{"outcome-over-byte", strings.Replace(queueDiagnosticFixture, `"outcome":1`, `"outcome":256`, 1)},
		{"trailing", queueDiagnosticFixture + `{}`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			if got, reason := decodeQueueDiagnostic(json.RawMessage(tc.packet)); got != nil || reason != queueDiagnosticMalformed { t.Fatalf("malformed queue accepted: %v", reason) }
		})
	}
	if _, reason := decodeQueueDiagnostic(nil); reason != queueDiagnosticAbsent { t.Fatal("absent queue was not distinguished") }
	if _, reason := decodeQueueDiagnostic(json.RawMessage(strings.Repeat(" ", 32769))); reason != queueDiagnosticOversize { t.Fatal("oversized queue was not distinguished") }
}

func (hostileProjectionError) Error() string { panic("error callback invoked") }
func (hostileProjectionError) Unwrap() error { panic("unwrap callback invoked") }

type diagnosticTestOwner struct {
	*callbackTestOwner
	ready func() <-chan struct{}
	get func() (json.RawMessage, error)
}

func (o *diagnosticTestOwner) DefinitionSourceDiagnosticsReady() <-chan struct{} { return o.ready() }
func (o *diagnosticTestOwner) DefinitionSourceDiagnostics() (json.RawMessage, error) { return o.get() }

type diagnosticHostileError struct{ called *bool }
func (e diagnosticHostileError) Error() string { *e.called = true; return "hostile" }
func (e diagnosticHostileError) Unwrap() error { *e.called = true; return nil }

type diagnosticOpenDeadlineContext struct {
	deadline time.Time
	done <-chan struct{}
	deadlineReads int
}
func (c *diagnosticOpenDeadlineContext) Deadline() (time.Time, bool) { c.deadlineReads++; return c.deadline, true }
func (c *diagnosticOpenDeadlineContext) Done() <-chan struct{} { return c.done }
func (c *diagnosticOpenDeadlineContext) Err() error { return nil }
func (c *diagnosticOpenDeadlineContext) Value(any) any { return nil }

func TestOwnerDiagnosticAbsoluteDeadlineAfterNativeFailure(t *testing.T) {
	b := validBindingFixture(); trace := validObservationFixture(b); operations := []string{}
	stopped := symbolizer.DefinitionStopObservation{TraceDefinition: &trace}
	o := &diagnosticTestOwner{callbackTestOwner: &callbackTestOwner{observation: stopped, combined: &stopped, combinedErr: unix.EPERM, operations: &operations}}
	calls := 0
	o.ready = func() <-chan struct{} { calls++; return make(chan struct{}) }
	o.get = func() (json.RawMessage, error) { calls++; return json.RawMessage(`{"schema":1}`), nil }
	ctx := &diagnosticOpenDeadlineContext{deadline: time.Now().Add(-time.Second), done: make(chan struct{})}
	d := &definitionAccounting{expected: b}; local := &definitionLocalRun{}
	err := newDefinitionAfterReadout(context.Background(), func() error { return nil }, func() error { return nil }, o, &d, local, func() error { return nil })(ctx)
	if !errors.Is(err, unix.EPERM) || err != d.terminalErr || calls != 0 { t.Fatal("expired metadata changed or blocked native result") }
	if got := d.ownerDiagnostic.Load(); got == nil || got.Reason != "budget-expired" || len(got.Raw) != 0 { t.Fatal("past absolute deadline was ignored") }
	if ctx.deadlineReads != 1 { t.Fatal("original deadline was not captured once") }
}

func TestOwnerDiagnosticAbsoluteDeadlineAtPhaseCuts(t *testing.T) {
	for _, expireAt := range []string{"ready", "getter", "never"} {
		t.Run(expireAt, func(t *testing.T) {
			clock := time.Unix(100, 0)
			ctx := &diagnosticOpenDeadlineContext{deadline: clock.Add(time.Second), done: make(chan struct{})}
			closed := make(chan struct{}); close(closed)
			readyCalls, gets := 0, 0
			o := &diagnosticTestOwner{}
			o.ready = func() <-chan struct{} { readyCalls++; if expireAt == "ready" && readyCalls == 2 { clock = clock.Add(2*time.Second) }; return closed }
			o.get = func() (json.RawMessage, error) { gets++; if expireAt == "getter" { clock = clock.Add(2*time.Second) }; return json.RawMessage(`{"schema":1}`), nil }
			d := &definitionAccounting{}
			d.retainOwnerDiagnosticsAt(ctx, o, nil, func() time.Time { return clock })
			got := d.ownerDiagnostic.Load()
			if ctx.deadlineReads != 1 { t.Fatal("deadline was refreshed") }
			if expireAt == "never" {
				if got.State != "captured" || gets != 1 { t.Fatal("open original budget did not capture") }
			} else if got.Reason != "budget-expired" || len(got.Raw) != 0 || (expireAt == "ready" && gets != 0) {
				t.Fatal("expired absolute budget admitted or started cached data")
			}
		})
	}
}

func TestOwnerDiagnosticAfterReadoutPreservesNativeFailure(t *testing.T) {
	b := validBindingFixture()
	trace := validObservationFixture(b)
	operations := []string{}
	closed := make(chan struct{}); close(closed)
	raw := json.RawMessage(`{"schema":1,"count":18446744073709551615}`)
	stopped := symbolizer.DefinitionStopObservation{TraceDefinition: &trace}
	o := &diagnosticTestOwner{callbackTestOwner: &callbackTestOwner{observation: stopped, combined: &stopped, combinedErr: unix.EPERM, operations: &operations}}
	o.ready = func() <-chan struct{} { operations = append(operations, "ready"); return closed }
	o.get = func() (json.RawMessage, error) { operations = append(operations, "get"); return raw, nil }
	d := &definitionAccounting{expected: b}; local := &definitionLocalRun{}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second); defer cancel()
	err := newDefinitionAfterReadout(context.Background(), func() error { return nil }, func() error { return nil }, o, &d, local, func() error { return nil })(ctx)
	if !errors.Is(err, unix.EPERM) || err != d.terminalErr { t.Fatal("native failure replaced") }
	if len(operations) < 5 || operations[0] != "drain" || operations[1] != "stop" || operations[2] != "combined" || operations[3] != "ready" { t.Fatalf("metadata preceded native phase: %v", operations) }
	p := projectDefinitionSources(d)
	if p.OwnerDiagnostic == nil || p.OwnerDiagnostic.State != "captured" || string(p.OwnerDiagnostic.Raw) != string(raw) || p.Disposition != definitionSourceRejected { t.Fatal("failed native result lost detached diagnostic") }
	raw[0] = 'x'
	if p.OwnerDiagnostic.Raw[0] != '{' || local.closeMissing().OwnerDiagnostic.Raw[0] != '{' { t.Fatal("raw packet was not copied") }
	var summary model.Summary
	_ = applyCollectionInventory(nil, nil, nil, &summary, p)
	found := false; for _, lane := range summary.UnprovenCollectionSources { if lane == accounting.Definitions { found = true } }
	if !found { t.Fatal("raw diagnostics admitted Definitions") }
}

func TestOwnerDiagnosticAfterReadoutUsesOriginalBudget(t *testing.T) {
	b := validBindingFixture(); trace := validObservationFixture(b); operations := []string{}
	closed := make(chan struct{}); close(closed)
	ctx, cancel := context.WithTimeout(context.Background(), time.Second); defer cancel()
	stopped := symbolizer.DefinitionStopObservation{TraceDefinition: &trace}
	o := &diagnosticTestOwner{callbackTestOwner: &callbackTestOwner{observation: stopped, combined: &stopped, combinedErr: unix.EPERM, operations: &operations}}
	o.ready = func() <-chan struct{} { return closed }
	o.get = func() (json.RawMessage, error) { cancel(); return json.RawMessage(`{"schema":1}`), nil }
	d := &definitionAccounting{expected: b}; local := &definitionLocalRun{}
	err := newDefinitionAfterReadout(context.Background(), func() error { return nil }, func() error { return nil }, o, &d, local, func() error { return nil })(ctx)
	if !errors.Is(err, unix.EPERM) || err != d.terminalErr { t.Fatal("metadata timeout replaced native result") }
	if got := local.closeMissing().OwnerDiagnostic; got == nil || got.Reason != "budget-expired" || len(got.Raw) != 0 { t.Fatal("original budget was renewed") }
}

func TestOwnerDiagnosticEarlyFailureDoesNotInvokeCapability(t *testing.T) {
	b := validBindingFixture(); operations := []string{}; calls := 0
	o := &diagnosticTestOwner{callbackTestOwner: &callbackTestOwner{operations: &operations}}
	o.ready = func() <-chan struct{} { calls++; return nil }
	o.get = func() (json.RawMessage, error) { calls++; return nil, nil }
	owned, cancelOwned := context.WithCancel(context.Background()); cancelOwned()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second); defer cancel()
	d := &definitionAccounting{expected: b}; local := &definitionLocalRun{}
	_ = newDefinitionAfterReadout(owned, func() error { return nil }, func() error { return nil }, o, &d, local, func() error { return nil })(ctx)
	if calls != 0 || d.ownerDiagnostic.Load() != nil { t.Fatal("early failure started diagnostics") }
}

func TestOwnerDiagnosticReadinessPhasesAndFrozenPending(t *testing.T) {
	first := make(chan struct{}); close(first)
	second := make(chan struct{}); entered := make(chan struct{})
	calls, gets := 0, 0
	o := &diagnosticTestOwner{}
	o.ready = func() <-chan struct{} {
		calls++
		if calls == 1 { return first }
		if calls == 3 { close(entered) }
		return second
	}
	o.get = func() (json.RawMessage, error) { gets++; return json.RawMessage(`{"schema":1}`), nil }
	d := &definitionAccounting{}; local := &definitionLocalRun{}
	ctx, cancel := context.WithTimeout(context.Background(), time.Second); defer cancel()
	done := make(chan struct{})
	go func() { d.retainOwnerDiagnostics(ctx, o, local); close(done) }()
	<-entered
	frozen := local.closeMissing()
	if frozen.OwnerDiagnostic == nil || frozen.OwnerDiagnostic.State != "pending" { t.Fatal("pending phase became terminal missing") }
	close(second); <-done
	if got := d.ownerDiagnostic.Load(); got == nil || got.State != "captured" || gets != 1 { t.Fatal("next readiness phase was not captured") }
	if local.closeMissing().OwnerDiagnostic.State != "pending" { t.Fatal("early snapshot was upgraded") }
}

func TestOwnerDiagnosticUnknownAndBudgetCuts(t *testing.T) {
	closed := make(chan struct{}); close(closed)
	for _, tc := range []struct { name string; raw json.RawMessage; fail bool }{
		{"empty", nil, false}, {"provider-error", nil, true}, {"bad-schema", json.RawMessage(`{"schema":2}`), false},
		{"quoted-schema", json.RawMessage(`{"schema":"1"}`), false}, {"invalid-utf8", json.RawMessage{'{', '"', 'x', '"', ':', '"', 0xff, '"', ',', '"', 's', 'c', 'h', 'e', 'm', 'a', '"', ':', '1', '}'}, false},
		{"duplicate-schema", json.RawMessage(`{"schema":1,"schema":1}`), false}, {"oversize", json.RawMessage(`{"schema":1,"x":"` + strings.Repeat("x", 32768) + `"}`), false},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls, gets, hostileCalled := 0, 0, false
			o := &diagnosticTestOwner{}
			o.ready = func() <-chan struct{} { calls++; return closed }
			o.get = func() (json.RawMessage, error) { gets++; if tc.fail { return nil, diagnosticHostileError{called: &hostileCalled} }; return tc.raw, nil }
			d := &definitionAccounting{}; ctx, cancel := context.WithTimeout(context.Background(), time.Second); defer cancel()
			d.retainOwnerDiagnostics(ctx, o, nil)
			if d.ownerDiagnostic.Load().State != "unknown" || calls > 2 || gets != 1 || hostileCalled { t.Fatal("closed phase spun or error was inspected") }
		})
	}
	t.Run("expired-before-ready", func(t *testing.T) {
		calls := 0; o := &diagnosticTestOwner{ready: func() <-chan struct{} { calls++; return closed }, get: func() (json.RawMessage, error) { calls++; return nil, nil }}
		ctx, cancel := context.WithTimeout(context.Background(), time.Second); cancel()
		d := &definitionAccounting{}; d.retainOwnerDiagnostics(ctx, o, nil)
		if calls != 0 || d.ownerDiagnostic.Load().Reason != "budget-expired" { t.Fatal("expired budget started optional work") }
	})
	t.Run("expired-during-copy", func(t *testing.T) {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second); defer cancel()
		o := &diagnosticTestOwner{ready: func() <-chan struct{} { return closed }, get: func() (json.RawMessage, error) { cancel(); return json.RawMessage(`{"schema":1}`), nil }}
		d := &definitionAccounting{}; d.retainOwnerDiagnostics(ctx, o, nil)
		if got := d.ownerDiagnostic.Load(); got.Reason != "budget-expired" || len(got.Raw) != 0 { t.Fatal("late data escaped the original budget") }
		d.retainOwnerDiagnostics(context.Background(), o, nil)
		if d.ownerDiagnostic.Load().Reason != "budget-expired" { t.Fatal("terminal diagnostic was overwritten") }
	})
}

type trackedProjectionError struct { errorCalled, unwrapCalled bool }
func (e *trackedProjectionError) Error() string { e.errorCalled = true; return "hostile" }
func (e *trackedProjectionError) Unwrap() error { e.unwrapCalled = true; return nil }

func TestProjectionPublishNeverCallsOpaqueErrors(t *testing.T) {
	e := &trackedProjectionError{}
	p := projectDefinitionSources(&definitionAccounting{expected: validBindingFixture(), terminalErr: e})
	var summary model.Summary
	publishDefinitionSources(&summary, p)
	if e.errorCalled || e.unwrapCalled || !p.TerminalErrorPresent { t.Fatal("opaque terminal error was inspected or lost") }
}

func auxiliaryProjectionFixture(b libpf.TraceDefinitionBinding) definitionSourceAuxiliaryFacts {
	s := libpf.AuxiliaryBinding{Source: libpf.AuxiliarySourcePeriodicCaller, Session: b.Session, HostTGID: b.HostTGID, Birth: b.Birth, Generation: b.Generation + 17}
	f := definitionSourceAuxiliaryFacts{ScopePresent: true, KindsPresent: true, MailboxPresent: true, ProcessPresent: true}
	f.Scope = periodiccaller.ScopeLocalSnapshot{State: libpf.LocalCopied, Observation: libpf.AuxiliaryObservation{Binding: s, Counts: libpf.AuxiliaryCounts{Admitted: 3}, Outcome: libpf.SourceLocalNonfinal}}
	f.Kinds.State = libpf.LocalCopied
	for i := range f.Kinds.Kinds {
		binding := s
		binding.Source = libpf.AuxiliarySource(i + 2)
		f.Kinds.Kinds[i] = periodiccaller.KindLocalSnapshot{Source: binding.Source, Present: true, Binding: binding, Counts: libpf.KindLedger{Admitted: uint64(i + 1)}, Outcome: libpf.SourceLocalNonfinal}
	}
	f.Mailbox = tracer.PIDMailboxLocalSnapshot{State: libpf.LocalCopied, ExpectedBinding: tracer.PIDMailboxBinding{Session: s.Session, HostTGID: s.HostTGID, Birth: s.Birth, Generation: s.Generation, MapID: 77}, Counts: tracer.PIDMailboxCounts{Admitted: 4, TargetAdmitted: 3, ForeignAdmitted: 1}, InfoReturned: true, InfoID: 77, InfoType: uint32(cebpf.Hash), InfoKeySize: 8, InfoValueSize: 1, InfoMaxEntries: 100, LifetimeReturned: true, LifetimeSession: s.Session, LifetimeTGID: s.HostTGID, LifetimeBirth: s.Birth}
	f.Process = tracer.PIDProcessLocalSnapshot{State: libpf.LocalCopied, Binding: s, Counts: tracer.PIDProcessCounts{Received: 5}}
	return f
}

func TestAuxiliaryProjectionPopulatedLocalFacts(t *testing.T) {
	b := validBindingFixture()
	f := auxiliaryProjectionFixture(b)
	p := projectDefinitionSources(nil)
	projectDefinitionAuxiliaryRows(&p, b, f)
	if p.Disposition != definitionSourceUnknown || p.Rows[6].Scope.Observation.Counts.Admitted != 3 || p.Rows[4].Mailbox.Counts.ForeignAdmitted != 1 || p.Rows[5].Process.Counts.Received != 5 {
		t.Fatal("local facts were lost or incorrectly rejected")
	}
	for i := range f.Kinds.Kinds {
		row := p.Rows[7+i]
		if row.Kind == nil || row.Kind.Source != libpf.AuxiliarySource(i+2) || row.Kind.Counts.Admitted != uint64(i+1) { t.Fatalf("kind %d lost", i) }
	}
	for _, row := range p.Rows {
		if row.Enablement != definitionSourceUnknown || row.Upstream != definitionSourceUnknown || row.Next != definitionSourceUnknown { t.Fatalf("authority invented for %s", row.Source) }
	}
	var summary model.Summary
	_ = applyCollectionInventory(nil, nil, nil, &summary, p)
	definitionsMissing := false
	for _, lane := range summary.UnprovenCollectionSources { if lane == accounting.Definitions { definitionsMissing = true } }
	if !definitionsMissing { t.Fatal("local snapshots admitted Definitions") }
	f.Scope.Observation.Counts.Admitted = 99
	f.Kinds.Kinds[0].Counts.Admitted = 99
	f.Mailbox.InfoID = 99
	f.Process.Counts.Received = 99
	if p.Rows[6].Scope.Observation.Counts.Admitted != 3 || p.Rows[7].Kind.Counts.Admitted != 1 || p.Rows[4].Mailbox.InfoID != 77 || p.Rows[5].Process.Counts.Received != 5 { t.Fatal("projection aliases raw facts") }
}

func TestAuxiliaryProjectionRejectsKnownContradictions(t *testing.T) {
	tests := []struct { name string; row int; reason string; mutate func(*definitionSourceAuxiliaryFacts) }{
		{"scope-target", 6, "binding-mismatch", func(f *definitionSourceAuxiliaryFacts) { f.Scope.Observation.Binding.HostTGID++ }},
		{"scope-source", 6, "binding-mismatch", func(f *definitionSourceAuxiliaryFacts) { f.Scope.Observation.Binding.Source++ }},
		{"scope-generation", 6, "binding-mismatch", func(f *definitionSourceAuxiliaryFacts) { f.Scope.Observation.Binding.Generation = 0 }},
		{"scope-panic", 6, "local-read-failure", func(f *definitionSourceAuxiliaryFacts) { f.Scope.State = libpf.LocalPanic }},
		{"scope-absent-copy", 6, "local-read-failure", func(f *definitionSourceAuxiliaryFacts) { f.Scope.State = libpf.LocalAbsent }},
		{"scope-error", 6, "local-read-failure", func(f *definitionSourceAuxiliaryFacts) { f.Scope.ErrorPresent = true }},
		{"scope-held", 6, "held-or-overflow", func(f *definitionSourceAuxiliaryFacts) { f.Scope.Observation.HeldWork = true }},
		{"scope-overflow", 6, "held-or-overflow", func(f *definitionSourceAuxiliaryFacts) { f.Scope.Observation.Counts.Overflow = true }},
		{"scope-inflight", 6, "held-or-overflow", func(f *definitionSourceAuxiliaryFacts) { f.Scope.Observation.Counts.InFlight = 1 }},
		{"kind-source", 7, "binding-mismatch", func(f *definitionSourceAuxiliaryFacts) { f.Kinds.Kinds[0].Source++ }},
		{"kind-generation", 7, "binding-mismatch", func(f *definitionSourceAuxiliaryFacts) { f.Kinds.Kinds[0].Binding.Generation++ }},
		{"kind-error", 7, "local-read-failure", func(f *definitionSourceAuxiliaryFacts) { f.Kinds.Kinds[0].ErrorPresent = true }},
		{"kind-held", 7, "held-or-overflow", func(f *definitionSourceAuxiliaryFacts) { f.Kinds.Kinds[0].HeldWork = true }},
		{"kind-overflow", 7, "held-or-overflow", func(f *definitionSourceAuxiliaryFacts) { f.Kinds.Kinds[0].Counts.Overflow = true }},
		{"kind-inflight", 7, "held-or-overflow", func(f *definitionSourceAuxiliaryFacts) { f.Kinds.Kinds[0].Counts.InFlight = 1 }},
		{"kind-copy", 7, "local-read-failure", func(f *definitionSourceAuxiliaryFacts) { f.Kinds.State = libpf.LocalPanic }},
		{"mailbox-generation", 4, "binding-mismatch", func(f *definitionSourceAuxiliaryFacts) { f.Mailbox.ExpectedBinding.Generation++ }},
		{"mailbox-id", 4, "map-readout-mismatch", func(f *definitionSourceAuxiliaryFacts) { f.Mailbox.InfoID++ }},
		{"mailbox-expected-id", 4, "map-readout-mismatch", func(f *definitionSourceAuxiliaryFacts) { f.Mailbox.ExpectedBinding.MapID++ }},
		{"mailbox-layout", 4, "map-readout-mismatch", func(f *definitionSourceAuxiliaryFacts) { f.Mailbox.InfoKeySize++ }},
		{"mailbox-type", 4, "map-readout-mismatch", func(f *definitionSourceAuxiliaryFacts) { f.Mailbox.InfoType++ }},
		{"mailbox-value-size", 4, "map-readout-mismatch", func(f *definitionSourceAuxiliaryFacts) { f.Mailbox.InfoValueSize++ }},
		{"mailbox-capacity", 4, "map-readout-mismatch", func(f *definitionSourceAuxiliaryFacts) { f.Mailbox.InfoMaxEntries = 0 }},
		{"mailbox-info-missing", 4, "map-readout-missing", func(f *definitionSourceAuxiliaryFacts) { f.Mailbox.InfoReturned = false }},
		{"mailbox-lifetime-missing", 4, "lifetime-readout-missing", func(f *definitionSourceAuxiliaryFacts) { f.Mailbox.LifetimeReturned = false }},
		{"mailbox-lifetime-session", 4, "lifetime-binding-mismatch", func(f *definitionSourceAuxiliaryFacts) { f.Mailbox.LifetimeSession += "x" }},
		{"mailbox-lifetime-tgid", 4, "lifetime-binding-mismatch", func(f *definitionSourceAuxiliaryFacts) { f.Mailbox.LifetimeTGID++ }},
		{"mailbox-lifetime-birth", 4, "lifetime-binding-mismatch", func(f *definitionSourceAuxiliaryFacts) { f.Mailbox.LifetimeBirth++ }},
		{"mailbox-info-error", 4, "local-read-failure", func(f *definitionSourceAuxiliaryFacts) { f.Mailbox.InfoError = true }},
		{"mailbox-error", 4, "local-read-failure", func(f *definitionSourceAuxiliaryFacts) { f.Mailbox.ErrorPresent = true }},
		{"mailbox-failed", 4, "local-read-failure", func(f *definitionSourceAuxiliaryFacts) { f.Mailbox.Outcome = tracer.PIDMailboxFailed }},
		{"mailbox-scan", 4, "local-read-failure", func(f *definitionSourceAuxiliaryFacts) { f.Mailbox.ScanIncomplete = true }},
		{"mailbox-residual", 4, "local-read-failure", func(f *definitionSourceAuxiliaryFacts) { f.Mailbox.UnpolledResidualUnknown = true }},
		{"mailbox-copy", 4, "local-read-failure", func(f *definitionSourceAuxiliaryFacts) { f.Mailbox.State = libpf.LocalPanic }},
		{"mailbox-overflow", 4, "held-or-overflow", func(f *definitionSourceAuxiliaryFacts) { f.Mailbox.Counts.Overflow = true }},
		{"mailbox-held", 4, "held-or-overflow", func(f *definitionSourceAuxiliaryFacts) { f.Mailbox.Counts.HeldResidual = 1 }},
		{"process-target-without-scope", 5, "binding-mismatch", func(f *definitionSourceAuxiliaryFacts) { f.ScopePresent = false; f.Process.Binding.HostTGID++ }},
		{"process-generation", 5, "binding-mismatch", func(f *definitionSourceAuxiliaryFacts) { f.Process.Binding.Generation++ }},
		{"process-source", 5, "binding-mismatch", func(f *definitionSourceAuxiliaryFacts) { f.Process.Binding.Source++ }},
		{"process-classification", 5, "local-classification-mismatch", func(f *definitionSourceAuxiliaryFacts) { f.Process.Counts.OrdinaryNil = 6 }},
		{"process-error", 5, "local-read-failure", func(f *definitionSourceAuxiliaryFacts) { f.Process.ErrorPresent = true }},
		{"process-failed", 5, "local-read-failure", func(f *definitionSourceAuxiliaryFacts) { f.Process.Outcome = tracer.PIDProcessFailed }},
		{"process-copy", 5, "local-read-failure", func(f *definitionSourceAuxiliaryFacts) { f.Process.State = libpf.LocalPanic }},
		{"process-overflow", 5, "held-or-overflow", func(f *definitionSourceAuxiliaryFacts) { f.Process.Counts.Overflow = true }},
		{"process-inflight", 5, "held-or-overflow", func(f *definitionSourceAuxiliaryFacts) { f.Process.Counts.InFlight = 1 }},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			b := validBindingFixture(); f := auxiliaryProjectionFixture(b); tc.mutate(&f)
			p := projectDefinitionSources(nil); projectDefinitionAuxiliaryRows(&p, b, f)
			row := p.Rows[tc.row]
			if p.Disposition != definitionSourceRejected || row.Disposition != definitionSourceRejected || !strings.Contains(strings.Join(row.Missing, ","), tc.reason) { t.Fatalf("row=%+v", row) }
			if tc.row == 7 && (row.Kind == nil || row.Kind.Counts.Admitted != 1) { t.Fatal("rejected kind lost its actual count") }
			if tc.row == 4 && (row.Mailbox == nil || row.Mailbox.Counts.Admitted != 4) { t.Fatal("rejected mailbox lost its actual count") }
			if tc.row == 5 && (row.Process == nil || row.Process.Counts.Received != 5) { t.Fatal("rejected process lost its actual count") }
		})
	}
}

func TestAuxiliaryProjectionMissingFactsAndInvalidExpected(t *testing.T) {
	b := validBindingFixture(); f := auxiliaryProjectionFixture(b)
	f.Kinds.Kinds[0].Present = false; f.ScopePresent = false; f.MailboxPresent = false; f.ProcessPresent = false
	p := projectDefinitionSources(nil); projectDefinitionAuxiliaryRows(&p, b, f)
	if p.Rows[7].Kind != nil || p.Rows[7].Missing[0] != "kind-not-forwarded" || p.Rows[6].Scope != nil || p.Rows[4].Mailbox != nil || p.Rows[5].Process != nil || p.Disposition != definitionSourceUnknown { t.Fatal("missing facts were treated as disabled or invalid") }
	for _, invalid := range []func(*libpf.TraceDefinitionBinding){func(b *libpf.TraceDefinitionBinding) { b.CounterMapID = 0 }, func(b *libpf.TraceDefinitionBinding) { b.Generation = 0 }} {
		bad := b; invalid(&bad); trace := validObservationFixture(bad)
		for _, actual := range []*libpf.TraceDefinitionObservation{&trace, nil} {
			p := projectDefinitionSources(&definitionAccounting{expected: bad, combined: symbolizer.DefinitionStopObservation{TraceDefinition: actual}})
			if p.Rows[1].Binding != definitionSourceRejected || !strings.Contains(strings.Join(p.Rows[1].Missing, ","), "expected-binding-invalid") { t.Fatal("invalid expected binding was not rejected") }
		}
	}
}

func TestDefinitionSourceProjectionRetainsRejectedTraceWithoutAuthority(t *testing.T) {
	binding := validBindingFixture()
	trace := validObservationFixture(binding)
	state := &definitionAccounting{expected: binding}
	obs := symbolizer.DefinitionStopObservation{TraceDefinition: &trace}
	drainer := &captureFinalCandidateDrainer{trace: obs, candidate: obs, cancel: func() {}}
	if withDefinitionDrain(context.Background(), drainer, state, func(context.Context) error { return nil }) == nil {
		t.Fatal("missing auxiliary and stop error must reject")
	}
	if drainer.stops != 1 { t.Fatal("stop was not attempted") }
	state.terminalErr = hostileProjectionError{}
	p := projectDefinitionSources(state)
	if p.Disposition != definitionSourceRejected || len(p.Rows) != 26 {
		t.Fatalf("unexpected rejected projection: %v rows=%d", p.Disposition, len(p.Rows))
	}
	if p.Rows[1].Trace == nil || p.Rows[1].Trace.Rows[0] != trace.Rows[0] {
		t.Fatal("rejected combined trace was not retained")
	}
	trace.Rows[0].Attempted++
	state.combined.TraceDefinition.Rows[0].Attempted++
	if p.Rows[1].Trace.Rows[0] == trace.Rows[0] {
		t.Fatal("projection aliases the input rows")
	}
	for _, row := range p.Rows {
		if row.Enablement != definitionSourceUnknown || row.Upstream != definitionSourceUnknown || row.Next != definitionSourceUnknown {
			t.Fatalf("unavailable authority promoted for %s", row.Source)
		}
	}
	if _, err := json.Marshal(p); err != nil {
		t.Fatal(err)
	}
}

func TestDefinitionSourceProjectionDistinguishesMissingAndBindingMismatch(t *testing.T) {
	binding := validBindingFixture()
	trace := validObservationFixture(binding)
	trace.Binding.Generation++
	p := projectDefinitionSources(&definitionAccounting{expected: binding, combined: symbolizer.DefinitionStopObservation{TraceDefinition: &trace}})
	if p.Rows[1].Binding != definitionSourceRejected || !strings.Contains(strings.Join(p.Rows[1].Missing, ","), "binding-mismatch") {
		t.Fatal("trace binding mismatch was not rejected")
	}
	if p.Rows[7].Kind != nil || p.Rows[7].Missing[0] != "kind-not-forwarded" || p.Rows[2].Missing[0] != "producer-readout-not-forwarded" {
		t.Fatal("missing kind and native producer evidence were conflated")
	}
	if !strings.Contains(strings.Join(p.Missing, ","), "unknown-extra-source") {
		t.Fatal("static rows cannot establish an exhaustive source inventory")
	}
	trace.Binding = binding
	trace.Reader.Overflow = true
	p = projectDefinitionSources(&definitionAccounting{expected: binding, combined: symbolizer.DefinitionStopObservation{TraceDefinition: &trace}})
	if p.Rows[1].Disposition != definitionSourceRejected || !strings.Contains(strings.Join(p.Rows[1].Missing, ","), "held-or-overflow") {
		t.Fatal("overflow must reject the source")
	}
	trace.Reader.Overflow = false
	trace.Rows = make([]libpf.TraceDefinitionCPUCounts, len(trace.Rows))
	trace.Outcome = libpf.TraceDefinitionNotExercised
	p = projectDefinitionSources(&definitionAccounting{expected: binding, combined: symbolizer.DefinitionStopObservation{TraceDefinition: &trace}})
	if p.Rows[1].Enablement != definitionSourceUnknown || p.Rows[1].Disposition != definitionSourceUnknown {
		t.Fatal("zero activity cannot establish that a source was disabled")
	}
}

func (m *mockBindingSymbolizer) TraceDefinitionBinding() (libpf.TraceDefinitionBinding, error) {
	m.calls++
	return m.binding, m.err
}

func validBindingFixture() libpf.TraceDefinitionBinding {
	return libpf.TraceDefinitionBinding{
		Source:       libpf.TraceDefinitionPerfOutputV1,
		Session:      "session-123",
		HostTGID:     1234,
		Birth:        5678,
		CounterMapID: 10,
		PerfMapID:    20,
		PossibleCPUs: 4,
		Generation:   1,
	}
}

type lifecycleDrainer struct {
	calls        int
	ctx          context.Context
	observation  symbolizer.DefinitionStopObservation
	err          error
	contextError bool
	stops        int
	stopObservation symbolizer.DefinitionStopObservation
}

func (d *lifecycleDrainer) DrainDefinitions(ctx context.Context) (symbolizer.DefinitionStopObservation, error) {
	d.calls++
	d.ctx = ctx
	d.stopObservation = d.observation
	if d.observation.TraceDefinition != nil {
		copy := *d.observation.TraceDefinition
		copy.Rows = append([]libpf.TraceDefinitionCPUCounts(nil), copy.Rows...)
		d.stopObservation.TraceDefinition = &copy
	}
	if d.contextError {
		return d.observation, ctx.Err()
	}
	return d.observation, d.err
}

func (d *lifecycleDrainer) StopDefinitions(ctx context.Context) (symbolizer.DefinitionStopObservation, error) {
	d.stops++
	if d.contextError { return d.stopObservation, ctx.Err() }
	return d.stopObservation, d.err
}

func TestDefinitionProductionStartupAndDrain(t *testing.T) {
	provider := &mockBindingSymbolizer{binding: validBindingFixture()}
	var state *definitionAccounting
	if err := withDefinitionStartup(true, provider, func(a *definitionAccounting) error {
		if provider.calls != 1 || a == nil || a.Expected() != provider.binding {
			t.Fatal("READY before prepared binding")
		}
		state = a
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	trace := validObservationFixture(provider.binding)
	rows := trace.Rows
	d := &lifecycleDrainer{observation: symbolizer.DefinitionStopObservation{TraceDefinition: &trace}}
	if err := withDefinitionDrain(ctx, d, state, func(got context.Context) error {
		deadline, ok := got.Deadline()
		want, expected := ctx.Deadline()
		if got != ctx || d.ctx != ctx || !ok || !expected || deadline != want || d.calls != 1 || len(state.Final().Rows) != provider.binding.PossibleCPUs || state.Final().Rows[0].Attempted != 10 {
			t.Fatal("drain or retention not before shutdown")
		}
		return nil
	}); err == nil {
		t.Fatal("trace-only model incorrectly acquired combined authority")
	}
	if d.stops != 1 { t.Fatalf("combined owner stop calls: %d", d.stops) }
	rows[0].Attempted = 9
	if state.Final().Rows[0].Attempted != 10 {
		t.Fatal("retained rows aliased")
	}
	for _, failure := range []error{unix.EPERM, unix.EACCES, context.Canceled} {
		d = &lifecycleDrainer{err: failure}
		state = &definitionAccounting{expected: provider.binding}
		if err := withDefinitionDrain(ctx, d, state, func(context.Context) error { t.Fatal("continued after failure"); return nil }); !errors.Is(err, failure) {
			t.Fatalf("lost error identity: %v", err)
		}
	}
	state = &definitionAccounting{expected: provider.binding}
	if err := withDefinitionDrain(ctx, &lifecycleDrainer{}, state, func(context.Context) error { t.Fatal("continued without observation"); return nil }); err == nil {
		t.Fatal("missing observation accepted")
	}
	canceled, cancelNow := context.WithCancel(ctx)
	cancelNow()
	state = &definitionAccounting{expected: provider.binding}
	if err := withDefinitionDrain(canceled, &lifecycleDrainer{contextError: true}, state, func(context.Context) error { t.Fatal("continued after cancellation"); return nil }); !errors.Is(err, context.Canceled) {
		t.Fatalf("lost cancellation: %v", err)
	}
	provider.err = unix.EACCES
	if err := withDefinitionStartup(true, provider, func(*definitionAccounting) error { t.Fatal("READY after binding failure"); return nil }); err != unix.EACCES {
		t.Fatalf("lost binding error: %v", err)
	}
	provider.err = nil
	provider.calls = 0
	if err := withDefinitionStartup(false, provider, func(a *definitionAccounting) error {
		if a != nil || provider.calls != 0 {
			t.Fatal("disabled path acquired binding")
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
}

func TestDefinitionAccountingBindingAcquiredBeforeReady(t *testing.T) {
	sym := &mockBindingSymbolizer{binding: validBindingFixture()}
	var callOrder []string

	mockAcquire := func() error {
		b, err := definitionExpected(sym)
		if err != nil {
			return err
		}
		if b != sym.binding {
			t.Fatalf("unexpected binding: %+v", b)
		}
		callOrder = append(callOrder, "acquire_binding")
		return nil
	}

	mockReady := func() error {
		callOrder = append(callOrder, "signal_ready")
		return nil
	}

	if err := mockAcquire(); err != nil {
		t.Fatal(err)
	}
	if err := mockReady(); err != nil {
		t.Fatal(err)
	}

	if len(callOrder) != 2 || callOrder[0] != "acquire_binding" || callOrder[1] != "signal_ready" {
		t.Fatalf("expected acquire before ready, got: %v", callOrder)
	}
	if sym.calls != 1 {
		t.Fatalf("expected 1 call, got %d", sym.calls)
	}
}

func TestDefinitionAccountingFinalRowsSurviveAfterReadoutWithoutAliasing(t *testing.T) {
	binding := validBindingFixture()
	defAcc := &definitionAccounting{expected: binding}

	origRows := []libpf.TraceDefinitionCPUCounts{
		{Attempted: 10, Submitted: 10},
		{Attempted: 20, Submitted: 20},
	}
	obs := libpf.TraceDefinitionObservation{
		Binding: binding,
		Rows:    origRows,
		Outcome: libpf.TraceDefinitionFinal,
	}

	if err := defAcc.recordFinal(symbolizer.DefinitionStopObservation{TraceDefinition: &obs}, nil); err == nil {
		t.Fatal("trace-only observation acquired combined authority")
	}

	// Mutate original observation rows after recordFinal
	origRows[0].Attempted = 999
	obs.Rows[1].Submitted = 888

	final1 := defAcc.Final()
	if final1.Rows[0].Attempted != 10 || final1.Rows[1].Submitted != 20 {
		t.Fatalf("defAcc.Final aliased original slice: %+v", final1.Rows)
	}

	// Mutate returned copy
	final1.Rows[0].Attempted = 777
	final2 := defAcc.Final()
	if final2.Rows[0].Attempted != 10 {
		t.Fatalf("defAcc.Final aliased subsequent calls: %+v", final2.Rows)
	}
	if defAcc.Expected() != binding {
		t.Fatalf("defAcc.Expected mismatch: %+v", defAcc.Expected())
	}
}

func TestDefinitionAccountingMissingOptionalCapabilityFailsClosed(t *testing.T) {
	if _, err := definitionExpected(nil); err == nil {
		t.Fatal("expected error for nil symbolizer")
	}

	if _, err := definitionExpected(struct{}{}); err == nil {
		t.Fatal("expected error for symbolizer without capability")
	}

	permErr := unix.EPERM
	symErr := &mockBindingSymbolizer{err: permErr}
	if _, err := definitionExpected(symErr); !errors.Is(err, permErr) {
		t.Fatalf("expected %v, got %v", permErr, err)
	}

	badBinding := validBindingFixture()
	badBinding.CounterMapID = 0
	symBad := &mockBindingSymbolizer{binding: badBinding}
	if _, err := definitionExpected(symBad); err == nil {
		t.Fatal("expected error for invalid binding with zero CounterMapID")
	}

	badBinding2 := validBindingFixture()
	badBinding2.PerfMapID = badBinding2.CounterMapID
	symBad2 := &mockBindingSymbolizer{binding: badBinding2}
	if _, err := definitionExpected(symBad2); err == nil {
		t.Fatal("expected error for identical counter and perf map IDs")
	}
}

func TestDefinitionAccountingOrdinaryDisabledProfileKeepsPriorSemantics(t *testing.T) {
	var defAcc *definitionAccounting
	drainFull := false

	// In ordinary/disabled-profile paths, definitionExpected is not invoked
	if drainFull {
		_, err := definitionExpected(nil)
		if err != nil {
			t.Fatal("unexpected branch")
		}
	}

	if defAcc != nil {
		t.Fatal("expected defAcc to remain nil for disabled profile")
	}
	if exp := defAcc.Expected(); exp != (libpf.TraceDefinitionBinding{}) {
		t.Fatalf("expected empty binding, got %+v", exp)
	}
	if fin := defAcc.Final(); fin.Outcome != 0 || len(fin.Rows) != 0 {
		t.Fatalf("expected empty final observation, got %+v", fin)
	}
}

func TestDefinitionAccountingDrainErrorPreservesContextAndErrno(t *testing.T) {
	defAcc := &definitionAccounting{expected: validBindingFixture()}

	// Test missing trace definition when DrainFull requested
	emptyObs := symbolizer.DefinitionStopObservation{
		ReaderStopped:   true,
		ConsumerStopped: true,
		BuffersDrained:  true,
		TraceDefinition: nil,
	}

	handleAfterReadout := func(obs symbolizer.DefinitionStopObservation, drainErr error, drainFull bool) error {
		if drainErr != nil {
			return drainErr
		}
		if drainFull {
			if obs.TraceDefinition == nil {
				return errors.New("trace definition observation missing from drain")
			}
			if err := defAcc.recordFinal(obs, drainErr); err != nil {
				return err
			}
		}
		return nil
	}

	if err := handleAfterReadout(emptyObs, nil, true); err == nil {
		t.Fatal("expected error when TraceDefinition is nil in DrainFull mode")
	}

	eaccesErr := unix.EACCES
	if err := handleAfterReadout(emptyObs, eaccesErr, true); !errors.Is(err, eaccesErr) {
		t.Fatalf("expected %v, got %v", eaccesErr, err)
	}

	ctxCanceled := context.Canceled
	if err := handleAfterReadout(emptyObs, ctxCanceled, true); !errors.Is(err, ctxCanceled) {
		t.Fatalf("expected %v, got %v", ctxCanceled, err)
	}
}

func TestDefinitionAccountingSummaryExtensionsPreservation(t *testing.T) {
	binding := validBindingFixture()
	validObs := validObservationFixture(binding)
	defAcc := &definitionAccounting{
		expected: binding,
		final:    validObs,
	}

	var summary model.Summary
	if err := applyDefinitionAccounting(defAcc, nil, &summary); err == nil {
		t.Fatal("trace-only summary acquired combined authority")
	}

	if summary.Extensions == nil {
		t.Fatal("summary.Extensions is nil")
	}
	raw, ok := summary.Extensions["definition_accounting"]
	if !ok || len(raw) == 0 {
		t.Fatal("definition_accounting missing from extensions")
	}

	var decoded struct {
		Expected libpf.TraceDefinitionBinding     `json:"expected"`
		Final    libpf.TraceDefinitionObservation `json:"final"`
	}
	if err := json.Unmarshal(raw, &decoded); err != nil {
		t.Fatal(err)
	}
	if decoded.Expected != binding {
		t.Fatalf("decoded expected mismatch: %+v", decoded.Expected)
	}
	if decoded.Final.Outcome != libpf.TraceDefinitionFinal || len(decoded.Final.Rows) != len(validObs.Rows) {
		t.Fatalf("decoded final mismatch: %+v", decoded.Final)
	}

	// Test definitionStopModel attaches trace_definition extension
	stopObs := symbolizer.DefinitionStopObservation{
		ReaderStopped:   true,
		ConsumerStopped: true,
		BuffersDrained:  true,
		TraceDefinition: &defAcc.final,
	}
	modelStop := definitionStopModel(stopObs)
	if modelStop.Extensions == nil {
		t.Fatal("modelStop.Extensions is nil")
	}
	rawTrace, ok := modelStop.Extensions["trace_definition"]
	if !ok || len(rawTrace) == 0 {
		t.Fatal("trace_definition missing from definition stop extensions")
	}
	var decodedTrace libpf.TraceDefinitionObservation
	if err := json.Unmarshal(rawTrace, &decodedTrace); err != nil {
		t.Fatal(err)
	}
	if decodedTrace.Outcome != libpf.TraceDefinitionFinal || len(decodedTrace.Rows) != len(validObs.Rows) {
		t.Fatalf("decoded trace observation mismatch: %+v", decodedTrace)
	}
}

func validObservationFixture(b libpf.TraceDefinitionBinding) libpf.TraceDefinitionObservation {
	rows := make([]libpf.TraceDefinitionCPUCounts, b.PossibleCPUs)
	rows[0] = libpf.TraceDefinitionCPUCounts{
		Attempted:    10,
		Submitted:    7,
		OutputFailed: 2,
		RejectedSize: 1,
	}
	reader := libpf.TraceDefinitionReaderCounts{
		Received:       5,
		HandedOff:      3,
		RejectedRecord: 1,
		HandoffAborted: 1,
	}
	return libpf.TraceDefinitionObservation{
		Binding:      b,
		Rows:         rows,
		Reader:       reader,
		Outcome:      libpf.TraceDefinitionFinal,
		Undelivered:  2,
		EmissionLoss: 5,
	}
}

func TestDefinitionDiagnosticRequiresIndependentIdentityAndConservation(t *testing.T) {
	expected := validBindingFixture()
	validObs := validObservationFixture(expected)

	counts, err := definitionDiagnostic(expected, validObs)
	if err != nil {
		t.Fatalf("valid diagnostic failed: %v", err)
	}
	want := accounting.Counts{
		Known:         true,
		Quiesced:      true,
		Joined:        true,
		Attempted:     10,
		Submitted:     7,
		ReserveFailed: 3,
		Received:      5,
		Terminals:     5,
	}
	if counts != want {
		t.Fatalf("counts mismatch: got %+v, want %+v", counts, want)
	}
	proof := accounting.Evaluate(counts)
	if !proof.Complete || proof.Lost != 5 {
		t.Fatalf("proof mismatch: got %+v", proof)
	}

	for _, kind := range []string{
		"session", "pid", "birth", "counter_map", "perf_map", "generation",
		"cpus_binding", "cpus_rows_short", "cpus_rows_extra",
		"outcome_zero", "outcome_not_exercised", "outcome_unknown",
		"row_overflow", "row_contradiction", "row_add_overflow",
		"reader_overflow", "reader_contradiction", "reader_received_exceeds_submitted",
		"totals_undelivered_mismatch", "totals_emission_loss_mismatch",
	} {
		t.Run(kind, func(t *testing.T) {
			obs := validObservationFixture(expected)
			obs.Rows = append([]libpf.TraceDefinitionCPUCounts(nil), obs.Rows...)
			switch kind {
			case "session":
				obs.Binding.Session = "other"
			case "pid":
				obs.Binding.HostTGID++
			case "birth":
				obs.Binding.Birth++
			case "counter_map":
				obs.Binding.CounterMapID++
			case "perf_map":
				obs.Binding.PerfMapID++
			case "generation":
				obs.Binding.Generation++
			case "cpus_binding":
				obs.Binding.PossibleCPUs++
			case "cpus_rows_short":
				obs.Rows = obs.Rows[:len(obs.Rows)-1]
			case "cpus_rows_extra":
				obs.Rows = append(obs.Rows, libpf.TraceDefinitionCPUCounts{})
			case "outcome_zero":
				obs.Outcome = 0
			case "outcome_not_exercised":
				obs.Outcome = libpf.TraceDefinitionNotExercised
			case "outcome_unknown":
				obs.Outcome = 99
			case "row_overflow":
				obs.Rows[0].Overflow = 1
			case "row_contradiction":
				obs.Rows[0].Attempted++
			case "row_add_overflow":
				obs.Rows[0].Submitted = math.MaxUint64
				obs.Rows[0].OutputFailed = 1
			case "reader_overflow":
				obs.Reader.Overflow = true
			case "reader_contradiction":
				obs.Reader.Received++
			case "reader_received_exceeds_submitted":
				obs.Reader.Received = 8
				obs.Reader.HandedOff = 6
			case "totals_undelivered_mismatch":
				obs.Undelivered++
			case "totals_emission_loss_mismatch":
				obs.EmissionLoss++
			case "not_exercised_nonzero_attempted":
				obs.Outcome = libpf.TraceDefinitionNotExercised
			case "not_exercised_row_overflow":
				obs = libpf.TraceDefinitionObservation{
					Binding: expected,
					Rows:    make([]libpf.TraceDefinitionCPUCounts, expected.PossibleCPUs),
					Outcome: libpf.TraceDefinitionNotExercised,
				}
				obs.Rows[0].Overflow = 1
			case "not_exercised_reader_overflow":
				obs = libpf.TraceDefinitionObservation{
					Binding: expected,
					Rows:    make([]libpf.TraceDefinitionCPUCounts, expected.PossibleCPUs),
					Outcome: libpf.TraceDefinitionNotExercised,
				}
				obs.Reader.Overflow = true
			case "not_exercised_totals_mismatch":
				obs = libpf.TraceDefinitionObservation{
					Binding:     expected,
					Rows:        make([]libpf.TraceDefinitionCPUCounts, expected.PossibleCPUs),
					Outcome:     libpf.TraceDefinitionNotExercised,
					Undelivered: 5,
				}
			}
			if c, err := definitionDiagnostic(expected, obs); err == nil || c != (accounting.Counts{}) {
				t.Fatalf("invalid case %s accepted: counts=%+v, err=%v", kind, c, err)
			}
		})
	}
}

func TestDefinitionDiagnosticRejectsInvalidExpectedEvenIfFinalAgrees(t *testing.T) {
	valid := validBindingFixture()
	for _, kind := range []string{
		"zero_binding", "empty_session", "zero_pid", "zero_birth",
		"zero_counter_map", "zero_perf_map", "identical_maps",
		"zero_cpus", "negative_cpus", "zero_generation", "zero_source",
	} {
		t.Run(kind, func(t *testing.T) {
			b := valid
			switch kind {
			case "zero_binding":
				b = libpf.TraceDefinitionBinding{}
			case "empty_session":
				b.Session = ""
			case "zero_pid":
				b.HostTGID = 0
			case "zero_birth":
				b.Birth = 0
			case "zero_counter_map":
				b.CounterMapID = 0
			case "zero_perf_map":
				b.PerfMapID = 0
			case "identical_maps":
				b.PerfMapID = b.CounterMapID
			case "zero_cpus":
				b.PossibleCPUs = 0
			case "negative_cpus":
				b.PossibleCPUs = -1
			case "zero_generation":
				b.Generation = 0
			case "zero_source":
				b.Source = 0
			}
			obs := libpf.TraceDefinitionObservation{
				Binding: b,
				Outcome: libpf.TraceDefinitionFinal,
			}
			if b.PossibleCPUs > 0 {
				obs.Rows = make([]libpf.TraceDefinitionCPUCounts, b.PossibleCPUs)
			}
			if c, err := definitionDiagnostic(b, obs); err == nil || c != (accounting.Counts{}) {
				t.Fatalf("invalid expected identity accepted for %s: counts=%+v, err=%v", kind, c, err)
			}
		})
	}
}

func TestDefinitionAccountingDiagnosticMethod(t *testing.T) {
	expected := validBindingFixture()
	validObs := validObservationFixture(expected)

	var nilAcc *definitionAccounting
	if _, err := nilAcc.Diagnostic(); err == nil {
		t.Fatal("nil definitionAccounting diagnostic succeeded")
	}

	acc := &definitionAccounting{
		expected: expected,
		final:    validObs,
	}
	if _, err := acc.Diagnostic(); err == nil { t.Fatal("trace-only diagnostic acquired combined authority") }
	counts, err := definitionDiagnostic(expected, validObs)
	if err != nil {
		t.Fatalf("trace-only diagnostic failed: %v", err)
	}
	if counts.Attempted != 10 || counts.Submitted != 7 || counts.ReserveFailed != 3 {
		t.Fatalf("acc.Diagnostic returned unexpected counts: %+v", counts)
	}

	badAcc := &definitionAccounting{
		expected: libpf.TraceDefinitionBinding{},
		final:    validObs,
	}
	if _, err := badAcc.Diagnostic(); err == nil {
		t.Fatal("bad expected diagnostic succeeded")
	}
}

func TestApplyDefinitionAccountingReconcilesAtProductionBoundary(t *testing.T) {
	expected := validBindingFixture()

	for _, kind := range []string{
		"session", "pid", "birth", "counter_map", "perf_map", "generation",
		"cpus_binding", "cpus_rows_short", "cpus_rows_extra",
		"row_overflow", "row_contradiction", "row_add_overflow",
		"reader_overflow", "reader_contradiction", "reader_received_exceeds_submitted",
		"totals_undelivered_mismatch", "totals_emission_loss_mismatch",
		"not_exercised_nonzero_attempted", "not_exercised_row_overflow",
		"not_exercised_reader_overflow", "not_exercised_totals_mismatch",
	} {
		t.Run(kind, func(t *testing.T) {
			obs := validObservationFixture(expected)
			obs.Rows = append([]libpf.TraceDefinitionCPUCounts(nil), obs.Rows...)
			switch kind {
			case "session":
				obs.Binding.Session = "other"
			case "pid":
				obs.Binding.HostTGID++
			case "birth":
				obs.Binding.Birth++
			case "counter_map":
				obs.Binding.CounterMapID++
			case "perf_map":
				obs.Binding.PerfMapID++
			case "generation":
				obs.Binding.Generation++
			case "cpus_binding":
				obs.Binding.PossibleCPUs++
			case "cpus_rows_short":
				obs.Rows = obs.Rows[:len(obs.Rows)-1]
			case "cpus_rows_extra":
				obs.Rows = append(obs.Rows, libpf.TraceDefinitionCPUCounts{})
			case "row_overflow":
				obs.Rows[0].Overflow = 1
			case "row_contradiction":
				obs.Rows[0].Attempted++
			case "row_add_overflow":
				obs.Rows[0].Submitted = math.MaxUint64
				obs.Rows[0].OutputFailed = 1
			case "reader_overflow":
				obs.Reader.Overflow = true
			case "reader_contradiction":
				obs.Reader.Received++
			case "reader_received_exceeds_submitted":
				obs.Reader.Received = 8
				obs.Reader.HandedOff = 6
			case "totals_undelivered_mismatch":
				obs.Undelivered++
			case "totals_emission_loss_mismatch":
				obs.EmissionLoss++
			case "not_exercised_nonzero_attempted":
				obs.Outcome = libpf.TraceDefinitionNotExercised
			case "not_exercised_row_overflow":
				obs = libpf.TraceDefinitionObservation{
					Binding: expected,
					Rows:    make([]libpf.TraceDefinitionCPUCounts, expected.PossibleCPUs),
					Outcome: libpf.TraceDefinitionNotExercised,
				}
				obs.Rows[0].Overflow = 1
			case "not_exercised_reader_overflow":
				obs = libpf.TraceDefinitionObservation{
					Binding: expected,
					Rows:    make([]libpf.TraceDefinitionCPUCounts, expected.PossibleCPUs),
					Outcome: libpf.TraceDefinitionNotExercised,
				}
				obs.Reader.Overflow = true
			case "not_exercised_totals_mismatch":
				obs = libpf.TraceDefinitionObservation{
					Binding:     expected,
					Rows:        make([]libpf.TraceDefinitionCPUCounts, expected.PossibleCPUs),
					Outcome:     libpf.TraceDefinitionNotExercised,
					Undelivered: 5,
				}
			}

			defAcc := &definitionAccounting{
				expected: expected,
				final:    obs,
			}
			var summary model.Summary
			err := applyDefinitionAccounting(defAcc, nil, &summary)
			if err == nil {
				t.Fatalf("mutated or malformed case %s admitted by production boundary", kind)
			}
			if strings.HasPrefix(kind, "not_exercised_") {
				if kind == "not_exercised_nonzero_attempted" {
					if !strings.Contains(err.Error(), "not exercised with nonzero attempted") { t.Fatalf("trace failure masked: %v", err) }
				} else {
					_, traceErr := validateDefinitionObservation(obs)
					if traceErr == nil || !strings.Contains(err.Error(), traceErr.Error()) { t.Fatalf("trace failure masked: %v / %v", traceErr, err) }
				}
			} else {
				_, traceErr := definitionDiagnostic(expected, obs)
				if traceErr == nil || !strings.Contains(err.Error(), traceErr.Error()) { t.Fatalf("trace failure masked: %v / %v", traceErr, err) }
			}

			raw, ok := summary.Extensions["definition_accounting"]
			if !ok || len(raw) == 0 {
				t.Fatalf("case %s failed to retain definition_accounting extension on error", kind)
			}
			var decoded struct {
				Expected libpf.TraceDefinitionBinding     `json:"expected"`
				Final    libpf.TraceDefinitionObservation `json:"final"`
				Counts   *accounting.Counts               `json:"counts,omitempty"`
				Error    string                           `json:"error,omitempty"`
			}
			if err := json.Unmarshal(raw, &decoded); err != nil {
				t.Fatalf("case %s produced unparseable extension: %v", kind, err)
			}
			if decoded.Expected != expected {
				t.Fatalf("case %s failed to retain raw expected binding", kind)
			}
			if decoded.Final.Binding != obs.Binding || len(decoded.Final.Rows) != len(obs.Rows) {
				t.Fatalf("case %s failed to retain raw final observation", kind)
			}
			if decoded.Error == "" {
				t.Fatalf("case %s failed to record diagnostic error in extension", kind)
			}
			if decoded.Counts != nil {
				t.Fatalf("case %s produced false Counts on error", kind)
			}
		})
	}
}

func TestApplyDefinitionAccountingKnownNonzeroLossIsDiagnostic(t *testing.T) {
	expected := validBindingFixture()
	obs := validObservationFixture(expected)
	counts, err := definitionDiagnostic(expected, obs)
	if err != nil { t.Fatalf("valid trace-only loss rejected: %v", err) }
	if counts.Attempted != 10 || counts.Submitted != 7 || counts.ReserveFailed != 3 || counts.Received != 5 || counts.Terminals != 5 { t.Fatalf("unexpected trace counts: %+v", counts) }
	proof := accounting.Evaluate(counts)
	if !proof.Complete || proof.Lost != 5 { t.Fatalf("trace-only reconciliation unexpected: %+v", proof) }
	defAcc := &definitionAccounting{expected: expected, final: obs}
	var summary model.Summary
	if err := applyDefinitionAccounting(defAcc, nil, &summary); err == nil { t.Fatal("trace-only loss acquired combined authority") }
	raw := summary.Extensions["definition_accounting"]
	var decoded struct { Counts *accounting.Counts `json:"counts"`; Error string `json:"error"` }
	if err := json.Unmarshal(raw, &decoded); err != nil || decoded.Error == "" || decoded.Counts != nil { t.Fatalf("rejected receipt lost diagnostic: %+v / %v", decoded, err) }
}

func TestApplyDefinitionAccountingNotExercisedDistinctOutcome(t *testing.T) {
	expected := validBindingFixture()
	unexercisedObs := libpf.TraceDefinitionObservation{
		Binding: expected,
		Rows:    make([]libpf.TraceDefinitionCPUCounts, expected.PossibleCPUs),
		Outcome: libpf.TraceDefinitionNotExercised,
	}
	defAcc := &definitionAccounting{expected: expected}
	if err := defAcc.recordFinal(symbolizer.DefinitionStopObservation{TraceDefinition: &unexercisedObs}, nil); err == nil { t.Fatal("unexercised trace acquired combined authority") }

	var summary model.Summary
	if err := applyDefinitionAccounting(defAcc, nil, &summary); err == nil {
		t.Fatal("unaccepted NotExercised receipt acquired inventory authority")
	}

	raw, ok := summary.Extensions["definition_accounting"]
	if !ok || len(raw) == 0 {
		t.Fatal("definition_accounting extension missing")
	}
	var decoded struct {
		Expected libpf.TraceDefinitionBinding     `json:"expected"`
		Final    libpf.TraceDefinitionObservation `json:"final"`
		Counts   *accounting.Counts               `json:"counts"`
		Error    string                           `json:"error"`
	}
	if err := json.Unmarshal(raw, &decoded); err != nil {
		t.Fatal(err)
	}
	if decoded.Error == "" {
		t.Fatal("unaccepted NotExercised receipt lost its error")
	}
	if decoded.Counts != nil {
		t.Fatal("NotExercised emitted false Counts")
	}
	if decoded.Final.Outcome != libpf.TraceDefinitionNotExercised {
		t.Fatalf("unexpected outcome: %v", decoded.Final.Outcome)
	}

	// Mutated binding with NotExercised must still be rejected
	mutatedObs := unexercisedObs
	mutatedObs.Binding.Session = "other"
	badAcc := &definitionAccounting{
		expected: expected,
		final:    mutatedObs,
	}
	var summary2 model.Summary
	if err := applyDefinitionAccounting(badAcc, nil, &summary2); err == nil {
		t.Fatal("mutated binding with NotExercised admitted")
	}
	raw2 := summary2.Extensions["definition_accounting"]
	var decoded2 struct {
		Error string `json:"error"`
	}
	if err := json.Unmarshal(raw2, &decoded2); err != nil || decoded2.Error == "" {
		t.Fatalf("failed to record error for mutated NotExercised: %v", err)
	}
}

func TestStrictParentNotRetiredCannotPublishFinalProof(t *testing.T) {
	expected := validBindingFixture()
	validObs := validObservationFixture(expected)
	defAcc := &definitionAccounting{
		expected: expected,
		final:    validObs,
	}

	var summary model.Summary
	if _, err := definitionDiagnostic(expected, validObs); err != nil { t.Fatalf("valid trace-only diagnostic rejected: %v", err) }
	if err := applyDefinitionAccounting(defAcc, nil, &summary); err == nil { t.Fatal("trace-only diagnostic acquired combined authority") }

	// Even with valid definition accounting, unretired parent blocks final proof
	selectedBinding := accounting.Binding{Session: "s", PID: 123, Birth: 456, MapID: 7, PossibleCPUs: 2}
	selectedValue := operators.SelectedAccountingObservation{
		Target: operators.SelectedAccountingTarget{Session: "s", PID: 123, Birth: 456},
		MapID:  7, PossibleCPUs: 2,
		Rows:     []operators.SelectedCPUCounts{{Attempted: 3, Submitted: 2, ReserveFailed: 1}, {}},
		Quiesced: true, Joined: true,
	}
	unretiredParent := model.EventFinalizationObservation{
		Phase:             "resources_unretired",
		ResourcesRetired:  false,
		ProducersQuiesced: true,
		ReceiverJoined:    true,
		FinalCounterRead:  true,
		BufferDrained:     true,
	}

	err := applyCollectionInventory(&selectedBinding, &selectedValue, &unretiredParent, &summary, projectDefinitionSources(nil))
	if err == nil {
		t.Fatal("unretired parent allowed final proof publication")
	}
	if summary.SelectedAccounting != nil {
		t.Fatal("unretired parent published SelectedAccounting diagnostic")
	}
	// Definitions lane must remain missing/unproven
	foundDef := false
	for _, l := range summary.UnprovenCollectionSources {
		if l == accounting.Definitions {
			foundDef = true
			break
		}
	}
	if !foundDef {
		t.Fatalf("Definitions lane missing from unproven collection sources: %v", summary.UnprovenCollectionSources)
	}
}

// The zero opaque auxiliary value is Missing, not a native-final receipt.
func TestCaptureModelCombinedReceiptRejectsMissingAndFreezesCopies(t *testing.T) {
	for _, includeAux := range []bool{false, true} {
		for _, stopErr := range []error{nil, unix.EACCES, unix.EPERM} {
		t.Run(fmt.Sprintf("aux=%t/error=%v", includeAux, stopErr), func(t *testing.T) {
			binding := validBindingFixture()
			trace := validObservationFixture(binding)
			if _, err := definitionDiagnostic(binding, trace); err != nil { t.Fatalf("trace fixture is invalid: %v", err) }
			aux := tracer.DefinitionAuxiliaryObservation{}
			obs := symbolizer.DefinitionStopObservation{ReaderStopped: true, ConsumerStopped: true, BuffersDrained: true, AuxiliaryStopped: true, TraceDefinition: &trace}
			if includeAux { obs.Auxiliary = &aux }
			state := &definitionAccounting{expected: binding}
			first := state.recordFinal(obs, stopErr)
			if first == nil || (stopErr != nil && !errors.Is(first, stopErr)) { t.Fatalf("missing auxiliary or native failure accepted: %v", first) }
			if state.status != definitionRejected { t.Fatalf("terminal status: %v", state.status) }
			if _, err := state.Diagnostic(); err == nil || (stopErr != nil && !errors.Is(err, stopErr)) { t.Fatalf("rejected receipt became diagnostic: %v", err) }
			if includeAux && (state.combined.Auxiliary == nil || state.combined.Auxiliary == &aux || state.combined.Auxiliary.Disposition() != tracer.DefinitionAuxiliaryMissing) { t.Fatal("opaque missing receipt not copied") }
			trace.Rows[0].Attempted = 999
			copy := state.Final()
			if copy.Rows[0].Attempted != 10 { t.Fatal("original rows changed frozen trace") }
			copy.Rows[0].Attempted = 888
			if state.Final().Rows[0].Attempted != 10 { t.Fatal("returned rows changed frozen trace") }
			other := validObservationFixture(binding)
			again := state.recordFinal(symbolizer.DefinitionStopObservation{TraceDefinition: &other}, nil)
			if again != first || (stopErr != nil && !errors.Is(again, stopErr)) || state.Final().Rows[0].Attempted != 10 { t.Fatalf("rejected terminal state changed: %v", again) }
		})
		}
	}
}

func TestCaptureModelTraceValidationPrecedesMissingAuxiliary(t *testing.T) {
	for _, kind := range []string{"binding-map", "generation", "row-loss"} {
		t.Run(kind, func(t *testing.T) {
			expected := validBindingFixture()
			trace := validObservationFixture(expected)
			switch kind {
			case "binding-map": trace.Binding.CounterMapID++
			case "generation": trace.Binding.Generation++
			case "row-loss": trace.Rows[0].Attempted++
			}
			_, traceErr := definitionDiagnostic(expected, trace)
			if traceErr == nil { t.Fatal("mutated trace unexpectedly valid") }
			state := &definitionAccounting{expected: expected}
			err := state.recordFinal(symbolizer.DefinitionStopObservation{ReaderStopped: true, ConsumerStopped: true, BuffersDrained: true, AuxiliaryStopped: true, TraceDefinition: &trace}, nil)
			if err == nil || !strings.Contains(err.Error(), traceErr.Error()) { t.Fatalf("intended trace failure masked: trace=%v combined=%v", traceErr, err) }
		})
	}
}

// This private fixture returns modeled trace and Missing auxiliary values only.
type captureFinalCandidateDrainer struct {
	trace, candidate symbolizer.DefinitionStopObservation
	cancel context.CancelFunc
	stops int
}
func (d *captureFinalCandidateDrainer) DrainDefinitions(context.Context) (symbolizer.DefinitionStopObservation, error) { return d.trace, nil }
func (d *captureFinalCandidateDrainer) StopDefinitions(context.Context) (symbolizer.DefinitionStopObservation, error) {
	d.stops++
	d.cancel()
	return d.candidate, unix.EACCES
}

func TestCaptureModelCanceledFinalCandidateRetainedWithoutAliasing(t *testing.T) {
	binding := validBindingFixture()
	initial, candidate := validObservationFixture(binding), validObservationFixture(binding)
	candidate.Rows[0].Attempted = 11
	aux := tracer.DefinitionAuxiliaryObservation{}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	d := &captureFinalCandidateDrainer{trace: symbolizer.DefinitionStopObservation{TraceDefinition: &initial}, candidate: symbolizer.DefinitionStopObservation{TraceDefinition: &candidate, Auxiliary: &aux}, cancel: cancel}
	state := &definitionAccounting{expected: binding}
	first := withDefinitionDrain(ctx, d, state, func(context.Context) error { return nil })
	if !errors.Is(first, context.Canceled) || !errors.Is(first, unix.EACCES) || d.stops != 1 { t.Fatalf("candidate causes lost: %v / %d", first, d.stops) }
	if state.status != definitionRejected || state.combined.Auxiliary == nil || state.combined.Auxiliary == &aux || state.combined.Auxiliary.Disposition() != tracer.DefinitionAuxiliaryMissing { t.Fatal("rejected opaque candidate not retained as a copy") }
	candidate.Rows[0].Attempted = 999
	got := state.Final()
	if len(got.Rows) == 0 || got.Rows[0].Attempted != 11 { t.Fatalf("candidate rows changed: %+v", got.Rows) }
	got.Rows[0].Attempted = 888
	if state.Final().Rows[0].Attempted != 11 { t.Fatal("returned rows alias candidate") }
	again := withDefinitionDrain(context.Background(), d, state, func(context.Context) error { t.Fatal("continued after rejection"); return nil })
	if again != first || d.stops != 1 || !errors.Is(again, context.Canceled) || !errors.Is(again, unix.EACCES) { t.Fatalf("terminal failure changed: %v", again) }
}
