//go:build integration

package integration

import (
	"bufio"
	"encoding/json"
	"os"
	"strconv"
	"strings"
	"testing"
)

// A capture without the event's own OTel reference or an application frame must
// not pass merely because a rendered native stack exists.
func TestCaptureContainsEventTriggeredJavaFrame(t *testing.T) {
	name := os.Getenv("MW_CAPTURE_JSONL")
	if name == "" {
		t.Fatal("MW_CAPTURE_JSONL must name a genuine live capture; this gate never skips")
	}
	expectedPID, err := strconv.ParseUint(os.Getenv("MW_TARGET_PID"), 10, 32)
	if err != nil || expectedPID == 0 { t.Fatal("MW_TARGET_PID must identify the ordinary-user live fixture") }
	f, err := os.Open(name)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	scanner := bufio.NewScanner(f)
	scanner.Buffer(make([]byte, 4096), 1024*1024)
	matched := 0
	for scanner.Scan() {
		var record struct {
			Kind    string `json:"kind"`
			Payload struct {
				TGID     uint32 `json:"tgid"`
				TID      uint32 `json:"tid"`
				Sequence uint64 `json:"sequence"`
				RegisterRead *int32 `json:"register_read"`
				DirFD int32 `json:"dirfd"`
				Flags uint64 `json:"flags"`
				Filename string `json:"filename"`
				Ustack   struct {
					Correlation uint64 `json:"otel_correlation_id"`
					Symbols     string `json:"symbols"`
				} `json:"ustack"`
			} `json:"payload"`
		}
		if err := json.Unmarshal(scanner.Bytes(), &record); err != nil {
			t.Fatal(err)
		}
		if record.Kind != "capture_debug" || !strings.Contains(record.Payload.Filename, "mw-inline-open") {
			continue
		}
		p := record.Payload
		if uint64(p.TGID) != expectedPID || p.TID == 0 || p.Sequence == 0 {
			t.Fatalf("missing capture identity: %s", scanner.Bytes())
		}
		if p.RegisterRead == nil || *p.RegisterRead != 0 || p.DirFD != -100 || p.Flags != 0 {
			t.Fatalf("marked read-only fixture open has invalid syscall-wrapper arguments: pid=%d filename=%q dirfd=%d flags=%d read=%v", p.TGID, p.Filename, p.DirFD, p.Flags, p.RegisterRead)
		}
		if p.Ustack.Correlation != 0 && strings.Contains(p.Ustack.Symbols, "InlineOpenFixture") {
			matched++
		}
	}
	if err := scanner.Err(); err != nil {
		t.Fatal(err)
	}
	if matched == 0 {
		t.Fatal("no marked openat carried both an OTel reference and an expected Java application frame")
	}
}
