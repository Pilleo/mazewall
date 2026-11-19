//go:build linux

package identity

import (
	"errors"
	"fmt"
	"os"
	"strconv"
	"strings"

	"golang.org/x/sys/unix"
	"io.mazewall/experiments/hotspot-agentless/internal/preflight"
)

// Linux v7.2.11 UAPI PID_NS_INIT_INO; this is not the proc symlink's inode.
const initialPIDNamespaceLink = "pid:[4026531836]"
const pidNamespaceLinkLimit = 64
const pidNamespaceStatLimit = 8192
const pidNamespaceStatusLimit = 65536
const pidNamespaceResolve = unix.RESOLVE_BENEATH | unix.RESOLVE_NO_XDEV | unix.RESOLVE_NO_MAGICLINKS | unix.RESOLVE_NO_SYMLINKS

type initialPIDNamespaceObservation struct {
	SelfPID             int
	SelfLink            string
	SelfNamespaceLink   string
	TargetNamespaceLink string
	SelfStat            string
	SelfStatus          string
	TargetStat          string
	TargetStatus        string
}

// CheckInitialPIDNamespace checks host TGID correspondence, not process lifetime.
// The caller must separately bracket it with pidfd/exec/birth validation.
func CheckInitialPIDNamespace(pid int) error {
	return checkInitialPIDNamespaceAt("/proc", pid)
}

func checkInitialPIDNamespaceAt(procRoot string, pid int) (err error) {
	if !validNamespacePID(pid) {
		return fmt.Errorf("invalid target PID %d", pid)
	}
	fd, err := unix.Open(procRoot, unix.O_PATH|unix.O_DIRECTORY|unix.O_NOFOLLOW|unix.O_CLOEXEC, 0)
	if err != nil {
		return fmt.Errorf("open procfs root: %w", err)
	}
	defer func() {
		if closeErr := unix.Close(fd); closeErr != nil {
			err = errors.Join(err, fmt.Errorf("close procfs root: %w", closeErr))
		}
	}()
	if err = validatePIDNamespaceFD(fd, unix.S_IFDIR); err != nil {
		return fmt.Errorf("authenticate procfs root: %w", err)
	}
	o := initialPIDNamespaceObservation{SelfPID: os.Getpid()}
	if o.SelfLink, err = readPIDNamespaceLink(fd, "self"); err != nil {
		return err
	}
	self, err := parseNamespacePID(o.SelfLink)
	if err != nil {
		return fmt.Errorf("procfs self link: %w", err)
	}
	if uint64(self) != uint64(o.SelfPID) {
		return fmt.Errorf("procfs self PID does not match current process")
	}
	for _, process := range []struct {
		pid    int
		link   *string
		stat   *string
		status *string
	}{
		{o.SelfPID, &o.SelfNamespaceLink, &o.SelfStat, &o.SelfStatus},
		{pid, &o.TargetNamespaceLink, &o.TargetStat, &o.TargetStatus},
	} {
		base := strconv.Itoa(process.pid) + "/"
		if *process.link, err = readPIDNamespaceLink(fd, base+"ns/pid"); err != nil {
			return err
		}
		if *process.stat, err = readPIDNamespaceFile(fd, base+"stat", pidNamespaceStatLimit); err != nil {
			return err
		}
		if *process.status, err = readPIDNamespaceFile(fd, base+"status", pidNamespaceStatusLimit); err != nil {
			return err
		}
	}
	return classifyInitialPIDNamespace(pid, o)
}

func validatePIDNamespaceFD(fd int, mode uint32) error {
	var fs unix.Statfs_t
	if err := unix.Fstatfs(fd, &fs); err != nil {
		return fmt.Errorf("fstatfs procfs object: %w", err)
	}
	if fs.Type != unix.PROC_SUPER_MAGIC {
		return fmt.Errorf("object is not procfs")
	}
	var stat unix.Stat_t
	if err := unix.Fstat(fd, &stat); err != nil {
		return fmt.Errorf("fstat procfs object: %w", err)
	}
	if stat.Mode&unix.S_IFMT != mode {
		return fmt.Errorf("unexpected procfs object type")
	}
	return nil
}

func readPIDNamespaceLink(root int, path string) (text string, err error) {
	// O_PATH|O_NOFOLLOW retains the final link object even with NO_SYMLINKS.
	fd, err := unix.Openat2(root, path, &unix.OpenHow{
		Flags:   unix.O_PATH | unix.O_NOFOLLOW | unix.O_CLOEXEC,
		Resolve: pidNamespaceResolve,
	})
	if err != nil {
		return "", fmt.Errorf("open procfs link %s: %w", path, err)
	}
	defer func() {
		if closeErr := unix.Close(fd); closeErr != nil {
			err = errors.Join(err, fmt.Errorf("close procfs link %s: %w", path, closeErr))
		}
	}()
	if err = validatePIDNamespaceFD(fd, unix.S_IFLNK); err != nil {
		return "", fmt.Errorf("authenticate procfs link %s: %w", path, err)
	}
	var buf [pidNamespaceLinkLimit]byte
	n, err := unix.Readlinkat(fd, "", buf[:])
	if err != nil {
		return "", fmt.Errorf("read procfs link %s: %w", path, err)
	}
	if n == len(buf) {
		return "", fmt.Errorf("truncated procfs link %s", path)
	}
	return string(buf[:n]), nil
}

