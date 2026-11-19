//go:build linux

package identity

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func initialNamespaceFixture() initialPIDNamespaceObservation {
	return initialPIDNamespaceObservation{
		SelfPID:             123,
		SelfLink:            "123",
		SelfNamespaceLink:   "pid:[4026531836]",
		TargetNamespaceLink: "pid:[4026531836]",
		SelfStat:            "123 (self with ) parentheses) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 456\n",
		TargetStat:          "456 (target with ) parentheses) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 789\n",
		SelfStatus:          "Name:\tself\nTgid:\t123\nPid:\t123\nNSpid:\t123\n",
		TargetStatus:        "Name:\ttarget\nTgid:\t456\nPid:\t456\nNSpid:\t456\n",
	}
}

func TestInitialPIDNamespaceClassification(t *testing.T) {
	if err := classifyInitialPIDNamespace(456, initialNamespaceFixture()); err != nil {
		t.Fatal("initial namespace with parentheses in comm", err)
	}
	for _, tc := range []struct {
		name   string
		mutate func(*initialPIDNamespaceObservation)
	}{
		{"nested_self_despite_single_nspid", func(o *initialPIDNamespaceObservation) { o.SelfNamespaceLink = "pid:[4026533000]" }},
		{"nested_target_despite_single_nspid", func(o *initialPIDNamespaceObservation) { o.TargetNamespaceLink = "pid:[4026533000]" }},
		{"self_link_pid_mismatch", func(o *initialPIDNamespaceObservation) { o.SelfLink = "456" }},
		{"self_pid_zero", func(o *initialPIDNamespaceObservation) { o.SelfPID = 0 }},
		{"self_stat_pid_mismatch", func(o *initialPIDNamespaceObservation) { o.SelfStat = strings.Replace(o.SelfStat, "123 (", "124 (", 1) }},
		{"target_stat_pid_mismatch", func(o *initialPIDNamespaceObservation) {
			o.TargetStat = strings.Replace(o.TargetStat, "456 (", "457 (", 1)
		}},
		{"self_stat_missing_birth", func(o *initialPIDNamespaceObservation) { o.SelfStat = "123 (self) S 1\n" }},
		{"target_stat_missing_birth", func(o *initialPIDNamespaceObservation) { o.TargetStat = "456 (target) S 1\n" }},
		{"self_stat_zero_birth", func(o *initialPIDNamespaceObservation) { o.SelfStat = strings.Replace(o.SelfStat, "18 456", "18 0", 1) }},
		{"target_stat_bad_birth", func(o *initialPIDNamespaceObservation) {
			o.TargetStat = strings.Replace(o.TargetStat, "18 789", "18 bad", 1)
		}},
		{"target_stat_overflow_birth", func(o *initialPIDNamespaceObservation) {
			o.TargetStat = strings.Replace(o.TargetStat, "18 789", "18 18446744073709551616", 1)
		}},
		{"self_stat_over_limit", func(o *initialPIDNamespaceObservation) { o.SelfStat += strings.Repeat(" ", 8192) }},
		{"target_status_over_limit", func(o *initialPIDNamespaceObservation) { o.TargetStatus += strings.Repeat("x", 65536) }},
	} {
		t.Run(tc.name, func(t *testing.T) {
			o := initialNamespaceFixture()
			tc.mutate(&o)
			if err := classifyInitialPIDNamespace(456, o); err == nil {
				t.Fatal("invalid namespace observation admitted")
			}
		})
	}
	for _, pid := range []int{0, -1, 123} {
		if err := classifyInitialPIDNamespace(pid, initialNamespaceFixture()); err == nil {
			t.Fatalf("invalid or mismatched target PID %d admitted", pid)
		}
	}
}

func TestInitialPIDNamespaceStrictLinkText(t *testing.T) {
	for _, text := range []string{"", "0", "0123", "+123", "-123", "123\n", "123/../123", "4294967296"} {
		t.Run("self_"+text, func(t *testing.T) {
			o := initialNamespaceFixture()
			o.SelfLink = text
			if err := classifyInitialPIDNamespace(456, o); err == nil {
				t.Fatal("invalid self link admitted", text)
			}
		})
	}
	for _, text := range []string{"", "pid:[402653183", "pid:[4026531836", "pid:[4026531836]\n", "pid:[04026531836]", "pid:[0]", "pid:[4294967296]", "mnt:[4026531836]", "pid:[4026531836]junk", strings.Repeat("x", 64)} {
		for _, target := range []bool{false, true} {
			t.Run(fmt.Sprintf("namespace_target_%t_%q", target, text), func(t *testing.T) {
				o := initialNamespaceFixture()
				if target {
					o.TargetNamespaceLink = text
				} else {
					o.SelfNamespaceLink = text
				}
				if err := classifyInitialPIDNamespace(456, o); err == nil {
					t.Fatal("malformed or truncated namespace link admitted", text)
				}
			})
		}
	}
}

