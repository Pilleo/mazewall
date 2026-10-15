package tooling

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func releaseFixture(t *testing.T) string {
	t.Helper()
	root := filepath.Join(t.TempDir(), "release")
	if err := os.MkdirAll(filepath.Join(root, "licenses"), 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "operator.txt"), []byte("verified fixture"), 0644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "licenses/Apache-2.0.txt"), []byte("license fixture"), 0644); err != nil {
		t.Fatal(err)
	}
	return root
}

func TestDistributionIndexRoundtripAndNoticeTamper(t *testing.T) {
	root := releaseFixture(t)
	if err := writeDistributionChecksums(root); err != nil {
		t.Fatal(err)
	}
	if err := checkDistributionChecksums(root); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "licenses/Apache-2.0.txt"), []byte("changed"), 0644); err != nil {
		t.Fatal(err)
	}
	if err := checkDistributionChecksums(root); err == nil {
		t.Fatal("tampered notice accepted")
	}
}

func TestDistributionMissingAndUnlistedIncludingNestedChecksumNames(t *testing.T) {
	for _, name := range []string{"surprise", "licenses/SHA256SUMS"} {
		root := releaseFixture(t)
		if err := writeDistributionChecksums(root); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(root, name), []byte("unlisted"), 0644); err != nil {
			t.Fatal(err)
		}
		if err := checkDistributionChecksums(root); err == nil {
			t.Fatalf("unlisted file accepted: %s", name)
		}
	}
	root := releaseFixture(t)
	if err := writeDistributionChecksums(root); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(filepath.Join(root, "operator.txt")); err != nil {
		t.Fatal(err)
	}
	if err := checkDistributionChecksums(root); err == nil {
		t.Fatal("missing distribution file accepted")
	}
}

func TestDistributionRejectsSymbolicTraversingAndDuplicatePaths(t *testing.T) {
	root := releaseFixture(t)
	if err := os.Symlink(filepath.Join(root, "operator.txt"), filepath.Join(root, "linked")); err != nil {
		t.Fatal(err)
	}
	if err := writeDistributionChecksums(root); err == nil {
		t.Fatal("symbolic distribution member admitted")
	}
	if err := os.Remove(filepath.Join(root, "linked")); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "SHA256SUMS"), []byte("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa  ../outside\n"), 0644); err != nil {
		t.Fatal(err)
	}
	if err := checkDistributionChecksums(root); err == nil || !strings.Contains(err.Error(), "invalid asset path") {
		t.Fatalf("escaping path was not rejected at admission: %v", err)
	}
	if err := writeDistributionChecksums(root); err != nil {
		t.Fatal(err)
	}
	index, err := os.ReadFile(filepath.Join(root, "SHA256SUMS"))
	if err != nil {
		t.Fatal(err)
	}
	index = append(index, []byte("f9adb7d924ed98c558040c910600d7363d749e7d20e8d355626edd53b4fb929f  operator.txt\n")...)
	if err := os.WriteFile(filepath.Join(root, "SHA256SUMS"), index, 0644); err != nil {
		t.Fatal(err)
	}
	if err := checkDistributionChecksums(root); err == nil || !strings.Contains(err.Error(), "invalid distribution checksum index") {
		t.Fatalf("duplicate index entry admitted: %v", err)
	}
}

func TestDistributionArchivesAreReproducibleAndNeverOverwrite(t *testing.T) {
	root := releaseFixture(t)
	if err := writeDistributionChecksums(root); err != nil {
		t.Fatal(err)
	}
	first := filepath.Join(filepath.Dir(root), "one.tar.gz")
	second := filepath.Join(filepath.Dir(root), "two.tar.gz")
	if err := archiveDistribution(root, first); err != nil {
		t.Fatal(err)
	}
	if err := archiveDistribution(root, second); err != nil {
		t.Fatal(err)
	}
	a, err := os.ReadFile(first)
	if err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(second)
	if err != nil {
		t.Fatal(err)
	}
	if string(a) != string(b) {
		t.Fatal("identical distribution trees produced different archive bytes")
	}
	if err := archiveDistribution(root, first); err == nil {
		t.Fatal("existing archive overwritten")
	}
}