func readPIDNamespaceFile(root int, path string, limit int) (text string, err error) {
	fd, err := unix.Openat2(root, path, &unix.OpenHow{
		Flags:   unix.O_RDONLY | unix.O_NOFOLLOW | unix.O_CLOEXEC | unix.O_NONBLOCK,
		Resolve: pidNamespaceResolve,
	})
	if err != nil {
		return "", fmt.Errorf("open procfs identity %s: %w", path, err)
	}
	defer func() {
		if closeErr := unix.Close(fd); closeErr != nil {
			err = errors.Join(err, fmt.Errorf("close procfs identity %s: %w", path, closeErr))
		}
	}()
	if err = validatePIDNamespaceFD(fd, unix.S_IFREG); err != nil {
		return "", fmt.Errorf("authenticate procfs identity %s: %w", path, err)
	}
	buf := make([]byte, limit+1)
	used := 0
	for used < len(buf) {
		n, readErr := unix.Read(fd, buf[used:])
		if readErr != nil {
			return "", fmt.Errorf("read procfs identity %s: %w", path, readErr)
		}
		if n == 0 {
			return string(buf[:used]), nil
		}
		used += n
	}
	return "", fmt.Errorf("procfs identity %s exceeds bounded read", path)
}

func validNamespacePID(pid int) bool {
	return pid > 0 && uint64(pid) <= uint64(^uint32(0))
}

func parseNamespacePID(text string) (uint32, error) {
	if text == "" || text[0] == '0' {
		return 0, fmt.Errorf("missing or zero/noncanonical PID")
	}
	for _, c := range text {
		if c < '0' || c > '9' {
			return 0, fmt.Errorf("nondecimal PID")
		}
	}
	pid, err := strconv.ParseUint(text, 10, 32)
	if err != nil {
		return 0, fmt.Errorf("invalid PID: %w", err)
	}
	return uint32(pid), nil
}

func classifyInitialPIDNamespace(pid int, o initialPIDNamespaceObservation) error {
	if !validNamespacePID(pid) || !validNamespacePID(o.SelfPID) {
		return fmt.Errorf("invalid self or target PID")
	}
	if len(o.SelfLink) >= pidNamespaceLinkLimit {
		return fmt.Errorf("truncated procfs self link")
	}
	self, err := parseNamespacePID(o.SelfLink)
	if err != nil {
		return fmt.Errorf("invalid procfs self link: %w", err)
	}
	if uint64(self) != uint64(o.SelfPID) {
		return fmt.Errorf("procfs self PID mismatch")
	}
	if o.SelfNamespaceLink != initialPIDNamespaceLink || o.TargetNamespaceLink != initialPIDNamespaceLink {
		return fmt.Errorf("self and target must be in the initial PID namespace")
	}
	if err := validateNamespaceProcess(o.SelfPID, o.SelfStat, o.SelfStatus); err != nil {
		return fmt.Errorf("self procfs identity: %w", err)
	}
	if err := validateNamespaceProcess(pid, o.TargetStat, o.TargetStatus); err != nil {
		return fmt.Errorf("target procfs identity: %w", err)
	}
	return nil
}

func validateNamespaceProcess(pid int, stat, status string) error {
	if len(stat) > pidNamespaceStatLimit || len(status) > pidNamespaceStatusLimit {
		return fmt.Errorf("procfs identity exceeds bounded input")
	}
	prefix, _, ok := strings.Cut(stat, " (")
	if !ok {
		return fmt.Errorf("missing stat comm delimiter")
	}
	statPID, err := parseNamespacePID(prefix)
	if err != nil {
		return fmt.Errorf("stat PID: %w", err)
	}
	if uint64(statPID) != uint64(pid) {
		return fmt.Errorf("stat PID mismatch")
	}
	ticks, err := preflight.ParseStartTicks(stat)
	if err != nil {
		return fmt.Errorf("stat birth: %w", err)
	}
	if ticks == 0 {
		return fmt.Errorf("missing stat birth")
	}
	var seen [2]bool
	for _, line := range strings.Split(status, "\n") {
		key, value, _ := strings.Cut(line, ":")
		index := 0
		switch key {
		case "Pid":
		case "Tgid":
			index = 1
		default:
			continue
		}
		if seen[index] {
			return fmt.Errorf("duplicate status %s", key)
		}
		seen[index] = true
		fields := strings.Fields(value)
		if len(fields) != 1 {
			return fmt.Errorf("malformed status %s", key)
		}
		valuePID, err := parseNamespacePID(fields[0])
		if err != nil {
			return fmt.Errorf("status %s: %w", key, err)
		}
		if uint64(valuePID) != uint64(pid) {
			return fmt.Errorf("status %s mismatch", key)
		}
	}
	if !seen[0] || !seen[1] {
		return fmt.Errorf("missing status Pid or Tgid")
	}
	return nil
}
