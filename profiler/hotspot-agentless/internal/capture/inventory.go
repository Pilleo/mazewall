package capture

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"

	"github.com/inspektor-gadget/inspektor-gadget/pkg/operators"
	"go.opentelemetry.io/ebpf-profiler/libpf"
	"io.mazewall/experiments/hotspot-agentless/internal/accounting"
	"io.mazewall/experiments/hotspot-agentless/internal/model"
)

type definitionReceiptError struct {
	cause error
	raw   []byte // raw nil => omitted
}

var errDefinitionReceiptMarshal = errors.New("failed_definition_receipt:unavailable_marshal_error")

func (e definitionReceiptError) Unwrap() error { return e.cause }

func (e definitionReceiptError) Is(target error) bool {
	if t, ok := target.(definitionReceiptError); ok {
		return errors.Is(e.cause, t.cause) && bytes.Equal(e.raw, t.raw)
	}
	if t, ok := target.(*definitionReceiptError); ok && t != nil {
		return errors.Is(e.cause, t.cause) && bytes.Equal(e.raw, t.raw)
	}
	return errors.Is(e.cause, target)
}

func (e definitionReceiptError) Error() string {
	if e.raw == nil {
		return e.cause.Error() + "\nfailed_definition_receipt:omitted_over_budget"
	}
	return e.cause.Error() + "\nfailed_definition_receipt:" + string(e.raw)
}

func attachDefinitionReceipt(cause error, raw []byte, local *definitionLocalRun) error {
	missing := local.closeMissing()
	if cause == nil {
		return nil
	}
	var existing definitionReceiptError
	var existingPointer *definitionReceiptError
	if errors.As(cause, &existing) || errors.As(cause, &existingPointer) || errors.Is(cause, errDefinitionReceiptMarshal) {
		return cause
	}
	if raw == nil {
		var err error
		raw, err = json.Marshal(struct {
			Expected     any                     `json:"expected"`
			Final        any                     `json:"final"`
			Counts       any                     `json:"counts"`
			Local        any                     `json:"local"`
			LocalMissing *definitionLocalMissing `json:"local_missing"`
		}{LocalMissing: missing})
		if err != nil {
			return errors.Join(cause, err, errDefinitionReceiptMarshal)
		}
	}
	var copied []byte
	if len(raw) <= 262144 {
		copied = append([]byte(nil), raw...)
	}
	return definitionReceiptError{cause: cause, raw: copied}
}

func publishDefinitionSources(summary *model.Summary, definitions definitionSourceConservation) {
	if summary.Extensions == nil {
		summary.Extensions = make(map[string]json.RawMessage)
	}
	raw, marshalErr := json.Marshal(definitions)
	if marshalErr != nil || len(raw) > 262144 {
		raw = json.RawMessage(`{"disposition":"unknown","missing":["diagnostic-omitted-over-budget"]}`)
	}
	summary.Extensions["definition_source_conservation"] = raw
}

func applyCollectionInventory(expected *accounting.Binding, value *operators.SelectedAccountingObservation, parent *model.EventFinalizationObservation, summary *model.Summary, definitions definitionSourceConservation, enrichReceipt ...*accounting.Counts) error {
	publishDefinitionSources(summary, definitions)
	var enrichCounts *accounting.Counts
	if len(enrichReceipt) > 0 {
		enrichCounts = enrichReceipt[0]
	}
	lanes := map[accounting.Lane]accounting.LaneEvidence{}
	var binding accounting.Binding
	var err error
	summary.SelectedAccounting = nil
	if expected == nil || value == nil {
		err = fmt.Errorf("selected prepared identity or final counters missing")
	} else {
		binding = *expected
		summary.SelectedAccounting, err = selectedDiagnostic(binding, *value, parent, summary.Stats)
		if d := summary.SelectedAccounting; d != nil {
			// Only the just-validated native rows, final parent and delivered stats admit this lane.
			lanes[accounting.SelectedEvents] = accounting.LaneEvidence{Binding: binding, Final: d.Complete, Counts: accounting.Counts{
				Known: true, Quiesced: value.Quiesced, Joined: value.Joined,
				Attempted: d.Attempted, Submitted: d.Submitted, ReserveFailed: d.ReserveFailed, Received: d.Received, Terminals: d.Terminals,
			}}
		}
		if enrichCounts != nil {
			proof := accounting.Evaluate(*enrichCounts)
			if !proof.Complete {
				err = errors.Join(err, fmt.Errorf("enrichment accounting incomplete: %s", proof.Reason))
			} else if summary.SelectedAccounting == nil || !summary.SelectedAccounting.Complete {
				err = errors.Join(err, fmt.Errorf("enrichment delivery cannot be reconciled: selected accounting incomplete"))
			} else if enrichCounts.Received != summary.SelectedAccounting.Received {
				err = errors.Join(err, fmt.Errorf("enrichment delivery does not match selected received events"))
			} else {
				lanes[accounting.Enrichment] = accounting.LaneEvidence{
					Binding: binding,
					Final:   true,
					Counts:  *enrichCounts,
				}
			}
		}
	}
	summary.UnprovenCollectionSources = accounting.MissingEvidence(binding, lanes)
	return err
}

func applyDefinitionAccounting(defAcc *definitionAccounting, local *definitionLocalRun, summary *model.Summary) error {
	if defAcc == nil || summary == nil {
		return nil
	}
	if summary.Extensions == nil {
		summary.Extensions = make(map[string]json.RawMessage)
	}

	expected := defAcc.expected
	final := defAcc.final

	var (
		counts  *accounting.Counts
		diagErr error
	)

	if final.Outcome == libpf.TraceDefinitionNotExercised {
		if err := validateTraceDefinitionBinding(expected); err != nil {
			diagErr = fmt.Errorf("trace definition expected binding invalid: %w", err)
		} else if expected != final.Binding {
			diagErr = fmt.Errorf("trace definition binding mismatch")
		} else if len(final.Rows) != expected.PossibleCPUs {
			diagErr = fmt.Errorf("trace definition CPU cardinality mismatch: expected %d, got %d", expected.PossibleCPUs, len(final.Rows))
		} else {
			totals, err := validateDefinitionObservation(final)
			if err != nil {
				diagErr = err
			} else if totals.totalAttempted != 0 {
				diagErr = fmt.Errorf("trace definition not exercised with nonzero attempted: %d", totals.totalAttempted)
			}
		}
		diagErr = errors.Join(diagErr, defAcc.requireAcceptedCombined())
	} else {
		c, err := defAcc.Diagnostic()
		if err != nil {
			diagErr = err
		} else {
			counts = &c
		}
	}

	receipt := struct {
		Expected     libpf.TraceDefinitionBinding     `json:"expected"`
		Final        libpf.TraceDefinitionObservation `json:"final"`
		Counts       *accounting.Counts               `json:"counts,omitempty"`
		Error        string                           `json:"error,omitempty"`
		Local        json.RawMessage                  `json:"local"`
		LocalMissing *definitionLocalMissing          `json:"local_missing"`
	}{
		Expected:     expected,
		Final:        final,
		Counts:       counts,
		LocalMissing: local.closeMissing(),
	}
	if diagErr != nil {
		receipt.Error = diagErr.Error()
	}

	raw, err := json.Marshal(receipt)
	if err != nil {
		return errors.Join(diagErr, err, errDefinitionReceiptMarshal)
	}
	summary.Extensions["definition_accounting"] = raw

	if diagErr != nil {
		return attachDefinitionReceipt(diagErr, raw, local)
	}
	return nil
}
