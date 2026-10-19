package identity

import (
	"bufio"
	"fmt"
	"math"
	"strconv"
	"strings"
)

const JVMBuildID = "e4dad22744e32e4d71755f4a58c5cf1f1d336f87"
const JVMSHA256 = "5e0fb6e83a5676090f28ff0b453e9199df06af1ff90d473c1b9837e41096114c"
const ProfileName = "temurin25_xint_serial"

type Profile struct {
	BuildID, SHA256            string
	UseSerialGC, UseCompiler   bool
	useG1GC, tieredCompilation bool
}
type Target struct {
	PID                                             uint32
	ProcessStartTicks, CounterAddress, InitialEpoch uint64
	JVMBuildID, JVMSHA256                           string
}

func ValidateProfile(p Profile) error {
	if p.BuildID != JVMBuildID || p.SHA256 != JVMSHA256 || !p.UseSerialGC || p.UseCompiler {
		return fmt.Errorf("unsupported HotSpot identity profile: need pinned Temurin interpreter-only Serial GC")
	}
	return nil
}
func RuntimeAddress(symbolValue, loadBias uint64) (uint64, error) {
	if symbolValue > math.MaxUint64-loadBias {
		return 0, fmt.Errorf("HotSpot symbol address overflow")
	}
	return symbolValue + loadBias, nil
}

type mapping struct {
	start, end, offset, inode uint64
	major, minor              uint32
	readable                  bool
	path                      string
}

func parseMaps(text string) ([]mapping, error) {
	var out []mapping
	s := bufio.NewScanner(strings.NewReader(text))
	s.Buffer(make([]byte, 4096), 1<<20)
	for s.Scan() {
		f := strings.Fields(s.Text())
		if len(f) < 5 {
			return nil, fmt.Errorf("invalid process mapping")
		}
		lo, hi, ok := strings.Cut(f[0], "-")
		major, minor, devOK := strings.Cut(f[3], ":")
		if !ok || !devOK {
			return nil, fmt.Errorf("invalid process mapping range/device")
		}
		parse := func(v string, base, bits int) (uint64, error) { return strconv.ParseUint(v, base, bits) }
		a, e1 := parse(lo, 16, 64)
		b, e2 := parse(hi, 16, 64)
		off, e3 := parse(f[2], 16, 64)
		ino, e4 := parse(f[4], 10, 64)
		maj, e5 := parse(major, 16, 32)
		min, e6 := parse(minor, 16, 32)
		if e1 != nil || e2 != nil || e3 != nil || e4 != nil || e5 != nil || e6 != nil || a >= b || len(f[1]) != 4 {
			return nil, fmt.Errorf("invalid process mapping fields")
		}
		path := ""
		if len(f) > 5 {
			path = strings.Join(f[5:], " ")
		}
		out = append(out, mapping{start: a, end: b, offset: off, inode: ino, major: uint32(maj), minor: uint32(min), readable: f[1][0] == 'r', path: path})
	}
	return out, s.Err()
}
