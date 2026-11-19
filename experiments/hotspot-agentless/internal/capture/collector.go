package capture

import (
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/Pilleo/mazewall/hotspot-bridge/wire"
	"go.opentelemetry.io/ebpf-profiler/libpf"
	"io"
	"io.mazewall/experiments/hotspot-agentless/internal/identity"
	"os"
	"strconv"
	"sync"
	"time"

	"github.com/inspektor-gadget/inspektor-gadget/pkg/datasource"
	gadgetcontext "github.com/inspektor-gadget/inspektor-gadget/pkg/gadget-context"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/operators"
	ocihandler "github.com/inspektor-gadget/inspektor-gadget/pkg/operators/oci-handler"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/operators/simple"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/runtime/local"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/symbolizer"
	"github.com/inspektor-gadget/inspektor-gadget/pkg/utils/host"
	"golang.org/x/sys/unix"
	orasoci "oras.land/oras-go/v2/content/oci"

	"io.mazewall/experiments/hotspot-agentless/internal/accounting"
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

func (s providerSink) AccountingPrefix() (uint64, uint64, string, error) {
	return extractAccountingPrefix(s.Sink)
}

type accountingPrefixer interface {
	AccountingPrefix() (uint64, uint64, string, error)
}

func extractAccountingPrefix(s model.Sink) (uint64, uint64, string, error) {
	for {
		if p, ok := s.(accountingPrefixer); ok {
			return p.AccountingPrefix()
		}
		if pr, ok := s.(productionSink); ok {
			s = pr.Sink
			continue
		}
		if ob, ok := s.(observedSink); ok {
			s = ob.Sink
			continue
		}
		break
	}
	return 0, 0, "", errors.New("accounting prefix unsupported on sink")
}

type productionAccountingWirePrefix struct {
	Records uint64 `json:"records"`
	Bytes   uint64 `json:"bytes"`
	SHA256  string `json:"sha256"`
}

type productionAccountingReceipt struct {
	Schema            uint32                         `json:"schema"`
	SessionID         string                         `json:"session_id"`
	HostPID           uint32                         `json:"host_pid"`
	ProcessStartTicks uint64                         `json:"process_start_ticks"`
	WirePrefix        productionAccountingWirePrefix `json:"wire_prefix"`
	Sources           map[string]json.RawMessage     `json:"sources,omitempty"`
}

// Run uses one Gadget symbolizer and its existing process discovery, cache,
// waiters, map replacements and pruning. Only event enrichment is queued here.
type captureRun struct {
	symbolizer           *symbolizer.Symbolizer
	gadget               operators.GadgetContext
	enrichment           *Enricher
	pidfd                int
	barrier              *identity.ExecBarrier
	retained             bool
	definitionAccounting *definitionAccounting
}

func (r *captureRun) DefinitionAccounting() *definitionAccounting {
	if r == nil {
		return nil
	}
	return r.definitionAccounting
}

func Run(ctx context.Context, cfg Config, writer io.Writer) error {
	return (&captureRun{}).run(ctx, cfg, writer)
}

func (r *captureRun) run(ctx context.Context, cfg Config, writer io.Writer) error {
	return runWithDefinitionReceipt(ctx, cfg, writer, r.runValidated)
}

func runWithDefinitionReceipt(ctx context.Context, cfg Config, writer io.Writer, operation func(context.Context, Config, io.Writer, *definitionLocalRun, *[]byte) error) (resultErr error) {
	if err := cfg.Validate(); err != nil {
		return err
	}
	local := &definitionLocalRun{}
	var definitionRaw []byte
	defer func() { resultErr = attachDefinitionReceipt(resultErr, definitionRaw, local) }()
	return operation(ctx, cfg, writer, local, &definitionRaw)
}

func (r *captureRun) runValidated(ctx context.Context, cfg Config, writer io.Writer, local *definitionLocalRun, definitionRaw *[]byte) (resultErr error) {
	requestCtx := ctx
	hardEventDeadline := time.Now().Add(time.Minute)
	if cfg.DrainEvents || cfg.DrainFull {
		var stop context.CancelFunc
		ctx, stop = context.WithDeadline(ctx, hardEventDeadline)
		defer stop()
	}
	if cfg.StrictEpoch {
		var stop context.CancelFunc
		ctx, stop = context.WithTimeout(ctx, 60*time.Second)
		defer stop()
	}
	if GadgetPatchSHA == "" {
		return fmt.Errorf("structured collector requires the provenance-pinned make build")
	}
	if cfg.profile == wire.Observed25 {
		if err := identity.CheckObservedTarget(uint32(cfg.PID), cfg.ExpectedTarget); err != nil {
			return err
		}
	}
	report := preflight.Check(preflight.Observe(cfg.PID))
	if !report.Ready {
		return errors.Join(fmt.Errorf("preflight refused capture: %v", report.Failures), report.NativeError)
	}
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	ownedCtx, abortOwned := fullOwnedContext(ctx, cfg, hardEventDeadline)
	defer abortOwned()
	cancelAll := func() { cancel(); abortOwned() }
	retainFullResources := false
	pidfd, err := unix.PidfdOpen(cfg.PID, 0)
	if err != nil {
		return fmt.Errorf("own target pidfd: %w", err)
	}
	r.pidfd = pidfd
	targetOwner := newCaptureTargetOwner(pidfd)
	defer func() {
		r.retained = retainFullResources
		if err := targetOwner.Close(retainFullResources); err != nil {
			resultErr = errors.Join(resultErr, err)
		}
	}()
	stat, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", cfg.PID))
	if err != nil {
		return err
	}
	ticks, err := preflight.ParseStartTicks(string(stat))
	if err != nil || ticks != report.Snapshot.TargetStartTicks {
		return errors.Join(fmt.Errorf("target changed during startup"), err)
	}
	var execBarrier *identity.ExecBarrier
	var epochTarget libpf.HotSpotEpochTarget
	var observedTarget identity.ObservedTarget
	var checks []func() error
	if cfg.StrictEpoch || cfg.DrainFull {
		execBarrier, err = identity.NewExecBarrier(cfg.PID)
		if err != nil {
			return err
		}
		r.barrier = execBarrier
		targetOwner.attachBarrier(execBarrier)
		defer func() {
			if err := targetOwner.CloseBarrier(retainFullResources); err != nil {
				resultErr = errors.Join(resultErr, err)
			}
		}()
		checks = append(checks, execBarrier.Check)
	}
	if cfg.StrictEpoch {
		target, err := identity.Discover(cfg.PID, ticks)
		if err != nil {
			return err
		}
		if err = execBarrier.Check(); err != nil {
			return err
		}
		epochTarget = libpf.HotSpotEpochTarget{PID: libpf.PID(target.PID), ProcessStartTicks: ticks, CounterAddress: libpf.Address(target.CounterAddress), InitialEpoch: target.InitialEpoch}
	}
	if cfg.profile == wire.Observed25 {
		observedTarget, err = identity.ObserveProfile(uint32(cfg.PID), cfg.ExpectedTarget)
		if err != nil {
			return err
		}
		if err := execBarrier.Check(); err != nil {
			return err
		}
		checks = append(checks, func() error { return identity.CheckObservedTarget(uint32(cfg.PID), cfg.ExpectedTarget) })
	}
	fatal := fatalState{cancel: cancelAll}
	defer func() { resultErr = errors.Join(resultErr, fatal.Err()) }()
	if cfg.AbortContext != nil {
		abortDone := make(chan struct{})
		go func() {
			defer close(abortDone)
			select {
			case <-cfg.AbortContext.Done():
				fatal.Record(context.Cause(cfg.AbortContext))
			case <-ownedCtx.Done():
			}
		}()
		defer func() { cancelAll(); <-abortDone }()
	}
	watchDone := make(chan struct{})
	var watchErr error
	watchConsumed := false
	go func() {
		defer close(watchDone)
		watchErr = targetStopError(cfg, watchTarget(ownedCtx, pidfd, cancelAll, checks...))
	}()
	defer func() {
		cancelAll()
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
	if cfg.sessionID != "" {
		sessionID = cfg.sessionID
	}
	var rawSink model.Sink = jsonoutput.NewJSONL(writer)
	if cfg.production {
		rawSink = newProductionSink(writer)
	}
	if cfg.profile == wire.Observed25 {
		rawSink = newObservedSink(writer)
	}
	sink := providerSink{rawSink}
	rt, err := newLocalRuntime(host.Init)
	if err != nil {
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
	var expectedAccounting *accounting.Binding
	var eventsOnly *eventsOnlyState
	var defAccounting *definitionAccounting
	operator := simple.New("mw-structured", simple.WithPriority(100), simple.OnInit(func(gc operators.GadgetContext) error {
		var err error
		if cfg.EventsOnly {
			eventsOnly = &eventsOnlyState{sink: sink}
			if cfg.DrainEvents {
				gc.SetVar(operators.EventFinalizationOptionsKey, &operators.EventFinalizationOptions{HardDeadline: hardEventDeadline})
			}
		} else {
			options := symbolizer.SymbolizerOptions{UseOtelEbpfProfiler: true, Context: ownedCtx, HotSpotEpochTarget: epochTarget, OnHotSpotFatal: fatal.Record}
			if cfg.DrainFull {
				options.TraceDefinitionLifetime = definitionLifetime{session: sessionID, pid: uint32(cfg.PID), birth: ticks, ctx: ownedCtx, pidfd: pidfd, barrier: execBarrier, fatal: &fatal, observed: cfg.profile == wire.Observed25, expected: cfg.ExpectedTarget, gc: gc, owner: targetOwner}
			}
			if cfg.profile == wire.Observed25 {
				options.HotSpotObservedPID = libpf.PID(cfg.PID)
			}
			sym, err = symbolizer.NewSymbolizer(options)
			if err != nil {
				return err
			}
			r.symbolizer = sym
			for name, replacement := range sym.GetEbpfReplacements() {
				gc.SetVar(name, replacement)
			}
			enrichment, err = newEnricher(ownedCtx, opts, gadgetLookup{symbolizer: sym, sessionID: sessionID, pid: uint32(cfg.PID), startTicks: ticks, strictEpoch: cfg.StrictEpoch, execBarrier: execBarrier}, sink, LinuxBootClock{}, cancelAll, fatal.Record)
			if err != nil {
				return err
			}
			r.enrichment = enrichment
			if cfg.DrainFull {
				gc.SetVar(operators.EventFinalizationOptionsKey, &operators.EventFinalizationOptions{HardDeadline: hardEventDeadline, StrictFull: cfg.StrictEpoch, ObservedFull: cfg.profile == wire.Observed25, Accounting: &operators.SelectedAccountingTarget{Session: sessionID, PID: uint32(cfg.PID), Birth: ticks}, AfterReadout: newDefinitionAfterReadout(ownedCtx, fatal.Err, execBarrier.Check, sym, &defAccounting, local, enrichment.Finish)})
			}
		}
		found := false
		for _, ds := range gc.GetDataSources() {
			if ds.Name() != "openat" {
				continue
			}
			found = true
			reportedLoss, _ = ds.(datasource.ReportedLossReader)
			decoder, err := newEventDecoder(ds, sessionID, uint32(cfg.PID), ticks, cfg.StrictEpoch)
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
				if err == nil && cfg.profile == wire.Observed25 {
					err = validateObservedEvent(cfg.ExpectedTarget, event)
				}
				if err == nil {
					if eventsOnly != nil {
						err = eventsOnly.Submit(eventDeliveryContext(ctx, cfg), event)
					} else {
						err = enrichment.Submit(event)
					}
				}
				if err != nil {
					callbackErr = err
					if cfg.DrainFull {
						fatal.Record(err)
					}
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
		if cfg.DrainFull {
			expected, err := selectedExpected(gc, sessionID, uint32(cfg.PID), ticks)
			if err != nil {
				return err
			}
			expectedAccounting = &expected
		}
		return withDefinitionStartup(cfg.DrainFull, sym, func(state *definitionAccounting) error {
			defAccounting = state
			r.definitionAccounting = state
			if cfg.production || cfg.profile == wire.Observed25 {
				check := func() error {
					if err := ownedCtx.Err(); err != nil {
						return errors.Join(err, fatal.Err())
					}
					if err := execBarrier.Check(); err != nil {
						return err
					}
					poll := []unix.PollFd{{Fd: int32(pidfd), Events: unix.POLLIN}}
					if _, err := unix.Poll(poll, 0); err != nil {
						return err
					}
					if poll[0].Revents != 0 {
						return fmt.Errorf("target unavailable before readiness")
					}
					stat, err := os.ReadFile(fmt.Sprintf("/proc/%d/stat", cfg.PID))
					if err != nil {
						return err
					}
					current, err := preflight.ParseStartTicks(string(stat))
					if err != nil || current != ticks {
						return errors.Join(fmt.Errorf("target generation changed before readiness"), err)
					}
					if cfg.profile == wire.Observed25 {
						if err := identity.CheckObservedTarget(uint32(cfg.PID), cfg.ExpectedTarget); err != nil {
							return err
						}
					}
					return fatal.Err()
				}
				if cfg.profile == wire.Observed25 {
					if err := check(); err != nil {
						return err
					}
					r, err := observedLifecycleRecord("collector_ready", sessionID, observedTarget.Target, observedTarget.JVMBuildID, observedTarget.JVMSHA256)
					if err != nil {
						return err
					}
					return sink.Write(ctx, r)
				}
				return writeProductionReady(ctx, sink, sessionID, uint32(cfg.PID), ticks, check)
			}
			return sink.Write(ctx, model.Record{SchemaVersion: 1, Kind: "collector_attached", SessionID: sessionID})
		})
	}))
	gc := gadgetcontext.New(ctx, cfg.Image, gadgetcontext.WithName("mw-hotspot-openat-structured"), gadgetcontext.WithOrasReadonlyTarget(pinnedTarget{Store: store, reference: cfg.Image}), gadgetcontext.WithDataOperators(ocihandler.OciHandler, operator))
	r.gadget = gc
	params := map[string]string{"operator.oci.ebpf.target_pid": strconv.Itoa(cfg.PID), "operator.oci.ebpf.target_start_ticks": strconv.FormatUint(ticks, 10), "operator.oci.ebpf.collect_ustack": "true", "operator.oci.ebpf.collect_otel_stack": "true", "operator.oci.ebpf.target_safepoint_counter": strconv.FormatUint(uint64(epochTarget.CounterAddress), 10), "operator.oci.ebpf.target_initial_safepoint_epoch": strconv.FormatUint(epochTarget.InitialEpoch, 10)}
	if cfg.EventsOnly {
		params["operator.oci.ebpf.collect_ustack"] = "false"
		params["operator.oci.ebpf.collect_otel_stack"] = "false"
	}
	runErr := rt.RunGadget(gc, nil, params)
	var eventFinalization *model.EventFinalizationObservation
	var eventFinalizationErr error
	if cfg.DrainEvents || cfg.DrainFull {
		cause := "target_exit_or_internal_stop"
		if requestCtx.Err() != nil {
			cause = "request"
		}
		eventFinalization, eventFinalizationErr = eventFinalizationSnapshot(gc, cause)
		if eventFinalization != nil && !eventFinalization.ReceiverJoined {
			retainFullResources = cfg.DrainFull
			// A held writer/callback cannot be safely locked for a final summary.
			return errors.Join(runErr, eventFinalizationErr, errReadoutPending, fmt.Errorf("owned event drain incomplete: %s", eventFinalization.Error))
		}
	}
	cancelAll()
	<-watchDone
	watchConsumed = true
	var finishErr error
	if enrichment != nil {
		finishErr = enrichment.Finish()
	}
	var definitionHealth *model.ReaderObservation
	var definitionStop *model.DefinitionStopObservation
	var definitionStopErr error
	if sym != nil {
		stopCtx, stopCancel := context.WithTimeout(context.Background(), 2*time.Second)
		stopped, stopErr := sym.StopDefinitions(stopCtx)
		stopCancel()
		definitionStop = definitionStopModel(stopped)
		definitionStopErr = stopErr
		definitionHealth = reportedDefinitionHealth(sym)
		if stopErr == nil {
			sym.Close()
		}
	}
	callbackMu.Lock()
	err = errors.Join(runErr, finishErr, callbackErr, watchErr, fatal.Err(), definitionStopErr, eventFinalizationErr)
	if cfg.AbortContext != nil {
		err = errors.Join(err, context.Cause(cfg.AbortContext))
	}
	callbackMu.Unlock()
	summary := model.Summary{CollectionHealth: "unknown"}
	summary.EventFinalization = eventFinalization
	summary.DefinitionStop = definitionStop
	applyCollectionHealth(&summary, reportedEventHealth(reportedLoss))
	if eventFinalization != nil && eventFinalization.FinalCounterRead && summary.EventLossObservation != nil {
		summary.EventLossObservation.Semantics = "quiescent_native_reserve_failures_partial_snapshot"
	}
	summary.DefinitionReaderObservation = definitionHealth
	if definitionHealth != nil {
		summary.CollectionHealth = "partial"
	}
	var enrichCounts *accounting.Counts
	if enrichment != nil {
		summary.Stats = enrichment.Stats()
		counts, accErr := enrichment.accounting()
		if accErr != nil {
			err = errors.Join(err, accErr)
		} else {
			enrichCounts = &counts
		}
		summary.TerminalAccountingComplete = err == nil && enrichCounts != nil && accounting.Evaluate(*enrichCounts).Complete
	}
	if eventsOnly != nil {
		summary.Stats = eventsOnly.stats
		summary.TerminalAccountingComplete = err == nil && eventsOnly.stats.Received == eventsOnly.stats.Skipped
	}
	local.closeMissing()
	var definitionErr error
	if (!cfg.DrainFull || (eventFinalization != nil && eventFinalization.ReceiverJoined)) && defAccounting != nil {
		definitionErr = applyDefinitionAccounting(defAccounting, local, &summary)
		*definitionRaw = append([]byte(nil), summary.Extensions["definition_accounting"]...)
	}
	definitions := projectDefinitionSources(defAccounting)
	if !cfg.DrainFull {
		publishDefinitionSources(&summary, definitions)
	}
	if cfg.DrainFull {
		value, selectedErr := selectedFinal(gc)
		selectedErr = errors.Join(selectedErr, applyCollectionInventory(expectedAccounting, value, eventFinalization, &summary, definitions, enrichCounts))
		err = errors.Join(err, selectedErr)
		if selectedErr != nil {
			summary.TerminalAccountingComplete = false
		}
	}
	err = errors.Join(err, definitionErr)
	if err != nil {
		summary.Errors = []string{err.Error()}
	}
	if prefixer, ok := any(sink).(accountingPrefixer); ok {
		recs, bts, hsh, pErr := prefixer.AccountingPrefix()
		if pErr == nil {
			var pid uint32
			var birth uint64
			if expectedAccounting != nil {
				pid = expectedAccounting.PID
				birth = expectedAccounting.Birth
			} else if cfg.ExpectedTarget.PID != 0 {
				pid = cfg.ExpectedTarget.PID
				birth = cfg.ExpectedTarget.StartTicks
			} else if cfg.PID > 0 {
				pid = uint32(cfg.PID)
			}
			sources := make(map[string]json.RawMessage)
			if summary.SelectedAccounting != nil {
				if raw, mErr := json.Marshal(summary.SelectedAccounting); mErr == nil {
					sources["selected_events"] = raw
				}
			}
			if enrichCounts != nil {
				if raw, mErr := json.Marshal(enrichCounts); mErr == nil {
					sources["enrichment"] = raw
				}
			}
			if defRaw, exists := summary.Extensions["definition_accounting"]; exists && len(defRaw) > 0 {
				sources["definitions"] = defRaw
			}
			pa := productionAccountingReceipt{
				Schema:            1,
				SessionID:         sessionID,
				HostPID:           pid,
				ProcessStartTicks: birth,
				WirePrefix: productionAccountingWirePrefix{
					Records: recs,
					Bytes:   bts,
					SHA256:  hsh,
				},
				Sources: sources,
			}
			if raw, mErr := json.Marshal(pa); mErr == nil {
				if summary.Extensions == nil {
					summary.Extensions = make(map[string]json.RawMessage)
				}
				summary.Extensions["production_accounting"] = raw
			}
		} else {
			err = errors.Join(err, pErr)
		}
	}
	return writeSessionSummary(cfg, sink, sessionID, summary, err)
}

func newLocalRuntime(initHost func(host.Config) error) (*local.Runtime, error) {
	// Real preflight precedes this call; preserve host checks without Gadget's UID-only guard.
	if err := initHost(host.Config{AutoMountFilesystems: false}); err != nil {
		return nil, err
	}
	return local.New(), nil
}

func writeSessionSummary(cfg Config, sink model.Sink, session string, summary model.Summary, cause error) error {
	if cause != nil && (cfg.production || cfg.profile == wire.Observed25) {
		return cause
	}
	return errors.Join(cause, sink.Write(context.Background(), model.Record{
		SchemaVersion: 1, Kind: "session_summary", SessionID: session, Summary: &summary,
	}))
}
