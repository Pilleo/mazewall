//go:build linux

package identity

import (
	"crypto/sha256"
	"debug/elf"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"strings"

	"golang.org/x/sys/unix"
	"io.mazewall/experiments/hotspot-agentless/internal/preflight"
)

// Discover is read-only. The caller must own an exec barrier before calling it.
func Discover(pid int, ticks uint64) (Target, error) {
	var target Target
	statTicks := func() (uint64, error) {
		b, e := os.ReadFile(fmt.Sprintf("/proc/%d/stat", pid))
		if e != nil {
			return 0, e
		}
		return preflight.ParseStartTicks(string(b))
	}
	before, err := statTicks()
	if err != nil {
		return target, err
	}
	if before != ticks || ticks == 0 {
		return target, fmt.Errorf("target incarnation changed before profile discovery")
	}
	raw, err := os.ReadFile(fmt.Sprintf("/proc/%d/maps", pid))
	if err != nil {
		return target, err
	}
	maps, err := parseMaps(string(raw))
	if err != nil {
		return target, err
	}
	var selected *mapping
	for i := range maps {
		m := &maps[i]
		if strings.HasSuffix(m.path, "/libjvm.so (deleted)") {
			return target, fmt.Errorf("mapped JVM deleted")
		}
		if strings.HasSuffix(m.path, "/libjvm.so") && m.offset == 0 {
			if selected != nil {
				return target, fmt.Errorf("ambiguous mapped JVM")
			}
			selected = m
		}
	}
	if selected == nil {
		return target, fmt.Errorf("mapped JVM not found")
	}
	f, err := os.Open(fmt.Sprintf("/proc/%d/root%s", pid, selected.path))
	if err != nil {
		return target, err
	}
	defer f.Close()
	var st unix.Stat_t
	if err = unix.Fstat(int(f.Fd()), &st); err != nil {
		return target, err
	}
	if st.Ino != selected.inode || unix.Major(st.Dev) != selected.major || unix.Minor(st.Dev) != selected.minor {
		return target, fmt.Errorf("mapped JVM file identity changed")
	}
	h := sha256.New()
	if _, err = io.Copy(h, f); err != nil {
		return target, err
	}
	digest := hex.EncodeToString(h.Sum(nil))
	if digest != JVMSHA256 {
		return target, fmt.Errorf("unsupported mapped JVM SHA-256 %s", digest)
	}
	ef, err := elf.NewFile(f)
	if err != nil {
		return target, err
	}
	defer ef.Close()
	if ef.Class != elf.ELFCLASS64 || ef.Machine != elf.EM_X86_64 || ef.Data != elf.ELFDATA2LSB {
		return target, fmt.Errorf("unsupported JVM ELF ABI")
	}
	buildID, err := gnuBuildID(ef)
	if err != nil {
		return target, err
	}
	page := uint64(os.Getpagesize())
	var bias uint64
	found := false
	for _, p := range ef.Progs {
		if p.Type != elf.PT_LOAD || p.Off&^(page-1) != selected.offset {
			continue
		}
		aligned := p.Vaddr &^ (page - 1)
		if selected.start < aligned {
			return target, fmt.Errorf("invalid JVM load bias")
		}
		b := selected.start - aligned
		if found && bias != b {
			return target, fmt.Errorf("ambiguous JVM load bias")
		}
		bias = b
		found = true
	}
	if !found {
		return target, fmt.Errorf("JVM mapping has no ELF load segment")
	}
	syms, err := ef.Symbols()
	if err != nil {
		return target, err
	}
	address := func(name string, size uint64) (uint64, error) {
		var addr uint64
		matches := 0
		for _, s := range syms {
			if s.Name != name {
				continue
			}
			if s.Size != size || elf.ST_TYPE(s.Info) != elf.STT_OBJECT {
				return 0, fmt.Errorf("invalid JVM symbol %s size/type", name)
			}
			a, e := RuntimeAddress(s.Value, bias)
			if e != nil {
				return 0, e
			}
			loaded := false
			for _, p := range ef.Progs {
				if p.Type == elf.PT_LOAD && s.Value >= p.Vaddr && s.Value-p.Vaddr <= p.Memsz && size <= p.Memsz-(s.Value-p.Vaddr) {
					loaded = true
				}
			}
			readable := false
			for _, m := range maps {
				if m.readable && a >= m.start && a < m.end && size <= m.end-a {
					readable = true
				}
			}
			if !loaded || !readable || a >= 1<<63 {
				return 0, fmt.Errorf("JVM symbol outside readable load memory")
			}
			addr = a
			matches++
		}
		if matches != 1 {
			return 0, fmt.Errorf("missing/ambiguous JVM symbol %s", name)
		}
		return addr, nil
	}
	counter, err := address("_ZN20SafepointSynchronize18_safepoint_counterE", 8)
	if err != nil {
		return target, err
	}
	if counter&7 != 0 {
		return target, fmt.Errorf("unaligned JVM epoch")
	}
	serial, err := address("UseSerialGC", 1)
	if err != nil {
		return target, err
	}
	compiler, err := address("UseCompiler", 1)
	if err != nil {
		return target, err
	}
	mem, err := os.Open(fmt.Sprintf("/proc/%d/mem", pid))
	if err != nil {
		return target, err
	}
	defer mem.Close()
	read := func(a uint64, b []byte) error {
		n, e := mem.ReadAt(b, int64(a))
		if e != nil {
			return fmt.Errorf("read JVM profile: %w", e)
		}
		if n != len(b) {
			return fmt.Errorf("read JVM profile: %w", io.ErrUnexpectedEOF)
		}
		return nil
	}
	var sb, cb [1]byte
	var epoch [8]byte
	if err = read(serial, sb[:]); err != nil {
		return target, err
	}
	if err = read(compiler, cb[:]); err != nil {
		return target, err
	}
	if sb[0] > 1 || cb[0] > 1 {
		return target, fmt.Errorf("invalid JVM bool storage")
	}
	if err = ValidateProfile(Profile{BuildID: buildID, SHA256: digest, UseSerialGC: sb[0] == 1, UseCompiler: cb[0] == 1}); err != nil {
		return target, err
	}
	if err = read(counter, epoch[:]); err != nil {
		return target, err
	}
	value := binary.LittleEndian.Uint64(epoch[:])
	if value&1 != 0 || value >= 1<<63 {
		return target, fmt.Errorf("JVM counter not in supported stable range")
	}
	after, err := statTicks()
	if err != nil {
		return target, err
	}
	if after != before {
		return target, fmt.Errorf("target changed during profile discovery")
	}
	return Target{PID: uint32(pid), ProcessStartTicks: ticks, CounterAddress: counter, InitialEpoch: value, JVMBuildID: buildID, JVMSHA256: digest}, nil
}

func gnuBuildID(f *elf.File) (string, error) {
	s := f.Section(".note.gnu.build-id")
	if s == nil {
		return "", fmt.Errorf("JVM build ID absent")
	}
	b, err := s.Data()
	if err != nil {
		return "", err
	}
	for len(b) >= 12 {
		n, d, k := binary.LittleEndian.Uint32(b), binary.LittleEndian.Uint32(b[4:]), binary.LittleEndian.Uint32(b[8:])
		b = b[12:]
		na, da := (uint64(n)+3)&^3, (uint64(d)+3)&^3
		if na > uint64(len(b)) || da > uint64(len(b))-na {
			return "", fmt.Errorf("truncated build ID note")
		}
		if k == 3 && n == 4 && string(b[:4]) == "GNU\x00" {
			return hex.EncodeToString(b[na : na+uint64(d)]), nil
		}
		b = b[na+da:]
	}
	return "", fmt.Errorf("GNU build ID absent")
}
