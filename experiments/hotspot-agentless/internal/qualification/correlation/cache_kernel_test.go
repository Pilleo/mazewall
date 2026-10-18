//go:build mw_kernel

package correlation

import (
	"bytes"
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"runtime"
	"sync"
	"testing"
	"time"

	"github.com/cilium/ebpf"
	"github.com/cilium/ebpf/btf"
	"github.com/cilium/ebpf/link"
	"github.com/cilium/ebpf/perf"
	"golang.org/x/sys/unix"
)

type cacheInput struct {
	PID, TID               uint32
	PC, File, Epoch, Token uint64
}
type cacheResult struct {
	Reference, TraceReference, Token, Calls uint64
	CPU                                     uint32
	Status                                  int32
}
type cacheCall struct {
	input cacheInput
	start <-chan struct{}
	done  chan cacheReply
}
type cacheReply struct {
	result cacheResult
	err    error
}
type cacheWorker struct {
	cpu      uint32
	commands chan cacheCall
	joined   chan error
}

// Offsets come from the compiled production BTF, not a second Trace declaration.
func cacheLayout(t *testing.T, spec *ebpf.CollectionSpec, name string) (int, map[string]int) {
	t.Helper()
	var typ *btf.Struct
	if err := spec.Types.TypeByName(name, &typ); err != nil {
		t.Fatal(err)
	}
	fields := map[string]int{}
	for _, m := range typ.Members {
		fields[m.Name] = int(m.Offset.Bytes())
	}
	t.Logf("actual BTF %s size=%d fields=%v", name, typ.Size, fields)
	return int(typ.Size), fields
}

func startCacheWorker(t *testing.T, cpu uint32, inputs, results *ebpf.Map) cacheWorker {
	t.Helper()
	w := cacheWorker{cpu, make(chan cacheCall), make(chan error, 1)}
	ready := make(chan error, 1)
	go func() {
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()
		var previous, selected unix.CPUSet
		err := unix.SchedGetaffinity(0, &previous)
		if err == nil {
			selected.Set(int(cpu))
			err = unix.SchedSetaffinity(0, &selected)
		}
		if err != nil {
			ready <- err
			w.joined <- err
			return
		}
		defer func() { w.joined <- unix.SchedSetaffinity(0, &previous) }()
		tid := uint32(unix.Gettid())
		err = results.Update(tid, cacheResult{}, ebpf.UpdateNoExist)
		ready <- err
		if err != nil {
			return
		}
		for c := range w.commands {
			err := inputs.Update(tid, c.input, ebpf.UpdateAny)
			if c.start != nil {
				<-c.start
			}
			var result cacheResult
			if err == nil {
				_, _, errno := unix.RawSyscall(unix.SYS_GETPGID, 0, 0, 0)
				if errno != 0 {
					err = errno
				} else {
					err = results.Lookup(tid, &result)
				}
			}
			if err == nil && (result.CPU != cpu || result.Token != c.input.Token || result.Status != 0) {
				err = fmt.Errorf("CPU/token/status mismatch: cpu=%d input=%+v result=%+v", cpu, c.input, result)
			}
			c.done <- cacheReply{result, err}
		}
	}()
	if err := <-ready; err != nil {
		t.Fatal(err)
	}
	return w
}