func TestInitialPIDNamespaceStrictProcessFields(t *testing.T) {
	for _, target := range []bool{false, true} {
		pid := "123"
		if target {
			pid = "456"
		}
		for _, field := range []string{"Pid", "Tgid"} {
			for _, value := range []string{"124", "", "bad", "0", "-1", "+123", "4294967296", pid + " extra"} {
				t.Run(fmt.Sprintf("status_target_%t_%s_%q", target, field, value), func(t *testing.T) {
					o := initialNamespaceFixture()
					status := &o.SelfStatus
					if target {
						status = &o.TargetStatus
					}
					*status = strings.Replace(*status, field+":\t"+pid+"\n", field+":\t"+value+"\n", 1)
					if err := classifyInitialPIDNamespace(456, o); err == nil {
						t.Fatal("invalid status field admitted", field, value)
					}
				})
			}
			for _, duplicate := range []bool{false, true} {
				t.Run(fmt.Sprintf("status_target_%t_%s_duplicate_%t", target, field, duplicate), func(t *testing.T) {
					o := initialNamespaceFixture()
					status := &o.SelfStatus
					if target {
						status = &o.TargetStatus
					}
					line := field + ":\t" + pid + "\n"
					if duplicate {
						*status += line
					} else {
						*status = strings.Replace(*status, line, "", 1)
					}
					if err := classifyInitialPIDNamespace(456, o); err == nil {
						t.Fatal("missing or duplicate status field admitted", field)
					}
				})
			}
		}
		for _, value := range []string{"0", "bad", "4294967296", "+" + pid} {
			t.Run(fmt.Sprintf("stat_target_%t_%s", target, value), func(t *testing.T) {
				o := initialNamespaceFixture()
				stat := &o.SelfStat
				if target {
					stat = &o.TargetStat
				}
				*stat = strings.Replace(*stat, pid+" (", value+" (", 1)
				if err := classifyInitialPIDNamespace(456, o); err == nil {
					t.Fatal("invalid stat PID admitted", value)
				}
			})
		}
	}
}

func TestInitialPIDNamespaceRealSelf(t *testing.T) {
	link, err := os.Readlink("/proc/self/ns/pid")
	if err != nil {
		t.Fatal("read independent real-self namespace expectation", err)
	}
	err = CheckInitialPIDNamespace(os.Getpid())
	if link == "pid:[4026531836]" {
		if err != nil {
			t.Fatal("real initial-namespace self must pass guarded acquisition", err)
		}
	} else if err == nil {
		t.Fatalf("noninitial real self %q must explicitly refuse qualification", link)
	} else {
		t.Logf("noninitial real self explicitly refused: %v", err)
	}
}

func TestInitialPIDNamespaceRejectsForgedProcRoot(t *testing.T) {
	root := t.TempDir()
	pid := os.Getpid()
	dir := filepath.Join(root, fmt.Sprint(pid))
	if err := os.MkdirAll(filepath.Join(dir, "ns"), 0700); err != nil {
		t.Fatal(err)
	}
	for path, text := range map[string]string{
		"stat":   fmt.Sprintf("%d (convincing proc fixture) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 456\n", pid),
		"status": fmt.Sprintf("Name:\tfixture\nPid:\t%d\nTgid:\t%d\nNSpid:\t%d\n", pid, pid, pid),
	} {
		if err := os.WriteFile(filepath.Join(dir, path), []byte(text), 0600); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.Symlink(fmt.Sprint(pid), filepath.Join(root, "self")); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink("pid:[4026531836]", filepath.Join(dir, "ns", "pid")); err != nil {
		t.Fatal(err)
	}
	if err := checkInitialPIDNamespaceAt(root, pid); err == nil || !strings.Contains(strings.ToLower(err.Error()), "procfs") {
		t.Fatalf("non-procfs root with convincing identity text must fail authentication: %v", err)
	}
}

func TestInitialPIDNamespaceAcquisitionErrors(t *testing.T) {
	root := t.TempDir()
	if err := checkInitialPIDNamespaceAt(filepath.Join(root, "missing"), os.Getpid()); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("missing root must preserve underlying ENOENT: %v", err)
	}
	link := filepath.Join(root, "proc-link")
	if err := os.Symlink("/proc", link); err != nil {
		t.Fatal(err)
	}
	if err := checkInitialPIDNamespaceAt(link, os.Getpid()); err == nil {
		t.Fatal("symlink proc root must not be followed")
	}
}
