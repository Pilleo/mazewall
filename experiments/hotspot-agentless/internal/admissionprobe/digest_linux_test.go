//go:build linux && mw_kernel

package admissionprobe

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"testing"
)

func TestActualKernelKnownAnswersAndBudget(t *testing.T) {
	path := filepath.Join(t.TempDir(), "input")
	for _, size := range []int{0, 3, 55, 56, 63, 64, 65, 65535, 65536, 65537, 1048576} {
		t.Run(strconv.Itoa(size), func(t *testing.T) {
			data := bytes.Repeat([]byte{0x61}, size)
			if err := os.WriteFile(path, data, 0600); err != nil {
				t.Fatal(err)
			}
			got, err := DigestFile(path, int64(size))
			if err != nil {
				t.Fatal("actual AF_ALG qualification failed", err)
			}
			if got != sha256.Sum256(data) {
				t.Fatal("native digest mismatch", size)
			}
			if size > 0 {
				if _, err := DigestFile(path, int64(size-1)); err == nil {
					t.Fatal("byte limit ignored")
				}
			}
			after, err := os.ReadFile(path)
			if err != nil || !bytes.Equal(after, data) {
				t.Fatal("source modified", err)
			}
		})
	}
}

func TestBudgetsAndFileErrors(t *testing.T) {
	path := filepath.Join(t.TempDir(), "missing")
	for _, maximum := range []int64{-1, (512 << 20) + 1} {
		if _, err := DigestFile(path, maximum); err == nil || errors.Is(err, os.ErrNotExist) {
			t.Fatal("invalid budget must fail before file access", maximum, err)
		}
	}
	if _, err := DigestFile(path, 512<<20); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("original file error lost", err)
	}
}

// Run explicitly with -bench and -benchtime=1x after verifying the canonical bundle.
// Timing includes file/socket ownership and checked close, but not manifest parsing.
func BenchmarkActualKernelCanonicalBundle(b *testing.B) {
	root := filepath.Join("..", "..", "..", "..", "profiler", "hotspot-agentless", "build", "assets")
	data, err := os.ReadFile(filepath.Join(root, "manifest.json"))
	if err != nil {
		b.Fatal(err)
	}
	var manifest struct {
		Files map[string]string `json:"files"`
	}
	if err := json.Unmarshal(data, &manifest); err != nil || len(manifest.Files) == 0 {
		b.Fatal("invalid canonical manifest", err)
	}
	names := make([]string, 0, len(manifest.Files))
	for name := range manifest.Files {
		names = append(names, name)
	}
	sort.Strings(names)
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		for _, name := range names {
			digest, err := DigestFile(filepath.Join(root, name), 512<<20)
			if err != nil {
				b.Fatal("actual AF_ALG qualification failed", name, err)
			}
			if hex.EncodeToString(digest[:]) != manifest.Files[name] {
				b.Fatal("canonical manifest mismatch", name)
			}
		}
	}
}