func TestKernelCorrelationCachePublication(t *testing.T) {
	if runtime.GOARCH != "amd64" {
		t.Fatal("fixture requires the admitted Linux-amd64 syscall/kprobe ABI")
	}
	spec, err := ebpf.LoadCollectionSpec(os.Getenv("MW_CORRELATION_CACHE_OBJECT"))
	if err != nil {
		t.Fatal(err)
	}
	if err := spec.Variables["fixture_tgid"].Set(uint32(os.Getpid())); err != nil {
		t.Fatal(err)
	}
	// Initialize ELF data explicitly and omit live Variable handles: cilium's
	// automatic mmap handles otherwise require GC rather than checked retirement.
	for _, v := range spec.Variables {
		m := spec.Maps[v.SectionName]
		data := make([]byte, m.ValueSize)
		if len(m.Contents) > 0 {
			copy(data, m.Contents[0].Value.([]byte))
		}
		copy(data[v.Offset:], v.Value)
		m.Contents = []ebpf.MapKV{{Key: uint32(0), Value: data}}
	}
	spec.Variables = nil
	keySize, keyFields := cacheLayout(t, spec, "TraceCache")
	_, traceFields := cacheLayout(t, spec, "Trace")
	_, epochFields := cacheLayout(t, spec, "HotspotEpochIdentity")
	var origin *btf.Enum
	if err := spec.Types.TypeByName("TraceOrigin", &origin); err != nil {
		t.Fatal(err)
	}
	var probe uint32
	for _, v := range origin.Values {
		if v.Name == "TRACE_PROBE" {
			probe = uint32(v.Value)
		}
	}
	if probe == 0 || keySize != 24+3072*8 {
		t.Fatal("unexpected production layout/origin", probe, keySize)
	}
	// Only maps reachable from this entry are loaded. Definitions/capacities of
	// every reachable production map are untouched; unrelated unwinder maps stay out.
	used := map[string]bool{}
	for _, ins := range spec.Programs["cache_fixture"].Instructions {
		used[ins.Reference()] = true
	}
	for name := range spec.Maps {
		if !used[name] {
			delete(spec.Maps, name)
		}
	}
	possible, err := ebpf.PossibleCPU()
	if err != nil {
		t.Fatal(err)
	}
	spec.Maps["trace_events"].MaxEntries = uint32(possible)
	if m := spec.Maps["report_events"]; m != nil {
		m.MaxEntries = uint32(possible)
	}
	coll, err := ebpf.NewCollection(spec)
	if err != nil {
		t.Fatalf("load actual full dispatcher kprobe: %+v", err)
	}
	mapIDs := map[string]ebpf.MapID{}
	var programID ebpf.ProgramID
	var producer link.Link
	var reader *perf.Reader
	var workers []cacheWorker
	readerDone, callbackDone := make(chan error, 1), make(chan struct{})
	release := make(chan struct{})
	readHeld, readRelease := make(chan struct{}), make(chan struct{})
	var releaseOnce, readReleaseOnce sync.Once
	readerStarted, callbackStarted := false, false
	stopWorkers := func() {
		for _, w := range workers {
			close(w.commands)
		}
		for _, w := range workers {
			select {
			case err := <-w.joined:
				if err != nil {
					t.Error("restore worker affinity", err)
				}
			case <-time.After(2 * time.Second):
				t.Error("worker join deadline")
			}
		}
		workers = nil
	}
	defer func() {
		stopWorkers()
		if producer != nil {
			if err := producer.Close(); err != nil {
				t.Error("detach", err)
			}
		}
		releaseOnce.Do(func() { close(release) })
		readReleaseOnce.Do(func() { close(readRelease) })
		if reader != nil {
			if err := reader.Close(); err != nil {
				t.Error("retire perf reader", err)
			}
		}
		if readerStarted {
			select {
			case err := <-readerDone:
				if err != nil && !errors.Is(err, os.ErrClosed) {
					t.Error(err)
				}
			case <-time.After(2 * time.Second):
				t.Error("reader join deadline")
			}
		}
		if callbackStarted {
			select {
			case <-callbackDone:
			case <-time.After(2 * time.Second):
				t.Error("callback join deadline")
			}
		}
		for name, p := range coll.Programs {
			if err := p.Close(); err != nil {
				t.Errorf("retire program %s: %v", name, err)
			}
		}
		for name, m := range coll.Maps {
			if err := m.Close(); err != nil {
				t.Errorf("retire map %s: %v", name, err)
			}
		}
		// Kernel program/map destruction is RCU-deferred. Poll actual IDs within
		// one shared 2s reclamation budget, after every userspace owner has closed.
		retirementDeadline := time.Now().Add(2 * time.Second)
		waitRetired := func(open func() (func() error, error)) error {
			ticker := time.NewTicker(time.Millisecond)
			defer ticker.Stop()
			for {
				closeHandle, err := open()
				if errors.Is(err, unix.ENOENT) {
					return nil
				}
				if err != nil {
					return err
				}
				if err := closeHandle(); err != nil {
					return err
				}
				if time.Now().After(retirementDeadline) {
					return errors.New("kernel object ID still live at reclamation deadline")
				}
				<-ticker.C
			}
		}
		for name, id := range mapIDs {
			if err := waitRetired(func() (func() error, error) {
				m, err := ebpf.NewMapFromID(id)
				if err != nil {
					return nil, err
				}
				return m.Close, nil
			}); err != nil {
				t.Error("check retired map", name, id, err)
			}
		}
		if programID != 0 {
			if err := waitRetired(func() (func() error, error) {
				p, err := ebpf.NewProgramFromID(programID)
				if err != nil {
					return nil, err
				}
				return p.Close, nil
			}); err != nil {
				t.Error("check retired program", err)
			}
		}
		t.Logf("checked retirement: link, workers/affinity, perf reader, callback, program %d and all %d maps", programID, len(mapIDs))
	}()
	for name, m := range coll.Maps {
		info, err := m.Info()
		if err != nil {
			t.Fatal(err)
		}
		id, ok := info.ID()
		if !ok {
			t.Fatal("missing map ID", name)
		}
		mapIDs[name] = id
		t.Logf("map=%s id=%d type=%s key=%d value=%d entries=%d", name, id, info.Type, info.KeySize, info.ValueSize, info.MaxEntries)
		want := spec.Maps[name]
		if name[0] == '.' && info.Flags == want.Flags|unix.BPF_F_MMAPABLE {
			want = want.Copy()
			want.Flags = info.Flags // cilium's automatic ELF data-section normalization.
		}
		if err := want.Compatible(m); err != nil {
			t.Fatal("loaded map differs from actual spec", name, info, err)
		}
	}
	lru := coll.Maps["stack_cache2correlation_id"]
	if lru.Type() != ebpf.LRUHash || lru.MaxEntries() != 256 || lru.KeySize() != uint32(keySize) || lru.ValueSize() != 8 {
		t.Fatal("not production LRU256")
	}
	program := coll.Programs["cache_fixture"]
	info, err := program.Info()
	if err != nil {
		t.Fatal(err)
	}
	programID, _ = info.ID()
	ids, ok := info.MapIDs()
	if !ok || len(ids) != len(mapIDs) {
		t.Fatal("program/map identity mismatch", ids, mapIDs)
	}
	for name, id := range mapIDs {
		found := false
		for _, actual := range ids {
			found = found || actual == id
		}
		if !found {
			t.Fatal("entry does not share acquired map", name, id)
		}
	}
	reader, err = perf.NewReader(coll.Maps["trace_events"], 524288)
	if err != nil {
		t.Fatal(err)
	}
	packets := make(chan []byte, 32) // At most 17 other-CPU definitions plus the 9-packet held tail.
	held := make(chan struct{})
	const heldPC = 9000
	var definitions [][]byte
	var lost uint64
	go func() {
		defer close(packets)
		for {
			record, err := reader.Read()
			if errors.Is(err, perf.ErrFlushed) {
				readerDone <- nil
				return
			}
			if err != nil {
				readerDone <- err
				return
			}
			lost += record.LostSamples
			if len(record.RawSample) > 0 {
				packets <- bytes.Clone(record.RawSample)
				if binary.LittleEndian.Uint64(record.RawSample[traceFields["frame_data"]:])&((1<<52)-1) == heldPC {
					close(readHeld)
					<-readRelease
				}
			}
		}
	}()
	readerStarted = true
	go func() {
		defer close(callbackDone)
		for raw := range packets {
			definitions = append(definitions, raw)
			if len(raw) >= traceFields["frame_data"]+8 && binary.LittleEndian.Uint64(raw[traceFields["frame_data"]:])&((1<<52)-1) == heldPC {
				close(held)
				<-release
			}
		}
	}()
	callbackStarted = true
	producer, err = link.Kprobe("__x64_sys_getpgid", program, nil)
	if err != nil {
		t.Fatal("attach marker kprobe", err)
	}
	var affinity unix.CPUSet
	if err := unix.SchedGetaffinity(0, &affinity); err != nil {
		t.Fatal(err)
	}
	for cpu := 0; cpu < 1024 && len(workers) < 2; cpu++ {
		if affinity.IsSet(cpu) {
			workers = append(workers, startCacheWorker(t, uint32(cpu), coll.Maps["fixture_inputs"], coll.Maps["fixture_results"]))
		}
	}
	if len(workers) != 2 {
		t.Fatal("requires two allowed CPUs")
	}
	put32 := func(b []byte, offset int, value uint32) { binary.LittleEndian.PutUint32(b[offset:], value) }
	put64 := func(b []byte, offset int, value uint64) { binary.LittleEndian.PutUint64(b[offset:], value) }
	key := func(in cacheInput) []byte {
		b := make([]byte, keySize)
		put32(b, keyFields["pid"], in.PID)
		put32(b, keyFields["tid"], in.TID)
		put64(b, keyFields["hotspot_identity"]+epochFields["epoch"], in.Epoch)
		put32(b, keyFields["hotspot_identity"]+epochFields["state"], 1) // HOTSPOT_EPOCH_STABLE
		put64(b, keyFields["frame_data"], 3<<60|2<<52|in.PC)
		put64(b, keyFields["frame_data"]+8, in.File)
		return b
	}
	expectedPacket := func(in cacheInput, reference uint64) []byte {
		b := make([]byte, traceFields["frame_data"]+16)
		put32(b, traceFields["pid"], in.PID)
		put32(b, traceFields["tid"], in.TID)
		put64(b, traceFields["ktime"], 123456789)
		put32(b, traceFields["kernel_stack_id"], ^uint32(0))
		binary.LittleEndian.PutUint16(b[traceFields["frame_data_len"]:], 2)
		binary.LittleEndian.PutUint16(b[traceFields["num_frames"]:], 1)
		put32(b, traceFields["origin"], probe)
		put64(b, traceFields["correlation_id"], reference)
		copy(b[traceFields["hotspot_identity"]:], key(in)[keyFields["hotspot_identity"]:keyFields["frame_data"]+16])
		return b
	}
	bindings := map[uint64]cacheInput{}
	expectedDefinitions := map[uint64][]byte{}
	var calls, token uint64
	invoke := func(inputs []cacheInput) []cacheResult {
		t.Helper()
		start := make(chan struct{})
		done := make([]chan cacheReply, len(inputs))
		for i := range inputs {
			token++
			inputs[i].Token = token
			done[i] = make(chan cacheReply, 1)
			workers[i].commands <- cacheCall{inputs[i], start, done[i]}
		}
		close(start)
		out := make([]cacheResult, len(inputs))
		for i := range inputs {
			select {
			case reply := <-done[i]:
				if reply.err != nil {
					t.Fatal(reply.err)
				}
				out[i] = reply.result
			case <-time.After(2 * time.Second):
				t.Fatal("marker completion deadline")
			}
			calls++
			r := out[i]
			in := inputs[i]
			in.Token = 0
			if r.Reference != 0 {
				if prior, ok := bindings[r.Reference]; ok && prior != in {
					t.Fatal("cross-key reference alias", r, prior, in)
				}
				bindings[r.Reference] = in
			}
			if r.TraceReference != 0 {
				if r.TraceReference != r.Reference || expectedDefinitions[r.Reference] != nil {
					t.Fatal("miss reference reused or misbound", r)
				}
				expectedDefinitions[r.Reference] = expectedPacket(in, r.Reference)
			}
		}
		return out
	}
	state := func() uint64 {
		t.Helper()
		var value uint64
		if err := coll.Maps["correlation_ids"].Lookup(uint32(0), &value); err != nil {
			t.Fatal(err)
		}
		return value
	}
	a := cacheInput{PID: 621929, TID: 622909, PC: 21, File: 1337, Epoch: 2}
	first := invoke([]cacheInput{a})[0]
	if first.Reference != 1 || first.TraceReference != 1 {
		t.Fatal("initial miss", first)
	}
	hit := invoke([]cacheInput{a})[0]
	if hit.Reference != first.Reference || hit.TraceReference != 0 || state() != 1 {
		t.Fatal("repeat key allocated/published", hit)
	}
	for i := range 16 {
		b, c := a, a
		b.TID += uint32(i + 1)
		c.PC += uint64(i + 1)
		r := invoke([]cacheInput{b, c})
		if r[0].Reference == 0 || r[1].Reference == 0 || r[0].Reference == r[1].Reference {
			t.Fatal("distinct concurrent keys", r)
		}
	}
	equal := a
	equal.Epoch = 4
	r := invoke([]cacheInput{equal, equal})
	if r[0].Reference == 0 || r[1].Reference == 0 {
		t.Fatal("equal-key callers unavailable", r)
	}
	t.Logf("repeat no definition; distinct 16 paired calls CPUs=%d,%d; equal-key results=%+v", workers[0].cpu, workers[1].cpu, r)
	// The retained old occurrence/definition remains outstanding throughout real
	// LRU pressure. Probe actual membership instead of presuming a victim/order.
	old := map[string]uint64{string(key(a)): first.Reference}
	fixtures := map[string]cacheInput{string(key(a)): a}
	for i := range 320 {
		in := a
		in.PC = uint64(100 + i)
		ref := invoke([]cacheInput{in})[0].Reference
		if ref == 0 {
			t.Fatal("fill unavailable", i)
		}
		old[string(key(in))], fixtures[string(key(in))] = ref, in
	}
	var victim cacheInput
	var oldReference uint64
	for k, ref := range old {
		var value uint64
		err := lru.Lookup([]byte(k), &value)
		if errors.Is(err, ebpf.ErrKeyNotExist) {
			victim, oldReference = fixtures[k], ref
			break
		}
		if err != nil {
			t.Fatal("select evicted key", err)
		}
	}
	if oldReference == 0 {
		t.Fatal("no demonstrably evicted key")
	}
	before := state()
	republished := invoke([]cacheInput{victim})[0]
	if republished.Reference <= before || republished.Reference == oldReference || republished.TraceReference != republished.Reference || state() != before+1 {
		t.Fatal("evicted key did not receive fresh reference without reset", oldReference, before, republished)
	}
	if !bytes.Equal(expectedDefinitions[oldReference], expectedPacket(victim, oldReference)) {
		t.Fatal("outstanding old definition changed")
	}
	t.Logf("actual LRU eviction: old=%d republished=%d allocator_before=%d after=%d outstanding old definition retained exact", oldReference, republished.Reference, before, state())
	tail := a
	tail.PC = heldPC
	invoke([]cacheInput{tail})
	select {
	case <-held:
	case <-time.After(2 * time.Second):
		t.Fatal("callback barrier deadline")
	}
	select {
	case <-readHeld:
	case <-time.After(2 * time.Second):
		t.Fatal("reader barrier deadline")
	}
	for i := range 8 {
		queued := a
		queued.PC = uint64(8000 + i)
		if invoke([]cacheInput{queued})[0].Reference == 0 {
			t.Fatal("queued tail unavailable")
		}
	}
	// Forward-only fixture seeding after all calls join, never a production reset.
	// Final miss issues INT64_MAX natively; a hit must still work at saturation.
	if err := coll.Maps["correlation_ids"].Update(uint32(0), uint64(1<<63-2), ebpf.UpdateAny); err != nil {
		t.Fatal(err)
	}
	tail.PC = heldPC + 2
	last := invoke([]cacheInput{tail})[0]
	if last.Reference != 1<<63-1 || last.TraceReference != last.Reference {
		t.Fatal("native saturation boundary", last)
	}
	for range 8 {
		if hit := invoke([]cacheInput{tail})[0]; hit.Reference != last.Reference || hit.TraceReference != 0 {
			t.Fatal("saturated hit", hit)
		}
	}
	missing := a
	missing.PC = heldPC + 1
	if miss := invoke([]cacheInput{missing})[0]; miss.Reference != 0 || miss.TraceReference != 0 {
		t.Fatal("saturated miss", miss)
	}
	if state() != 1<<63-1 {
		t.Fatal("saturated counter changed")
	}
	var absent uint64
	if err := lru.Lookup(key(missing), &absent); !errors.Is(err, ebpf.ErrKeyNotExist) {
		t.Fatal("saturated miss inserted", absent, err)
	}
	stopWorkers()
	if err := producer.Close(); err != nil {
		t.Fatal("detach producer before final flush", err)
	}
	producer = nil
	if err := reader.Flush(); err != nil {
		t.Fatal("flush detached definition transport", err)
	}
	readReleaseOnce.Do(func() { close(readRelease) })
	select {
	case err := <-readerDone:
		readerStarted = false
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("final reader flush/join deadline")
	}
	// The reader is joined while a real callback is held. No map retirement or
	// final definition sample is allowed until this owner has also joined.
	select {
	case <-callbackDone:
		t.Fatal("held callback reported joined")
	default:
	}
	releaseOnce.Do(func() { close(release) })
	select {
	case <-callbackDone:
		callbackStarted = false
	case <-time.After(2 * time.Second):
		t.Fatal("final callback join deadline")
	}
	var admitted uint64
	iter := coll.Maps["fixture_results"].Iterate()
	var tid uint32
	var result cacheResult
	for iter.Next(&tid, &result) {
		admitted += result.Calls
	}
	if err := iter.Err(); err != nil {
		t.Fatal(err)
	}
	if admitted != calls || lost != 0 || len(definitions) != len(expectedDefinitions) {
		t.Fatal("native conservation", admitted, calls, lost, len(definitions), len(expectedDefinitions))
	}
	seen := map[uint64]bool{}
	for _, raw := range definitions {
		packetSize := traceFields["frame_data"] + 16
		if len(raw) < packetSize || len(raw) > packetSize+7 {
			t.Fatal("actual packet size", len(raw), packetSize)
		}
		ref := binary.LittleEndian.Uint64(raw[traceFields["correlation_id"]:])
		if seen[ref] || !bytes.Equal(raw[:packetSize], expectedDefinitions[ref]) {
			t.Fatal("definition duplicate or exact raw task/epoch/frame/reference mismatch", ref, hex.EncodeToString(raw))
		}
		seen[ref] = true
		sum := sha256.Sum256(raw)
		t.Logf("definition reference=%d bytes=%d sha256=%x raw=%s", ref, len(raw), sum, hex.EncodeToString(raw))
	}
	var count int
	it := lru.Iterate()
	k := make([]byte, keySize)
	var value uint64
	for it.Next(&k, &value) {
		if in, ok := bindings[value]; !ok || !bytes.Equal(k, key(in)) {
			t.Fatal("final LRU entry not bound to exact returned definition", value)
		}
		count++
	}
	if err := it.Err(); err != nil {
		t.Fatal(err)
	}
	if count == 0 || count > 256 || !seen[oldReference] || !seen[republished.Reference] {
		t.Fatal("final cache/outstanding reference evidence", count, oldReference, republished.Reference)
	}
	t.Logf("FINAL admitted=%d observed=%d definitions=%d lost=%d LRU_entries=%d allocator=%d; 9 native packets queued behind held reader; quiesced/detached then flushed/joined reader, held callback joined before sample; synthetic fixtures, not JVM metadata", admitted, calls, len(definitions), lost, count, state())
}
