package driver

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"golang.org/x/sys/unix"
	"os"
	"path/filepath"
	"time"
)

func PrepareLossSource(root string) (destination string, failure error) {
	patchPath := filepath.Join(root, "patches/faults/drop-marked-event.patch")
	patch, err := os.ReadFile(patchPath)
	if err != nil {
		return "", err
	}
	hash := sha256.New()
	hash.Write(patch)
	source := filepath.Join(root, "gadgets/openat-stack")
	entries, err := os.ReadDir(source)
	if err != nil {
		return "", err
	}
	build := filepath.Join(root, "build")
	if err := os.MkdirAll(build, 0755); err != nil {
		return "", err
	}
	stage, err := os.MkdirTemp(build, ".loss-source-")
	if err != nil {
		return "", err
	}
	defer func() {
		if stage != "" {
			failure = errors.Join(failure, os.RemoveAll(stage))
		}
	}()
	for _, entry := range entries {
		info, err := entry.Info()
		if err != nil {
			return "", err
		}
		if !info.Mode().IsRegular() {
			return "", fmt.Errorf("loss gadget source must be physical flat files: %s", entry.Name())
		}
		data, err := os.ReadFile(filepath.Join(source, entry.Name()))
		if err != nil {
			return "", err
		}
		hash.Write([]byte(entry.Name() + "\x00"))
		hash.Write(data)
		path := filepath.Join(stage, entry.Name())
		if err := os.WriteFile(path, data, info.Mode().Perm()); err != nil {
			return "", err
		}
		if err := os.Chmod(path, info.Mode().Perm()); err != nil {
			return "", err
		}
	}
	identity := hex.EncodeToString(hash.Sum(nil))
	destination = filepath.Join(build, "loss-gadget-"+identity)
	if _, err := Output(stage, 10*time.Second, "patch", "--batch", "--forward", "--fuzz=0", "-p1", "-i", patchPath); err != nil {
		return "", err
	}
	if err := WriteJSON(filepath.Join(stage, "fault-manifest.json"), map[string]any{"fault": "drop_marked_event_sequence_42", "source_sha256": identity}); err != nil {
		return "", err
	}
	if err := unix.Renameat2(unix.AT_FDCWD, stage, unix.AT_FDCWD, destination, unix.RENAME_NOREPLACE); err == nil {
		stage = ""
		return destination, nil
	} else if !errors.Is(err, unix.EEXIST) {
		return "", err
	}
	info, err := os.Lstat(destination)
	if err != nil {
		return "", err
	}
	if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return "", fmt.Errorf("refusing nonphysical loss source destination")
	}
	expected, err := os.ReadDir(stage)
	if err != nil {
		return "", err
	}
	actual, err := os.ReadDir(destination)
	if err != nil {
		return "", err
	}
	if len(expected) != len(actual) {
		return "", fmt.Errorf("incomplete/extraneous prepared loss source")
	}
	for index, entry := range expected {
		if entry.Name() != actual[index].Name() {
			return "", fmt.Errorf("changed prepared loss source inventory")
		}
		left, err := entry.Info()
		if err != nil {
			return "", err
		}
		right, err := actual[index].Info()
		if err != nil {
			return "", err
		}
		if !right.Mode().IsRegular() || left.Mode().Perm() != right.Mode().Perm() {
			return "", fmt.Errorf("changed prepared loss source type/mode: %s", entry.Name())
		}
		want, err := os.ReadFile(filepath.Join(stage, entry.Name()))
		if err != nil {
			return "", err
		}
		got, err := os.ReadFile(filepath.Join(destination, entry.Name()))
		if err != nil {
			return "", err
		}
		if !bytes.Equal(want, got) {
			return "", fmt.Errorf("changed prepared loss source bytes: %s", entry.Name())
		}
	}
	return destination, nil
}
