package capture

import (
	"context"
	"crypto/rand"
	"errors"
	"fmt"
	"io"
	"os"
	"strconv"
	"sync"

	"github.com/inspektor-gadget/inspektor-gadget/pkg/datasource"
	gadgetcontext "github.com/inspektor-gadget/inspektor-gadget/pkg/gadget-context"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/operators"
	ocihandler "github.com/inspektor-gadget/inspektor-gadget/pkg/operators/oci-handler"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/operators/simple"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/runtime/local"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/symbolizer"
	"golang.org/x/sys/unix"
	orasoci "oras.land/oras-go/v2/content/oci"

	"io.mazewall/experiments/hotspot-agentless/internal/model"
	jsonoutput "io.mazewall/experiments/hotspot-agentless/internal/output"
	"io.mazewall/experiments/hotspot-agentless/internal/preflight"
)

type providerSink struct{ model.Sink }

func (s providerSink) Write(ctx context.Context, record model.Record) error {
	if record.Evidence != nil && record.Evidence.Provider.Name == "" {
		record.Evidence.Provider = providerIdentity()
	}
	return s.Sink.Write(ctx, record)
}

// Run uses one Gadget symbolizer and its existing process discovery, cache,
// waiters, map replacements and pruning. Only event enrichment is queued here.
func Run(ctx context.Context, cfg Config, writer io.Writer) (resultErr error) {
	if err := cfg.Validate(); err != nil {
		return err
	}
	if GadgetPatchSHA == "" {
		return fmt.Errorf("structured collector requires the provenance-pinned make build")
	}
	report := preflight.Check(preflight.Observe(cfg.PID))
	if !report.Ready {
		return errors.Join(fmt.Errorf("preflight refused capture: %v", report.Failures), report.NativeError)
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	pidfd, err := unix.PidfdOpen(cfg.PID, 0)
	if err != nil {
		return fmt.Errorf("own target pidfd: %w", err)
	}
	defer func() { resultErr = errors.Join(resultErr, unix.Close(pidfd)) }()
	stat, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", cfg.PID))
	if err != nil {
		return err
	}
	ticks, err := preflight.ParseStartTicks(string(stat))
	if err != nil || ticks != report.Snapshot.TargetStartTicks {
		return errors.Join(fmt.Errorf("target changed during startup"), err)
	}
	watchDone := make(chan struct{})
	var watchErr error
	watchConsumed := false
	go func() {
		defer close(watchDone)
		watchErr = watchTarget(ctx, pidfd, cancel)
	}()
	defer func() {
		cancel()
		<-watchDone
		if !watchConsumed {
			resultErr = errors.Join(resultErr, watchErr)
		}
	}()
	var random [16]byte
	if _, err := rand.Read(random[:]); err != nil {
		return err
	}
	sessionID := fmt.Sprintf("%x", random)
	sink := providerSink{jsonoutput.NewJSONL(writer)}
	rt := local.New()
	if err := rt.Init(nil); err != nil {
		return err
	}
	defer rt.Close()
	store, err := orasoci.New(cfg.Store)
	if err != nil {
		return err
	}
	opts := cfg.Enrichment
	if opts == (EnrichmentOptions{}) {
		opts = DefaultEnrichmentOptions()
	}
	var sym *symbolizer.Symbolizer
	var enrichment *Enricher
	var callbackMu sync.Mutex
	var callbackErr error
	var reportedLoss datasource.ReportedLossReader
	operator := simple.New("mw-structured", simple.WithPriority(100), simple.OnInit(func(gc operators.GadgetContext) error {
		var err error
		sym, err = symbolizer.NewSymbolizer(symbolizer.SymbolizerOptions{UseOtelEbpfProfiler: true, Context: ctx})
		if err != nil {
			return err
		}
		for name, replacement := range sym.GetEbpfReplacements() {
			gc.SetVar(name, replacement)
		}
		enrichment, err = NewEnricher(ctx, opts, gadgetLookup{symbolizer: sym, sessionID: sessionID, pid: uint32(cfg.PID), startTicks: ticks}, sink, LinuxBootClock{}, cancel)
		if err != nil {
			return err
		}
		found := false
		for _, ds := range gc.GetDataSources() {
			if ds.Name() != "openat" {
				continue
			}
			found = true
			reportedLoss, _ = ds.(datasource.ReportedLossReader)
			decoder, err := newEventDecoder(ds, sessionID, uint32(cfg.PID), ticks)
			if err != nil {
				return err
			}
			if err := ds.Subscribe(func(source datasource.DataSource, data datasource.Data) error {
				callbackMu.Lock()
				defer callbackMu.Unlock()
				if callbackErr != nil {
					return callbackErr
				}
				event, err := decoder.Decode(data)
				if err == nil {
					err = enrichment.Submit(event)
				}
				if err != nil {
					callbackErr = err
					gc.Cancel()
				}
				return err
			}, 1000); err != nil {
				return err
			}
		}
		if !found {
			return fmt.Errorf("openat datasource missing")
		}
		return nil
	}), simple.OnStart(func(gc operators.GadgetContext) error {
		return sink.Write(ctx, model.Record{SchemaVersion: 1, Kind: "collector_attached", SessionID: sessionID})
	}))
	gc := gadgetcontext.New(ctx, cfg.Image, gadgetcontext.WithName("mw-hotspot-openat-structured"), gadgetcontext.WithOrasReadonlyTarget(pinnedTarget{Store: store, reference: cfg.Image}), gadgetcontext.WithDataOperators(ocihandler.OciHandler, operator))
	runErr := rt.RunGadget(gc, nil, map[string]string{"operator.oci.ebpf.target_pid": strconv.Itoa(cfg.PID), "operator.oci.ebpf.target_start_ticks": strconv.FormatUint(ticks, 10), "operator.oci.ebpf.collect_ustack": "true", "operator.oci.ebpf.collect_otel_stack": "true"})
	cancel()
	<-watchDone
	watchConsumed = true
	var finishErr error
	if enrichment != nil {
		finishErr = enrichment.Finish()
	}
	var definitionHealth *model.ReaderObservation
	if sym != nil {
		definitionHealth = reportedDefinitionHealth(sym)
		sym.Close()
	}
	callbackMu.Lock()
	err = errors.Join(runErr, finishErr, callbackErr, watchErr)
	callbackMu.Unlock()
	summary := model.Summary{CollectionHealth: "unknown"}
	applyCollectionHealth(&summary, reportedEventHealth(reportedLoss))
	summary.DefinitionReaderObservation = definitionHealth
	if definitionHealth != nil {
		summary.CollectionHealth = "partial"
	}
	if enrichment != nil {
		summary.Stats = enrichment.Stats()
		s := summary.Stats
		summary.TerminalAccountingComplete = err == nil && s.Resolved+s.Unavailable+s.Skipped == s.Received
	}
	if err != nil {
		summary.Errors = []string{err.Error()}
	}
	writeErr := sink.Write(context.Background(), model.Record{SchemaVersion: 1, Kind: "session_summary", SessionID: sessionID, Summary: &summary})
	return errors.Join(err, writeErr)
}
