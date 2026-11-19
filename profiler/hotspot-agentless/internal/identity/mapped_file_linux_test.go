//go:build linux

package identity

import (
	"errors"
	"os"
	"path/filepath"
	"testing"

	"golang.org/x/sys/unix"
)

func TestMappedJVMFileIdentity(t *testing.T) {
	for _, test := range []struct {
		name                                       string
		changeDevice, replace, missing, wrongInode bool
		wantOK                                     bool
	}{
		{name: "same_file", wantOK: true},
		{name: "btrfs_maps_device_differs", changeDevice: true, wantOK: true},
		{name: "path_replaced", replace: true},
		{name: "mapping_disappeared", missing: true},
		{name: "maps_inode_changed", wrongInode: true},
	} {
		t.Run(test.name, func(t *testing.T) {
			proc := t.TempDir()
			file, err := os.Create(filepath.Join(proc, "libjvm.so"))
			if err != nil {
				t.Fatal(err)
			}
			defer file.Close()
			if err := os.Mkdir(filepath.Join(proc, "map_files"), 0700); err != nil {
				t.Fatal(err)
			}
			mapped := filepath.Join(proc, "map_files", "1000-2000")
			if err := os.Link(file.Name(), mapped); err != nil {
				t.Fatal(err)
			}
			if test.replace {
				replacement, err := os.Create(filepath.Join(proc, "replacement"))
				if err != nil {
					t.Fatal(err)
				}
				defer replacement.Close()
				file = replacement
			}
			var st unix.Stat_t
			if err := unix.Fstat(int(file.Fd()), &st); err != nil {
				t.Fatal(err)
			}
			selected := mapping{start: 0x1000, end: 0x2000, inode: st.Ino,
				major: unix.Major(st.Dev), minor: unix.Minor(st.Dev)}
			if test.changeDevice {
				selected.minor ^= 1
			}
			if test.wrongInode {
				selected.inode++
			}
			if test.missing {
				if err := os.Remove(mapped); err != nil {
					t.Fatal(err)
				}
			}
			err = verifyMappedJVMFile(proc, selected, file)
			if (err == nil) != test.wantOK {
				t.Fatalf("accepted=%v, want=%v; error=%v", err == nil, test.wantOK, err)
			}
			if test.missing && !errors.Is(err, os.ErrNotExist) {
				t.Fatalf("lost original missing-mapping cause: %v", err)
			}
		})
	}
}

func TestMappedJVMFileIdentityPreservesPermissionDenial(t *testing.T) {
	if os.Geteuid() == 0 {
		t.Skip("directory permission denial requires a non-root test process")
	}
	proc := t.TempDir()
	file, err := os.Create(filepath.Join(proc, "libjvm.so"))
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()
	mapFiles := filepath.Join(proc, "map_files")
	if err := os.Mkdir(mapFiles, 0000); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { os.Chmod(mapFiles, 0700) })
	err = verifyMappedJVMFile(proc, mapping{start: 0x1000, end: 0x2000}, file)
	if !errors.Is(err, unix.EACCES) {
		t.Fatalf("expected original EACCES, got %v", err)
	}
}
